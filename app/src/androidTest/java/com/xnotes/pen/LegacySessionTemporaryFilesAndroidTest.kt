package com.xnotes.pen

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.CanvasSessionStore
import com.xnotes.platform.LegacySessionWrite
import com.xnotes.platform.SessionStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Actual Android file replacement and cross-instance candidate lifetime. */
class LegacySessionTemporaryFilesAndroidTest {
    private class Store(val dir:File,val isCanvas:Boolean,block:(()->Unit)?=null) {
        private val note=if(isCanvas) null else SessionStore(dir,DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()),File(dir,"pdf"),File(dir,"images"))
        private val canvas=if(isCanvas) CanvasSessionStore(dir,CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()),File(dir,"images")) else null
        private val noteDocument=Document(pages=blockingList(mutableListOf(Page(612.0,792.0)),block))
        private val canvasDocument=InfiniteDocument(waypoints=blockingList(mutableListOf(Waypoint("pause",1.0,2.0,1.0)),block))
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
        for(isCanvas in listOf(false,true)) {
            val dir=File(PenTestRunner.instance.targetContext.cacheDir,"session-temps-${UUID.randomUUID()}").apply {mkdirs()}
            try {block(dir,isCanvas)} finally {dir.deleteRecursively()}
        }
    }
    private fun orphan(dir:File,isCanvas:Boolean)=File.createTempFile(if(isCanvas) "canvas-" else "document-",if(isCanvas) ".xcanvas.tmp" else ".xnote.tmp",dir)
        .apply {writeText("interrupted body candidate")}

    @Test fun restartAndMetadataRefreshReclaimOrphansWithoutChangingPublishedBytes()=scenario {dir,isCanvas->
        val initial=Store(dir,isCanvas);assertEquals(LegacySessionWrite.SAVED,initial.save(true))
        val bytes=initial.body.readBytes();val first=orphan(dir,isCanvas)
        val restarted=Store(dir,isCanvas)
        assertFalse(first.exists());assertArrayEquals(bytes,restarted.body.readBytes());assertNotNull(restarted.load())
        val second=orphan(dir,isCanvas)
        assertEquals(LegacySessionWrite.SAVED,restarted.save(false))
        assertFalse(second.exists());assertArrayEquals(bytes,restarted.body.readBytes())
    }

    @Test fun anotherStoreCannotDeleteTheActiveWriterThroughAnAliasDirectory()=scenario {dir,isCanvas->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val store=Store(dir,isCanvas) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val result=AtomicReference<LegacySessionWrite>()
        val writer=thread(name="android-session-writer") {result.set(store.save(true))}
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val active=dir.listFiles()!!.single {it.name.startsWith(store.prefix) && it.name.endsWith(store.suffix)}
            val abandoned=orphan(dir,isCanvas);val another=Store(File(dir,"."),isCanvas)
            assertEquals(LegacySessionWrite.NEEDS_BODY,another.save(false))
            assertFalse(abandoned.exists());assertTrue(active.isFile)
        } finally {release.countDown();writer.join(5000)}
        assertFalse(writer.isAlive);assertEquals(LegacySessionWrite.SAVED,result.get());assertNotNull(store.load())
    }
}
