package com.xnotes.pen

import android.net.Uri
import android.provider.DocumentsContract
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.Tool
import com.xnotes.ui.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The actual explicit saved-split entry must prepare both files before changing live owners. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeSplitReplacementHostTest {
    @Test fun retiredMapFilesCanBeSharedButCannotReplaceTheOpenNote()=runParkingScenario {f->
        f.seed();f.seed(f.target)
        val split=mixedSplitJson().toString().toByteArray()
        val recent=File(f.context.filesDir,"saved-split.json").apply {writeBytes(split)}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            val original=f.editor.state.document
            f.targetDisplayName="retired.xmap"
            val mapBytes=f.target.readBytes()
            assertFalse(f.editor.openSingleFromHome(f.targetUri,f.targetDisplayName))
            val unsupported=f.context.getString(com.xnotes.R.string.legacy_map_unsupported)
            assertEquals(unsupported,f.editor.message)
            f.editor.message=null;f.editor.openAsync(f.targetUri)
            assertEquals(unsupported,f.editor.message);assertSame(original,f.editor.state.document)
            fun checkShared(expected:ByteArray) {
                val out=java.io.ByteArrayOutputStream();f.editor.copyFileTo(f.targetUri,out)
                assertArrayEquals(expected,out.toByteArray());assertArrayEquals(expected,f.target.readBytes())
            }
            checkShared(mapBytes)
            f.targetDisplayName="retired.xsplit";f.target.writeBytes(split)
            f.editor.message=null;f.editor.openAsync(f.targetUri)
            assertEquals(unsupported,f.editor.message);assertSame(original,f.editor.state.document)
            for(location in listOf(f.targetUri,null)) {
                f.editor.message=null
                assertFalse(f.editor.openSplitFile(location));assertEquals(unsupported,f.editor.message)
                assertSame(original,f.editor.state.document);assertFalse(f.editor.inSplit)
            }
            checkShared(split)
        }
        assertArrayEquals(split,recent.readBytes());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }

    @Test fun explorerContentUrisAndRecentArrangementsOpenBothNativeVersions() {
        for(version in 1..2) for(recent in listOf(false,true)) runParkingScenario {f->
            f.seed();f.seed(f.target)
            val bytes=JSONObject().put("format","xnotes-split").put("version",version)
                .put("primaryPath",f.sourceUri).put("secondPath",f.targetUri).toString().toByteArray()
            val saved=if(recent) File(f.context.filesDir,"saved-split.json") else f.retry
            saved.writeBytes(bytes)
            val location=if(recent) null else DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
            withContext(Dispatchers.Main) {
                assertTrue(f.editor.openSplitFile(location));assertTrue(f.editor.inSplit)
                assertEquals(f.sourceUri,f.editor.visibleDocumentUri);assertEquals(f.targetUri,f.editor.secondary?.visibleDocumentUri)
            }
            if(!recent) assertArrayEquals(bytes,saved.readBytes())
        }
    }

    @Test fun aBadSecondFileLeavesTheOriginalNativeModelHistoryAndTextDraftUntouched()=runParkingScenario {f->
        withContext(Dispatchers.IO) {
            f.seed();f.seed(f.target);f.retry.writeText("Deliberately not an XNotes bundle")
        }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");f.stopDebounce()
            assertTrue(f.editor.noteOpen);assertEquals(f.sourceUri,f.editor.visibleDocumentUri)
            f.editor.view.layout(0,0,1000,1000);f.editor.selectTool(Tool.TEXT_BOX)
            parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(100.0,100.0))
            f.editor.typeInOpenBox("Old source text remains an unconfirmed draft")
            f.stopDebounce();assertNotNull(f.editor.controller.activeTextItem())
        }
        val original=withContext(Dispatchers.Main) {f.editor.state.document}
        val history=withContext(Dispatchers.Main) {f.editor.history}
        val undo=withContext(Dispatchers.Main) {history.nextUndo}
        val draft=withContext(Dispatchers.Main) {requireNotNull(f.editor.controller.activeTextItem())}
        val revision=withContext(Dispatchers.Main) {original.bodyRevision}
        val sourceBytes=f.source.readBytes();val firstBytes=f.target.readBytes();val secondBytes=f.retry.readBytes()
        val retryUri=DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
        val arrangement=withContext(Dispatchers.IO) {
            File(File(f.context.filesDir,"splits").apply {mkdirs()},"bad-second.xsplit").apply {
                writeText(JSONObject().put("format","xnotes-split").put("version",2)
                    .put("primaryPath",f.targetUri).put("secondPath",retryUri)
                    .put("ratio",.37).put("reversed",true).put("sideBySide",false).toString())
            }
        }
        val arrangementBytes=arrangement.readBytes()
        val opened=withContext(Dispatchers.Main) {f.editor.openSplitFile(Uri.fromFile(arrangement).toString())}
        withContext(Dispatchers.Main) {
            assertFalse("An explicitly opened split with an invalid second file is a failed open",opened)
            assertTrue(f.editor.noteOpen);assertSame(original,f.editor.state.document)
            assertEquals(f.sourceUri,f.editor.visibleDocumentUri);assertSame(history,f.editor.history);assertSame(undo,history.nextUndo)
            assertEquals(revision,original.bodyRevision);assertSame(draft,f.editor.controller.activeTextItem())
            assertEquals("Old source text remains an unconfirmed draft",draft.text)
            assertNull(f.editor.missingDocument);assertFalse(f.editor.inSplit)
        }
        assertArrayEquals("The failed initial reads do not save or blur the old draft",sourceBytes,f.source.readBytes())
        assertArrayEquals(firstBytes,f.target.readBytes());assertArrayEquals(secondBytes,f.retry.readBytes())
        assertArrayEquals(arrangementBytes,arrangement.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

}
