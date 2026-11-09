package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(Parameterized::class)
class LegacySessionTemporaryFilesTest(private val isCanvas:Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="canvas={0}")
        fun stores()=listOf(arrayOf<Any>(false),arrayOf<Any>(true))
    }
    private class Store(val dir:File,val isCanvas:Boolean,blockEncoding:(()->Unit)?=null) {
        private val note=if(isCanvas) null else SessionStore(dir,DocumentCodec(FakeImageCodec(),FakeTextMeasurer()),File(dir,"pdf"),File(dir,"images"))
        private val canvas=if(isCanvas) CanvasSessionStore(dir,CanvasCodec(FakeImageCodec(),FakeTextMeasurer()),File(dir,"images")) else null
        private val noteDocument=Document(pages=blockingList(mutableListOf(Page(612.0,792.0)),blockEncoding))
        private val canvasDocument=InfiniteDocument(waypoints=blockingList(mutableListOf(Waypoint("pause",1.0,2.0,1.0)),blockEncoding))
        val suffix=if(isCanvas) ".xcanvas.tmp" else ".xnote.tmp"
        val prefix=if(isCanvas) "canvas-" else "document-"
        val body get()=File(dir,if(isCanvas) "canvas.xcanvas" else "document.xnote")
        fun save(withBody:Boolean)=if(isCanvas) canvas!!.saveChecked(if(withBody) canvasDocument else null,CanvasSessionStore.Metadata(null,null,false))
            else note!!.saveChecked(if(withBody) noteDocument else null,SessionStore.Metadata(null,null,false,1.0,0.0,0.0,false,ViewOverrides()))
        fun load()=if(isCanvas) canvas!!.load() else note!!.load()
        companion object {
            private fun <T> blockingList(items:MutableList<T>,block:(()->Unit)?):MutableList<T> =
                object:MutableList<T> by items {
                    override fun iterator():MutableIterator<T> {block?.invoke();return items.iterator()}
                }
        }
    }
    private fun scenario(block:(File,Boolean)->Unit) {
        val dir=Files.createTempDirectory("legacy-session-temps").toFile()
        try {block(dir,isCanvas)} finally {dir.deleteRecursively()}
    }
    private fun orphan(dir:File,isCanvas:Boolean)=File.createTempFile(if(isCanvas) "canvas-" else "document-",if(isCanvas) ".xcanvas.tmp" else ".xnote.tmp",dir)
        .apply {writeText("interrupted body candidate")}

    @Test fun startupRemovesInterruptedBodyCandidatesAndPreservesPublishedSessionAndUnrelatedFiles()=scenario {dir,isCanvas->
        val initial=Store(dir,isCanvas)
        assertEquals(LegacySessionWrite.SAVED,initial.save(true))
        val published=initial.body.readBytes()
        val abandoned=orphan(dir,isCanvas)
        val unrelated=File(dir,"keep.bin").apply {writeText("keep")}
        val directory=File(dir,initial.prefix+"folder"+initial.suffix).apply {mkdir();File(this,"keep").writeText("keep")}

        val restarted=Store(dir,isCanvas)

        assertFalse("A previous process's body candidate must be removed at startup",abandoned.exists())
        assertArrayEquals(published,restarted.body.readBytes())
        assertNotNull(restarted.load())
        assertEquals("keep",unrelated.readText());assertEquals("keep",File(directory,"keep").readText())
    }

    @Test fun writeRemovesCandidatesLeftAfterThisStoreWasCreatedWithoutReencodingBody()=scenario {dir,isCanvas->
        val store=Store(dir,isCanvas)
        assertEquals(LegacySessionWrite.SAVED,store.save(true))
        val published=store.body.readBytes()
        val abandoned=orphan(dir,isCanvas)

        assertEquals(LegacySessionWrite.SAVED,store.save(false))

        assertFalse("Metadata refresh must also reclaim interrupted candidates",abandoned.exists())
        assertArrayEquals(published,store.body.readBytes())
    }

    @Test fun cleanupFromAnotherStoreUsingAnAliasKeepsTheActiveBodyWriter()=scenario {dir,isCanvas->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val store=Store(dir,isCanvas) {
            entered.countDown()
            check(release.await(5,TimeUnit.SECONDS)) {"Writer was not released"}
        }
        val result=AtomicReference<LegacySessionWrite>()
        val writer=thread(name="session-body-writer") {result.set(store.save(true))}
        try {
            assertTrue("Encoding did not begin",entered.await(5,TimeUnit.SECONDS))
            val active=dir.listFiles()!!.single {it.name.startsWith(store.prefix) && it.name.endsWith(store.suffix)}
            val abandoned=orphan(dir,isCanvas)
            val alias=File(dir,".")
            val another=Store(alias,isCanvas)
            assertEquals(LegacySessionWrite.NEEDS_BODY,another.save(false))

            assertFalse("Cleanup must reclaim inactive candidates while a writer is active",abandoned.exists())
            assertTrue("Another instance must not unlink a live writer's candidate",active.isFile)
        } finally {release.countDown();writer.join(5000)}
        assertFalse("The writer did not finish",writer.isAlive)
        assertEquals(LegacySessionWrite.SAVED,result.get())
        assertNotNull(store.load())
        assertTrue(dir.listFiles()!!.none {it.name.endsWith(store.suffix)})
    }
}
