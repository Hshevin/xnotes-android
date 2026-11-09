package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LegacySessionStoreCheckedTest {
    private fun withDirectory(block:(File)->Unit) {
        val dir=Files.createTempDirectory("legacy-session-checked").toFile()
        try {block(dir)} finally {dir.deleteRecursively()}
    }
    private fun note(dir:File)=SessionStore(dir,DocumentCodec(FakeImageCodec(),FakeTextMeasurer()),File(dir,"pdf"),File(dir,"images"))
    private fun canvas(dir:File)=CanvasSessionStore(dir,CanvasCodec(FakeImageCodec(),FakeTextMeasurer()),File(dir,"images"))
    private fun noteMeta(path:String?="content://notes/A",name:String?="A.xnote",dirty:Boolean=true,zoom:Double=1.5)=
        SessionStore.Metadata(path,name,dirty,zoom,12.0,34.0,true,ViewOverrides(rotation=90))
    private fun canvasMeta(path:String?="content://canvas/A",name:String?="A.xcanvas",dirty:Boolean=true)=
        CanvasSessionStore.Metadata(path,name,dirty)
    private fun missingImage(dir:File)=ImageItem(ImageData(File(dir,"missing.png"),64,48),Rect(1.0,2.0,64.0,48.0))

    @Test fun noteMetadataOnlyNeedsBodyWithoutWritingOrReplacingMetadata() {
        for(existingMeta in listOf(false,true)) withDirectory {dir->
            val metadata=File(dir,"session.json")
            if(existingMeta) metadata.writeText("preserve existing metadata")
            assertEquals(LegacySessionWrite.NEEDS_BODY,note(dir).saveChecked(null,noteMeta()))
            assertFalse(File(dir,"document.xnote").exists())
            if(existingMeta) assertEquals("preserve existing metadata",metadata.readText()) else assertFalse(metadata.exists())
        }
    }

    @Test fun canvasMetadataOnlyNeedsBodyWithoutWritingOrReplacingMetadata() {
        for(existingMeta in listOf(false,true)) withDirectory {dir->
            val metadata=File(dir,"canvas-session.json")
            if(existingMeta) metadata.writeText("preserve existing metadata")
            assertEquals(LegacySessionWrite.NEEDS_BODY,canvas(dir).saveChecked(null,canvasMeta()))
            assertFalse(File(dir,"canvas.xcanvas").exists())
            if(existingMeta) assertEquals("preserve existing metadata",metadata.readText()) else assertFalse(metadata.exists())
        }
    }

    @Test fun noteCheckedSaveUsesCapturedMetadataAndViewOnlyKeepsBodyBytes() {withDirectory {dir->
        val store=note(dir)
        val document=Document.blank(2).apply {path="live path";displayName="live name";dirty=false}
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(document,noteMeta()))
        val body=File(dir,"document.xnote").readBytes()
        store.load()!!.let {
            assertEquals(2,it.document.pages.size);assertEquals("content://notes/A",it.document.path)
            assertEquals("A.xnote",it.document.displayName);assertTrue(it.document.dirty)
            assertEquals(1.5,it.zoom,0.0);assertEquals(12.0,it.scrollX,0.0);assertEquals(34.0,it.scrollY,0.0)
            assertTrue(it.zoomLocked);assertEquals(ViewOverrides(rotation=90),it.viewOverrides)
        }
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(null,noteMeta(null,null,false,2.0)))
        assertArrayEquals(body,File(dir,"document.xnote").readBytes())
        store.load()!!.let {
            assertEquals(2,it.document.pages.size);assertNull(it.document.path);assertNull(it.document.displayName)
            assertFalse(it.document.dirty);assertEquals(2.0,it.zoom,0.0)
        }
    }}

    @Test fun canvasCheckedSaveUsesCapturedMetadataAndViewOnlyKeepsBodyBytes() {withDirectory {dir->
        val store=canvas(dir)
        val document=InfiniteDocument(dpi=173,lastView=Waypoint("",20.0,30.0,2.5)).apply {
            path="live path";displayName="live name";dirty=false
        }
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(document,canvasMeta()))
        val body=File(dir,"canvas.xcanvas").readBytes()
        store.load()!!.let {
            assertEquals(173,it.dpi);assertEquals(document.lastView,it.lastView)
            assertEquals("content://canvas/A",it.path);assertEquals("A.xcanvas",it.displayName);assertTrue(it.dirty)
        }
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(null,canvasMeta(null,null,false)))
        assertArrayEquals(body,File(dir,"canvas.xcanvas").readBytes())
        store.load()!!.let {assertEquals(173,it.dpi);assertNull(it.path);assertNull(it.displayName);assertFalse(it.dirty)}
    }}

    @Test fun noteEncodeFailurePreservesPreviousBodyMetadataAndCleansCandidate() {withDirectory {dir->
        val store=note(dir)
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(Document.blank(2),noteMeta()))
        val before=dir.listFiles()!!.associate {it.name to it.readBytes().toList()}
        val broken=Document.blank(3).apply {pages.first().items.add(missingImage(dir))}
        assertEquals(LegacySessionWrite.FAILED,store.saveChecked(broken,noteMeta("new","new",false)))
        assertEquals(before,dir.listFiles()!!.associate {it.name to it.readBytes().toList()})
        assertEquals(2,store.load()!!.document.pages.size)
    }}

    @Test fun canvasEncodeFailurePreservesPreviousBodyMetadataAndCleansCandidate() {withDirectory {dir->
        val store=canvas(dir)
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(InfiniteDocument(dpi=173),canvasMeta()))
        val before=dir.listFiles()!!.associate {it.name to it.readBytes().toList()}
        val broken=InfiniteDocument(dpi=200).apply {add(missingImage(dir))}
        assertEquals(LegacySessionWrite.FAILED,store.saveChecked(broken,canvasMeta("new","new",false)))
        assertEquals(before,dir.listFiles()!!.associate {it.name to it.readBytes().toList()})
        assertEquals(173,store.load()!!.dpi)
    }}

    @Test fun metadataPublicationFailureIsReportedWithoutChangingExistingBody() {
        for(isCanvas in listOf(false,true)) withDirectory {dir->
            val note=note(dir);val canvas=canvas(dir)
            val saved=if(isCanvas) canvas.saveChecked(InfiniteDocument(),canvasMeta()) else note.saveChecked(Document.blank(1),noteMeta())
            assertEquals(LegacySessionWrite.SAVED,saved)
            val body=File(dir,if(isCanvas) "canvas.xcanvas" else "document.xnote")
            val bytes=body.readBytes()
            File(dir,if(isCanvas) "canvas-session.json" else "session.json").apply {
                assertTrue(delete());assertTrue(mkdir());File(this,"keep").writeText("not replaceable")
            }
            val result=if(isCanvas) canvas.saveChecked(null,canvasMeta()) else note.saveChecked(null,noteMeta())
            assertEquals(LegacySessionWrite.FAILED,result);assertArrayEquals(bytes,body.readBytes())
        }
    }

    @Test fun metadataFailureAfterBodyReplacementStillReportsFailure() {
        for(isCanvas in listOf(false,true)) withDirectory {dir->
            val note=note(dir);val canvas=canvas(dir)
            assertEquals(LegacySessionWrite.SAVED,if(isCanvas)
                canvas.saveChecked(InfiniteDocument(dpi=173),canvasMeta()) else note.saveChecked(Document.blank(1),noteMeta()))
            File(dir,if(isCanvas) "canvas-session.json" else "session.json").apply {
                assertTrue(delete());assertTrue(mkdir());File(this,"keep").writeText("not replaceable")
            }
            val result=if(isCanvas) canvas.saveChecked(InfiniteDocument(dpi=257),canvasMeta())
                else note.saveChecked(Document.blank(5),noteMeta())
            assertEquals(LegacySessionWrite.FAILED,result)
            // Legacy has two files: publishing the body does not promise sidecar publication.
            if(isCanvas) assertEquals(257,canvas.load()!!.dpi) else assertEquals(5,note.load()!!.document.pages.size)
        }
    }

    @Test fun clearReportsBodyDeletionFailureAndPreservesItsSidecar() {
        for(isCanvas in listOf(false,true)) withDirectory {dir->
            val body=File(dir,if(isCanvas) "canvas.xcanvas" else "document.xnote").apply {
                assertTrue(mkdir());File(this,"keep").writeText("not deletable as a file")
            }
            val metadata=File(dir,if(isCanvas) "canvas-session.json" else "session.json").apply {writeText("keep metadata")}
            val result=if(isCanvas) canvas(dir).clearChecked() else note(dir).clearChecked()
            assertFalse(result);assertTrue(body.isDirectory);assertEquals("keep metadata",metadata.readText())
        }
    }

    @Test fun clearReportsMetadataDeletionFailureAndSuccessfulClearRestoresNothing() {
        for(isCanvas in listOf(false,true)) withDirectory {dir->
            val metadata=File(dir,if(isCanvas) "canvas-session.json" else "session.json").apply {
                assertTrue(mkdir());File(this,"keep").writeText("not replaceable")
            }
            assertFalse(if(isCanvas) canvas(dir).clearChecked() else note(dir).clearChecked())
            metadata.deleteRecursively()
            val result=if(isCanvas) {
                val store=canvas(dir);assertEquals(LegacySessionWrite.SAVED,store.saveChecked(InfiniteDocument(),canvasMeta()))
                store.clearChecked().also {assertNull(store.load())}
            } else {
                val store=note(dir);assertEquals(LegacySessionWrite.SAVED,store.saveChecked(Document.blank(1),noteMeta()))
                store.clearChecked().also {assertNull(store.load())}
            }
            assertTrue(result)
        }
    }

    @Test fun compatibilitySaveStillEncodesExplicitDocumentWhenBodyIsMissing() {withDirectory {dir->
        val note=note(File(dir,"note"))
        note.save(Document.blank(3),1.0,0.0,0.0,false,ViewOverrides(),writeDocument=false)
        assertEquals(3,note.load()!!.document.pages.size)
        val canvas=canvas(File(dir,"canvas"))
        canvas.save(InfiniteDocument(dpi=173),writeDocument=false)
        assertEquals(173,canvas.load()!!.dpi)
    }}
}
