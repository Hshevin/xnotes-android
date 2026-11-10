package com.xnotes.pen

import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.provider.DocumentsContract
import android.widget.FrameLayout
import com.xnotes.MainActivity
import com.xnotes.MixedInputBinding
import com.xnotes.canvas.StylusButtonLatch
import com.xnotes.core.tools.Tool
import com.xnotes.ui.Editor
import com.xnotes.ui.Pane
import com.xnotes.ui.NativeSplitSource
import com.xnotes.ui.NativeSplitOpenLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Main key dispatch and actual prepared native commits, including a Map-focused release. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MainPairKeyOwnerHostTest {
    private class KeyView(activity:MainActivity):View(activity) {
        val keys=mutableListOf<KeyEvent>()
        init {isFocusable=true;isFocusableInTouchMode=true}
        override fun dispatchKeyEvent(event:KeyEvent):Boolean {keys+=event;return true}
    }
    private fun key(action:Int,device:Int=9,repeat:Int=0,code:Int=600)=
        KeyEvent(1000L,1001L+action,action,code,repeat,0,device,81,0,InputDevice.SOURCE_STYLUS)
    private fun mapFocus(activity:MainActivity) {
        activity.mixedInput=MixedInputBinding(MixedInputRouter(),native={null},focus={true},blocked={false})
    }
    private fun scenario(block:suspend (MainActivity,EditorPageCopyFixture,KeyView)->Unit)=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        var activity:MainActivity?=null
        try {withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");f.stopDebounce()
            val host=Robolectric.buildActivity(MainActivity::class.java).get();activity=host
            host.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            val child=KeyView(host)
            host.window.setContentView(child,ViewGroup.LayoutParams(-1,-1))
            host.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY))
            host.window.decorView.layout(0,0,1000,1000);child.requestFocus()
            MainActivity::class.java.getDeclaredField("editor").apply {isAccessible=true}.set(host,f.editor)
            StylusButtonLatch.releaseKey();block(host,f,child)
        }} finally {withContext(NonCancellable+Dispatchers.Main) {
            activity?.let {host->host.mixedInput=null;host.penKeyRecorder=null
                MainActivity::class.java.getDeclaredField("editor").apply {isAccessible=true}.set(host,null)
                host.window.setContentView(View(host))}
            f.editor.openPanes.forEach {it.resetPenButtons()};StylusButtonLatch.releaseKey()
        }}
    }
    private fun bind(editor:Editor):List<PenShortcut> {
        val old=editor.preferences.penShortcuts
        assertTrue(editor.setPenShortcuts(listOf(PenShortcut("Hold",listOf(requireNotNull(PenSignals.key(key(KeyEvent.ACTION_DOWN)))),
            PenBinding("eraser",PenBindingMode.HOLD)))))
        return old
    }

    @Test fun freshModelAndRepeatCannotRebindAnAlreadyAcceptedPhysicalPress()=scenario {activity,f,child->
        val old=bind(f.editor)
        try {
            f.editor.selectTool(Tool.PEN)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            val model=f.editor.state.document;val token=f.editor.penInputOwnerToken
            f.editor.newNote()
            withTimeout(10000) {while(f.editor.state.document===model) delay(1)}
            assertNotSame(token,f.editor.penInputOwnerToken)
            f.editor.selectTool(Tool.PAN)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN,repeat=1)))
            assertEquals(Tool.PAN,f.editor.tool)
            // A current press with the same persistent trigger must survive the old generation's UP.
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN,device=98)))
            assertEquals(Tool.ERASER,f.editor.tool);assertEquals(Tool.PAN,f.editor.toolbarTool)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
            assertEquals(Tool.ERASER,f.editor.tool)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP,device=98)))
            assertEquals(Tool.PAN,f.editor.tool);assertTrue(child.keys.isEmpty())
        } finally {f.editor.setPenShortcuts(old)}
    }

    @Test fun actualSharedPeerCommitHandsItsAcceptedReleaseToTheRetainedOwner()=scenario {activity,f,child->
        val peer=f.editor.secondaryPane();assertTrue(peer.openSharedView(f.editor))
        val old=bind(f.editor)
        try {
            f.editor.selectTool(Tool.PEN);f.editor.focusPane(Pane.SECONDARY)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)));assertEquals(Tool.ERASER,peer.tool)
            val model=f.editor.state.document;val history=f.editor.history
            val cap=f.editor.preparePagedPairOpen(f.sourceUri,"source.xnote",Pane.SECONDARY,
                guardOwners={owners,current->assertTrue(current());assertTrue(owners.isEmpty());emptySet()})
            assertNotNull(cap)
            try {
                assertSame(f.editor,cap!!.target);assertTrue(cap.keyHandoffs.any {it.first===peer && it.second===f.editor})
                val handoff=activity.prepareNativeKeyHandoffs(cap.keyHandoffs)
                assertTrue(cap.commit());handoff();f.editor.focusMixedNativePane(cap.nativePane)
                assertFalse(peer.noteOpen);assertSame(model,f.editor.state.document);assertSame(history,f.editor.history)
                assertEquals(Tool.ERASER,f.editor.tool);assertEquals(Tool.PEN,f.editor.toolbarTool)
                mapFocus(activity)
                assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
                assertEquals(Tool.PEN,f.editor.tool);assertTrue(child.keys.isEmpty())
            } finally {cap?.close()}
        } finally {f.editor.setPenShortcuts(old)}
    }

    @Test fun retainedOtherDocumentKeepsItsFormalToolAndReceivesTheRetiredOwnersRelease()=scenario {activity,f,child->
        val target=openParkingPanes(f);val old=bind(f.editor)
        try {
            f.editor.focusPane(Pane.PRIMARY);f.editor.selectTool(Tool.PEN);target.selectTool(Tool.PAN)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            val token=target.penInputOwnerToken
            val cap=f.editor.preparePagedPairOpen(f.targetUri,"target.xnote",Pane.PRIMARY,
                guardOwners={owners,current->assertTrue(current());assertEquals(listOf(f.editor),owners);emptySet()})
            assertNotNull(cap)
            try {
                assertSame(target,cap!!.target);assertTrue(cap.keyHandoffs.any {it.first===f.editor && it.second===target})
                val handoff=activity.prepareNativeKeyHandoffs(cap.keyHandoffs)
                assertTrue(cap.commit());handoff();f.editor.focusMixedNativePane(cap.nativePane)
                assertSame(token,target.penInputOwnerToken);assertFalse(f.editor.noteOpen)
                assertEquals(Tool.PAN,target.toolbarTool);assertEquals(Tool.ERASER,target.tool)
                mapFocus(activity)
                assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
                assertEquals(Tool.PAN,target.tool);assertEquals(Tool.PAN,target.toolbarTool);assertTrue(child.keys.isEmpty())
            } finally {cap?.close()}
        } finally {f.editor.setPenShortcuts(old)}
    }

    @Test fun nativeOnlyInheritedRecipientStillReleasesWhenTheOriginalEditorChangesModel()=scenario {activity,f,child->
        val target=openParkingPanes(f);val old=bind(f.editor)
        try {
            f.editor.focusPane(Pane.PRIMARY);f.editor.selectTool(Tool.PEN)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            f.editor.focusPane(Pane.SECONDARY)
            assertEquals(Tool.ERASER,target.tool);assertEquals(Tool.PEN,target.toolbarTool)
            val previous=f.editor.state.document
            f.editor.newNote()
            withTimeout(10000) {while(f.editor.state.document===previous) delay(1)}
            f.editor.selectTool(Tool.PAN)
            assertEquals(Tool.ERASER,target.tool)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
            assertEquals(Tool.PEN,target.tool);assertEquals(Tool.PAN,f.editor.tool)
            assertTrue(child.keys.isEmpty())
        } finally {f.editor.setPenShortcuts(old)}
    }

    @Test fun aNewNonRepeatingDownInTheReplacementModelOwnsItsOwnRelease()=scenario {activity,f,child->
        val old=bind(f.editor)
        try {
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            val previous=f.editor.state.document
            f.editor.newNote()
            withTimeout(10000) {while(f.editor.state.document===previous) delay(1)}
            f.editor.selectTool(Tool.PAN)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            assertEquals(Tool.ERASER,f.editor.tool);assertEquals(Tool.PAN,f.editor.toolbarTool)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
            assertEquals(Tool.PAN,f.editor.tool);assertTrue(child.keys.isEmpty())
        } finally {f.editor.setPenShortcuts(old)}
    }

    @Test fun aLegacyPressStillClearsItsLatchAfterTheSameKeyGetsARecordedMapping()=scenario {activity,f,child->
        val old=f.editor.preferences.penShortcuts
        val down=key(KeyEvent.ACTION_DOWN,code=KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY)
        try {
            assertTrue(f.editor.setPenShortcuts(emptyList()))
            assertTrue(activity.dispatchKeyEvent(down));assertTrue(StylusButtonLatch().heldWith(0))
            assertTrue(f.editor.setPenShortcuts(listOf(PenShortcut("New mapping",listOf(requireNotNull(PenSignals.key(down))),
                PenBinding("eraser",PenBindingMode.HOLD)))))
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP,code=KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY)))
            assertFalse(StylusButtonLatch().heldWith(0));assertTrue(child.keys.isEmpty())
        } finally {f.editor.setPenShortcuts(old)}
    }

    @Test fun aNativeSplitCommitKeepsTheAcceptedHoldOnItsActualRetainedOwner()=scenario {activity,f,child->
        withContext(Dispatchers.IO) {f.seed(f.retry)}
        val uri=DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
        val old=bind(f.editor)
        try {
            f.editor.selectTool(Tool.PEN)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            val model=f.editor.state.document;val history=f.editor.history;val token=f.editor.penInputOwnerToken
            var guarded=false
            val cap=f.editor.prepareNativePairOpen(NativeSplitSource(f.sourceUri,"source.xnote"),NativeSplitSource(uri,"retry.xnote"),
                guardOwners={owners,current->assertTrue(current());assertTrue(owners.isEmpty());guarded=true;emptySet()})
            assertNotNull(cap)
            try {
                assertTrue(guarded)
                val handoff=activity.prepareNativeKeyHandoffs(cap!!.keyHandoffs)
                assertTrue(cap.commit());handoff()
                assertSame(f.editor,cap.first);assertTrue(cap.second.noteOpen);assertSame(model,f.editor.state.document)
                assertSame(history,f.editor.history);assertSame(token,f.editor.penInputOwnerToken)
                assertEquals(Tool.ERASER,f.editor.tool);assertEquals(Tool.PEN,f.editor.toolbarTool)
                assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
                assertEquals(Tool.PEN,f.editor.tool);assertTrue(child.keys.isEmpty())
            } finally {cap?.close()}
        } finally {f.editor.setPenShortcuts(old)}
    }

    @Test fun aFailedSecondNativeReadDoesNotRetireAnAcceptedPress()=scenario {activity,f,child->
        withContext(Dispatchers.IO) {f.retry.writeText("invalid NOTE")}
        val uri=DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
        val old=bind(f.editor)
        try {
            f.editor.selectTool(Tool.PEN)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
            val model=f.editor.state.document;val token=f.editor.penInputOwnerToken
            var guarded=false
            assertNull(f.editor.prepareNativePairOpen(NativeSplitSource(f.sourceUri,"source.xnote"),NativeSplitSource(uri,"retry.xnote"),
                guardOwners={_,_->guarded=true;emptySet()}))
            assertFalse(guarded);assertSame(model,f.editor.state.document);assertSame(token,f.editor.penInputOwnerToken)
            assertEquals(Tool.ERASER,f.editor.tool)
            assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
            assertEquals(Tool.PEN,f.editor.tool);assertTrue(child.keys.isEmpty())
        } finally {f.editor.setPenShortcuts(old)}
    }

    @Test fun aFocusedSharedNativePeerInheritsTheOwnersAcceptedHoldWithoutAnotherDown() {
        for(keepPeer in listOf(false,true)) scenario {activity,f,child->
            val peer=f.editor.secondaryPane()
            if(keepPeer) assertTrue(peer.openSharedView(f.editor))
            val old=bind(f.editor)
            val holder=FrameLayout(activity)
            activity.window.setContentView(holder,ViewGroup.LayoutParams(-1,-1))
            holder.addView(child,FrameLayout.LayoutParams(1,1))
            holder.addView(f.editor.surfacesForAttach(),FrameLayout.LayoutParams(500,1000))
            if(keepPeer) holder.addView(peer.surfacesForAttach(),FrameLayout.LayoutParams(500,1000).apply {leftMargin=500})
            fun layoutWindow() {
                activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY))
                activity.window.decorView.layout(0,0,1000,1000)
            }
            try {
                f.editor.selectTool(Tool.PEN);peer.selectTool(Tool.PAN)
                f.editor.view.isFocusableInTouchMode=true;layoutWindow()
                assertTrue(f.editor.view.requestFocus());assertTrue(f.editor.view.hasFocus())
                assertSame(f.editor,f.editor.active)
                assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN)))
                assertEquals(Tool.ERASER,f.editor.tool);assertEquals(Tool.PAN,peer.tool)
                val model=f.editor.state.document;val history=f.editor.history
                val cap=f.editor.prepareNativePairOpen(NativeSplitSource(f.sourceUri,"source.xnote"),
                    NativeSplitSource(f.sourceUri,"source.xnote"),NativeSplitOpenLayout(focused=Pane.SECONDARY),
                    guardOwners={owners,current->assertTrue(current());assertTrue(owners.isEmpty());emptySet()})
                assertNotNull(cap)
                try {
                    assertSame(f.editor,cap!!.first);assertSame(peer,cap.second)
                    val handoff=activity.prepareNativeKeyHandoffs(cap.keyHandoffs)
                    assertTrue(cap.commit());handoff()
                    if(!keepPeer) holder.addView(peer.surfacesForAttach(),FrameLayout.LayoutParams(500,1000).apply {leftMargin=500})
                    peer.view.isFocusableInTouchMode=true;layoutWindow()
                    assertTrue(peer.view.requestFocus());assertTrue(peer.view.hasFocus())
                    assertSame(peer,f.editor.active);assertSame(f.editor,peer.documentOwner)
                    assertSame(model,peer.state.document);assertSame(history,f.editor.history)
                    assertEquals("shared peer inherits HOLD (keepPeer=$keepPeer)",Tool.ERASER,peer.tool)
                    assertEquals(Tool.PAN,peer.toolbarTool);assertEquals(Tool.PEN,f.editor.toolbarTool)
                    assertTrue(activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP)))
                    assertEquals(Tool.PEN,f.editor.tool);assertEquals(Tool.PAN,peer.tool)
                    assertEquals(Tool.PEN,f.editor.toolbarTool);assertEquals(Tool.PAN,peer.toolbarTool)
                    assertTrue(child.keys.isEmpty())
                } finally {cap?.close()}
            } finally {holder.removeAllViews();f.editor.setPenShortcuts(old)}
        }
    }
}
