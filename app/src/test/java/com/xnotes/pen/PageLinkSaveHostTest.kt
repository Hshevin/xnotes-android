package com.xnotes.pen

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.map.source.PageIdEnableResult
import com.xnotes.ui.Editor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PageLinkSaveHostTest {
    @Test fun activationFlushesLaterInkAgainstTheVersionActuallyWrittenByAnOrdinarySave()=runParkingScenario {f->
        val model=f.openLegacyNote()
        val started=CompletableDeferred<Unit>();val saved=CompletableDeferred<Boolean>()
        f.beforeTargetWrite={f.beforeTargetWrite=null;started.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        withContext(Dispatchers.Main) {
            f.addStroke(110.0,recordHistory=true);f.stopDebounce()
            f.editor.saveToThen(f.targetUri) {ok->f.stopDebounce();saved.complete(ok)}
            try {started.await();f.addStroke(990.0,recordHistory=true);f.stopDebounce()} finally {f.release.countDown()}
            assertTrue(saved.await());assertTrue(model.dirty)
            val firstSave=f.target.inputStream().use {f.codec.read(it)}
            assertEquals(1,firstSave.pages.first().items.filterIsInstance<Stroke>().size)
            assertEquals(PageIdEnableResult.ENABLED,f.editor.enablePageIds())
            val activated=f.target.inputStream().use {f.codec.read(it)}
            assertTrue(activated.hasReferenceAnchors);assertEquals(model.identity.documentId,activated.identity.documentId)
            assertEquals(listOf(110.0,990.0),activated.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
        }
    }

    @Test fun cancellingActivationDuringItsAcceptedSaveDoesNotStrandLaterPenInput()=runParkingScenario(advanceClock=true) {f->
        f.openLegacyNote()
        val started=CompletableDeferred<Unit>();val laterSaved=CompletableDeferred<Unit>()
        f.beforeTargetWrite={f.beforeTargetWrite=null;started.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        f.afterTargetWrite={if(f.writes.get()==2) laterSaved.complete(Unit)}
        withContext(Dispatchers.Main) {
            Editor::class.java.getDeclaredField("ownAutosaveUri").apply {isAccessible=true}.set(f.editor,f.targetUri)
            f.addStroke(110.0,recordHistory=true);f.stopDebounce()
            val enabling=async {f.editor.enablePageIds()}
            try {
                started.await();enabling.cancel()
                f.editor.selectTool(Tool.PEN)
                parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(240.0,240.0))
                parkingPen(f.editor,MotionEvent.ACTION_MOVE,Pt(280.0,260.0))
                parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(310.0,270.0))
                assertEquals(2,f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().size)
            } finally {f.release.countDown()}
            enabling.join()
        }
        withTimeout(5000L) {laterSaved.await()}
        val actual=f.target.inputStream().use {f.codec.read(it)}
        assertFalse(actual.hasReferenceAnchors);assertEquals(2,actual.pages.first().items.filterIsInstance<Stroke>().size)
    }
}
