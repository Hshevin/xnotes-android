package com.xnotes.pen

import android.content.ContextWrapper
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Document
import com.xnotes.core.model.snapshot
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.NativeWorkingSession
import com.xnotes.platform.WorkingSessionCache
import com.xnotes.storage.NotebookStorage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Platform JSON, private filesystem and real codecs. Compilation alone is not Android acceptance. */
class NativeWorkingSessionAndroidTest {
    private fun scenario(block:(NotebookStorage,File,DocumentCodec,CanvasCodec)->Unit) {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"native-working-${UUID.randomUUID()}").apply {mkdir()}
        val context=object:ContextWrapper(PenTestRunner.instance.targetContext) {override fun getNoBackupFilesDir()=root}
        val storage=NotebookStorage(context)
        val note=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val canvas=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
        try {block(storage,root,note,canvas)} finally {storage.registry.close();root.deleteRecursively()}
    }
    @Test fun portablePdfBodySurvivesViewOnlyChangesWithoutItsTemporaryOriginal()=scenario {storage,root,note,canvas->
        val directory=File(root,"working");val store=NativeWorkingSession(directory,note,canvas,storage.pdfResources)
        val pdf=File(root,"draft.pdf").apply {writeText("%PDF-platform-working-original")}
        val bytes=pdf.readBytes();val doc=Document.blank().apply {pdfFile=pdf;dirty=true}
        val view=NativeWorkingSession.NoteView(1.0,10.0,20.0,false,ViewOverrides(rotation=90))
        assertTrue(store.publish(store.capture(doc,null,view)).published)
        val held=store.load()!!;val body=held.body
        try {
            assertEquals(BundleMigration.hash(body),held.sha256)
            java.util.zip.ZipFile(body).use {zip->assertArrayEquals(bytes,zip.getInputStream(zip.getEntry("assets/source.pdf")).use {it.readBytes()})}
            assertSame(pdf,doc.pdfFile);assertNull(doc.identity.pdfResource);assertTrue(doc.dirty)
            assertTrue(pdf.delete())
            val moved=view.copy(scrollY=200.0);val request=store.capture(doc,null,moved)
            assertFalse(request.requiresBody);assertTrue(store.publish(request).published)
            store.load()!!.use {assertEquals(body,it.body);assertEquals(moved,it.metadata.noteView)}
            val drawing=InfiniteDocument().apply {lastView=Waypoint("",2.0,3.0,.75)}
            assertTrue(store.publish(store.capture(drawing,null)).published)
            drawing.lastView=null
            val clearView=store.capture(drawing,null);assertFalse(clearView.requiresBody);assertTrue(store.publish(clearView).published)
            store.load()!!.use {assertNull(it.metadata.canvasView);assertEquals(WorkingSessionCache.Kind.CANVAS,it.kind)}
            assertTrue(body.isFile)
        } finally {held.close()}
        assertFalse(body.exists())
    }
    @Test fun platformNullMetadataAndEscapedDuplicatesCannotEraseARecoverableBody()=scenario {storage,root,note,canvas->
        for(raw in listOf(
            """{"version":1,"state":"cleared","metadata":null}""",
            """{"version":1,"state":"active","st\u0061te":"cleared"}""",
        )) {
            val directory=File(root,UUID.randomUUID().toString()).apply {mkdir()}
            val body=File(directory,"body-${UUID.randomUUID()}.zip")
            body.outputStream().use {note.write(Document.blank().snapshot(),it)}
            File(directory,"head.json").writeText(raw)
            val store=NativeWorkingSession(directory,note,canvas,storage.pdfResources)
            try {store.load();fail("must reject ambiguous envelope")} catch(_:WorkingSessionCache.Invalid) {}
            try {store.publishClear(store.reserveClear());fail("must preserve invalid envelope")} catch(_:WorkingSessionCache.Invalid) {}
            assertTrue(body.isFile);assertEquals(raw,File(directory,"head.json").readText())
        }
        val directory=File(root,"typed");val store=NativeWorkingSession(directory,note,canvas,storage.pdfResources)
        val doc=Document.blank();val view=NativeWorkingSession.NoteView(1.0,0.0,0.0,false,ViewOverrides())
        store.publish(store.capture(doc,null,view))
        val metadata=JSONObject(File(directory,"head.json").readText()).getJSONObject("metadata").put("displayName",JSONObject.NULL)
        val generic=WorkingSessionCache(directory,{_,_,_->})
        generic.publish(generic.reserve(Any(),WorkingSessionCache.Kind.NOTE,doc.identity.documentId,2,metadata)) {note.write(doc.snapshot(),it)}
        val before=File(directory,"head.json").readBytes()
        try {store.load();fail("JSON NULL is not an absent optional string")} catch(_:WorkingSessionCache.Invalid) {}
        try {store.publishClear(store.reserveClear());fail("must preserve bad metadata")} catch(_:WorkingSessionCache.Invalid) {}
        assertArrayEquals(before,File(directory,"head.json").readBytes())
    }
}
