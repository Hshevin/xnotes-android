package com.xnotes.pen

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorSplitSaveReviewTest {
    @Suppress("UNCHECKED_CAST")
    private fun noteMask(editor:com.xnotes.ui.Editor,value:Boolean) {
        val field=com.xnotes.ui.Editor::class.java.getDeclaredField("savingNote\$delegate").apply {isAccessible=true}
        (field.get(editor) as androidx.compose.runtime.MutableState<Boolean>).value=value
    }
    @Test fun savingAnArrangementDoesNotUseOrClearTheNoteSaveMask()=runParkingScenario {f->
        openParkingPanes(f)
        withContext(Dispatchers.Main) {
            noteMask(f.editor,true)
            try {
                assertTrue("Independent split saving must not be gated by a note overlay",f.editor.saveCurrentSplit())
                assertTrue("The split operation does not own the note save mask",f.editor.savingNote)
                assertEquals(1,f.editor.storedSplitFiles().size)
            } finally {noteMask(f.editor,false)}
        }
    }

    @Test fun aSecondArrangementSaveIsRejectedWhileTheFirstIsWaitingForItsDocumentWrite()=runParkingScenario {f->
        val other=openParkingPanes(f)
        val entered=CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {other.state.document.dirty=true}
        f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
        coroutineScope {
            val first=async(Dispatchers.Main) {f.editor.saveCurrentSplit()}
            try {
                entered.await()
                withContext(Dispatchers.Main) {
                    val second=async(start=CoroutineStart.UNDISPATCHED) {f.editor.saveCurrentSplit()}
                    try {
                        assertTrue("A repeated command must finish immediately instead of joining another split save",second.isCompleted)
                        assertFalse(second.await())
                    } finally {second.cancel()}
                }
            } finally {f.release.countDown()}
            assertTrue(first.await())
            assertEquals(1,f.editor.storedSplitFiles().size)
        }
    }
}
