package com.xnotes.mindmap

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.storage.NativeReferenceIssue
import com.xnotes.storage.NativeReferenceTarget
import com.xnotes.core.util.DocumentKind
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferencePreparation
import com.xnotes.ui.NativeReferenceRequest
import com.xnotes.ui.NativeReferenceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal sealed interface MapSourceGuardResult {
    data class Ready(val discarded:Set<Editor.OpenDiscard>):MapSourceGuardResult
    data object Cancelled:MapSourceGuardResult
    data object Failed:MapSourceGuardResult
    data object Changed:MapSourceGuardResult
}

/** Composition-scoped handoff. Only the pure return bookmark is retained by MapWorkspace. */
@MainThread
internal class MapSourceNavigator(
    private val editor:Editor,
    private val workspace:MapWorkspace,
    private val binding:Any,
    private val scope:CoroutineScope,
    private val guard:suspend (NativeReferenceRequest,()->Boolean)->MapSourceGuardResult,
    private val onOpened:(NativeReferenceResult.Opened,Boolean)->Unit={_,_->},
) {
    private var active=true
    private var generation=0L
    private var job:Job?=null
    private var request:NativeReferenceRequest?=null
    private var pendingEntry:MapWorkspace.Entry?=null
    private var pendingTicket:MapSourceNavigationTicket?=null
    private var returnOperation:Any?=null
    var busy by mutableStateOf(false);private set
    var returning by mutableStateOf(false);private set
    var failure by mutableStateOf<NativeReferenceIssue?>(null);private set
    var saveFailed by mutableStateOf(false);private set

    private fun current(entry:MapWorkspace.Entry,token:Long)=active && generation==token &&
        workspace.isHostCurrent(binding) && workspace.current===entry && entry.lease.alive

    fun open(entry:MapWorkspace.Entry,ticket:MapSourceNavigationTicket,bookmark:MapSourceReturnBookmark):Boolean {
        if(busy || !active || !workspace.visible || workspace.current!==entry || !workspace.isHostCurrent(binding) ||
            !workspace.canHide(entry) || entry.session.currentSourceNavigation(ticket)==null || bookmark.source!==ticket) return false
        val token=++generation
        entry.sourceReturn=null;failure=null;saveFailed=false;returning=false;busy=true
        pendingEntry=entry;pendingTicket=ticket
        fun valid()=current(entry,token) && workspace.visible && workspace.canHide(entry) &&
            entry.session.currentSourceNavigation(ticket)!=null
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var committed=false
            try {
                val target=ticket.target
                when(val prepared=editor.prepareReference(NativeReferenceTarget(target.documentId,target.pageId,
                    requiredKind=if(target.scope==SourceScope.CANVAS_REGION) DocumentKind.CANVAS else null),target.region)) {
                    is NativeReferencePreparation.Failed->if(current(entry,token)) failure=prepared.issue
                    is NativeReferencePreparation.Ready->{
                        val native=prepared.request;request=native
                        if(!valid() || !native.current()) {if(current(entry,token)) failure=NativeReferenceIssue.CHANGED;return@launch}
                        val decision=guard(native,::valid)
                        if(!current(entry,token)) return@launch
                        when(decision) {
                            MapSourceGuardResult.Cancelled->Unit
                            MapSourceGuardResult.Failed->{saveFailed=true;failure=NativeReferenceIssue.UNAVAILABLE}
                            MapSourceGuardResult.Changed->failure=NativeReferenceIssue.CHANGED
                            is MapSourceGuardResult.Ready->{
                                if(!valid() || !native.current()) {if(current(entry,token)) failure=NativeReferenceIssue.CHANGED;return@launch}
                                val result=editor.openReference(native,decision.discarded,::valid) {opened->
                                    // Core checked valid immediately before adoption, with no suspension
                                    // before this callback. The map therefore has no new draft/pen to drop.
                                    committed=true
                                    entry.sourceReturn=bookmark
                                    entry.skipNativePersistOnPresent=opened.pane.canvasOpen
                                    workspace.hide(entry)
                                    onOpened(opened,native.keptOwner!=null)
                                }
                                if(!committed && current(entry,token) && result is NativeReferenceResult.Failed) failure=result.issue
                            }
                        }
                    }
                }
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {if(!committed && current(entry,token)) failure=NativeReferenceIssue.UNAVAILABLE}
            finally {
                if(generation==token) {
                    if(!committed) {
                        request?.cancel()
                        if(current(entry,token) && workspace.visible) entry.presentation.reopenSourcePanel(entry.session,ticket)
                    }
                    request=null;job=null;pendingEntry=null;pendingTicket=null;busy=false
                }
            }
        }
        job=pending;pending.start();return true
    }

    /** A fresh native parking proof, followed by the actual same-entry presentation callback. */
    fun returnToMap():Boolean {
        val entry=workspace.current ?: return false
        val bookmark=entry.sourceReturn ?: return false
        if(busy || !active || workspace.visible || !workspace.isHostCurrent(binding) || !entry.lease.alive) return false
        if(!entry.session.canRestoreSourceReturn(bookmark)) {
            if(!entry.session.canRestoreSourceReturn(bookmark,requireReady=false)) entry.sourceReturn=null
            failure=NativeReferenceIssue.CHANGED;return false
        }
        val token=++generation;val operation=Any()
        val skipNativePersist=entry.skipNativePersistOnPresent
        failure=null;saveFailed=false;busy=true;returning=true;returnOperation=operation
        fun valid()=current(entry,token) && entry.sourceReturn===bookmark && entry.session.canRestoreSourceReturn(bookmark)
        val accepted=workspace.open(entry.uri,entry.title,preparePresentation={
            if(!valid()) null else editor.awaitHostParking()?.let {proof->({valid() && proof.current()})}
        },stillCurrent={valid() && !workspace.visible},operation=operation,flushNativeOnPresent=entry.flushNativeOnPresent,onPresented={shown->
            if(current(entry,token) && shown===entry && entry.sourceReturn===bookmark) {
                entry.skipNativePersistOnPresent=skipNativePersist
                if(entry.presentation.applySourceReturn(entry.session,bookmark)) entry.sourceReturn=null
                returnOperation=null;busy=false;returning=false
            }
        },onFinished={shown->
            if(current(entry,token)) {
                returnOperation=null;busy=false;returning=false
                if(!shown) {
                    if(entry.sourceReturn===bookmark && !entry.session.canRestoreSourceReturn(bookmark,requireReady=false)) entry.sourceReturn=null
                    failure=NativeReferenceIssue.CHANGED
                }
            }
        })
        if(!accepted) {returnOperation=null;busy=false;returning=false}
        return accepted
    }

    fun clearFailure() {failure=null;saveFailed=false}

    fun cancel() {
        val entry=pendingEntry;val ticket=pendingTicket
        val showPanel=entry!=null && ticket!=null && workspace.isHostCurrent(binding) && workspace.current===entry && workspace.visible
        generation++;job?.cancel();job=null;request?.cancel();request=null
        returnOperation?.let {workspace.cancelOpen(it)};returnOperation=null
        pendingEntry=null;pendingTicket=null;busy=false;returning=false;failure=null;saveFailed=false
        if(showPanel) entry!!.presentation.reopenSourcePanel(entry.session,ticket!!)
    }
    /** Ordinary View map/open/close must not consume the old explicit-return selection. */
    fun retire() {cancel();workspace.current?.sourceReturn=null}
    fun dispose() {cancel();active=false}
}
