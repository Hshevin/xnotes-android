package com.xnotes

import com.xnotes.pen.openParkingPanes
import com.xnotes.pen.runParkingScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real guard model proofs, including callbacks from dialogs that have already retired. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeGuardHandoffHostTest {
    @Test fun aNewPendingPromptReplacesAndNotifiesTheOldRequestWithoutRunningIt()=runParkingScenario {f->
        val second=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            val slot=NativeGuardState();val stops=mutableListOf<NativeActionStop>();var oldActions=0;var nextActions=0
            val old=GuardRequest(f.editor,{oldActions++},{},stops::add,null,{true})
            val next=GuardRequest(second,{nextActions++},{},{},null,{true})
            slot.current=old;slot.current=next
            assertEquals(listOf(NativeActionStop.CHANGED),stops)
            assertFalse(old.current());assertFalse(slot.consume(old));assertSame(next,slot.current)
            old.proceed();assertEquals(0,oldActions)
            assertTrue(slot.consume(next));next.proceed();assertEquals(1,nextActions)
        }
    }
    @Test fun everyRetiredDialogCallbackLeavesTheNewPromptAndItsCompletionReachable()=runParkingScenario {f->
        val second=openParkingPanes(f)
        withContext(Dispatchers.Main) {
            val slot=NativeGuardState();val stops=mutableListOf<NativeActionStop>();var firstActions=0;var secondActions=0
            val old=GuardRequest(f.editor,{firstActions++},{},stops::add,Any(),{true})
            slot.current=old;assertTrue(slot.consume(old));old.stop(NativeActionStop.CANCELLED)
            val next=GuardRequest(second,{secondActions++},{},stops::add,Any(),{true})
            slot.current=next
            // Dismiss, Save, Discard and Cancel all use the same actual slot consumption.
            repeat(4) {assertFalse(slot.consume(old));assertSame(next,slot.current);assertTrue(next.current())}
            assertEquals(listOf(NativeActionStop.CANCELLED),stops);assertEquals(0,firstActions)
            assertTrue(slot.consume(next));assertTrue(next.discard.applies());next.proceed()
            assertNull(slot.current);assertEquals(1,secondActions)
            next.stop(NativeActionStop.FAILED);next.proceed();assertEquals(1,secondActions)
        }
    }

    @Test fun pickerCancelAndFailureHaveOneTerminalOutcomeAndCannotInvokeASavedContinuationLater()=runParkingScenario {f->
        openParkingPanes(f)
        withContext(Dispatchers.Main) {
            for(reason in NativeActionStop.entries) {
                val stops=mutableListOf<NativeActionStop>();var saved=0
                val request=SavePickerRequest(f.editor.discardForOpen(),{saved++},stops::add,{true})
                assertTrue(request.current());request.stop(reason);request.saved();request.stop(NativeActionStop.FAILED)
                assertFalse(request.current());assertEquals(listOf(reason),stops);assertEquals(0,saved)
            }
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get())
    }
}
