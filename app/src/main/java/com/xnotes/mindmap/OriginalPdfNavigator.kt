package com.xnotes.mindmap

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.storage.NativeReferenceIssue
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.ui.Editor
import kotlinx.coroutines.*

/** One exclusive handoff for map sources and the original library; neither path saves a native owner. */
@MainThread
internal class OriginalPdfNavigator(private val editor:Editor,private val maps:MapWorkspace,
    private val networks:FileNetworkWorkspace,private val originals:OriginalPdfWorkspace,
    private val binding:Any,private val scope:CoroutineScope,
    private val captureHost:()->(()->Boolean)={ {true} },private val onStarting:()->Unit={},
    private val prepareParking:(suspend (()->Boolean)->(() -> Boolean)?)?=null,
    private val onPresented:()->Unit={}) {
    private var active=true
    private var generation=0L
    private var work:Job?=null
    private var mapOperation:Any?=null
    private var sourceOperation=false
    var busy by mutableStateOf(false);private set
    var returning by mutableStateOf(false);private set
    var failure by mutableStateOf<NativeReferenceIssue?>(null);private set
    val sourceBusy get()=busy && sourceOperation
    val sourceFailure get()=failure.takeIf {sourceOperation}
    val sourceReturning get()=returning && sourceOperation
    val ownsSourceReturn get()=originals.current?.returnTo is OriginalPdfWorkspace.Return.Map
    private fun hostCurrent()=active && originals.isHostCurrent(binding) && maps.isHostCurrent(binding) && networks.isHostCurrent(binding)

    fun openSource(entry:MapWorkspace.Entry,ticket:MapSourceNavigationTicket,bookmark:MapSourceReturnBookmark):Boolean {
        if(busy || !hostCurrent() || originals.current!=null || networks.visible || !maps.visible || maps.current!==entry ||
            !maps.canHide(entry) || entry.session.currentSourceNavigation(ticket)==null || bookmark.source!==ticket ||
            ticket.target.scope!=SourceScope.PDF_ORIGINAL) return false
        return open(ticket.target,source=true,
            valid={maps.current===entry && maps.visible && maps.canHide(entry) && entry.session.currentSourceNavigation(ticket)!=null},
            commit={read,beforeAdopt->
                // Both mutations are guarded on the same Main dispatch; no native pane is adopted.
                if(!maps.canHide(entry)) false else {
                    val nativeIdentity=if(maps.splitPresentation.layout!=null) editor.captureNativePresentationIdentity() else null
                    val shown=originals.admit(binding,read,OriginalPdfWorkspace.Return.Map(entry,bookmark,nativeIdentity),beforeAdopt)
                    if(shown==null) false else {
                        check(maps.hide(entry));entry.sourceReturn=bookmark;true
                    }
                }
            },onFailure={if(maps.visible && maps.current===entry) entry.presentation.reopenSourcePanel(entry.session,ticket)})
    }
    fun openLibrary(target:MapSourceTarget,stillCurrent:()->Boolean):Boolean {
        if(busy || !hostCurrent() || maps.visible || networks.visible || originals.current!=null ||
            target.scope!=SourceScope.PDF_ORIGINAL || !stillCurrent()) return false
        return open(target,source=false,
            valid={!maps.visible && !networks.visible && stillCurrent()},
            commit={read,beforeAdopt->originals.admit(binding,read,OriginalPdfWorkspace.Return.Library,beforeAdopt)!=null})
    }
    private fun open(target:MapSourceTarget,source:Boolean,valid:()->Boolean,
        commit:(OriginalPdfAccess.Read,()->Boolean)->Boolean,onFailure:()->Unit={}):Boolean {
        val host=captureHost();if(!host()) return false
        onStarting()
        editor.onPresentationIntent?.invoke()
        val token=++generation;val previous=originals.current
        sourceOperation=source;failure=null;returning=false;busy=true
        fun current()=hostCurrent() && generation==token && originals.current===previous && !originals.visible && valid() && host()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var read:OriginalPdfAccess.Read?=null;var committed=false
            var homeGate:com.xnotes.ui.PreparedHomeGate?=null
            try {
                if(!current()) return@launch
                val persisted=prepareParking?.invoke(::current)
                if(prepareParking!=null && persisted==null) {failure=NativeReferenceIssue.CHANGED;return@launch}
                val requestJob=currentCoroutineContext()[Job]!!
                val loaded=withContext(Dispatchers.IO) {originals.access.open(target) {requestJob.ensureActive()}.also {read=it}}
                if(!current() || !loaded.current()) {if(hostCurrent() && generation==token) failure=NativeReferenceIssue.CHANGED;return@launch}
                val proof=editor.awaitMapSourceParking()
                if(proof==null || !current() || !proof.current() || !loaded.current()) {
                    if(hostCurrent() && generation==token) failure=NativeReferenceIssue.CHANGED
                    return@launch
                }
                val provider=editor.preparePresentationDeparture
                homeGate=provider?.invoke {current() && persisted?.invoke()!=false && proof.current() && loaded.current()}
                if(provider!=null && homeGate==null) {failure=NativeReferenceIssue.CHANGED;return@launch}
                committed=commit(loaded) {homeGate?.commit {current() && persisted?.invoke()!=false && proof.current() && loaded.current()}!=false}
                if(committed) {read=null;onPresented()} else failure=NativeReferenceIssue.CHANGED
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {if(!committed && hostCurrent() && generation==token) failure=NativeReferenceIssue.UNAVAILABLE}
            finally {
                try {withContext(NonCancellable) {
                    try {homeGate?.close()} finally {withContext(Dispatchers.IO) {read?.closeAndAwait()}}
                }} finally {if(generation==token) {work=null;busy=false;if(!committed) onFailure()}}
            }
        }
        work=pending;pending.start();return true
    }
    fun resume():Boolean {
        val entry=originals.current ?: return false
        if(busy || !hostCurrent() || originals.visible || maps.visible || networks.visible || !originals.resumeRequested) return false
        return parkAndCommit(entry,source=entry.returnTo is OriginalPdfWorkspace.Return.Map,
            valid={!originals.visible && !maps.visible && !networks.visible && entry.current()}) {beforeAdopt->originals.show(binding,entry,beforeAdopt)}
    }
    fun returnToMap():Boolean {
        val shown=originals.current ?: return false
        val origin=shown.returnTo as? OriginalPdfWorkspace.Return.Map ?: return false
        val entry=origin.entry;val bookmark=origin.bookmark
        if(busy || !hostCurrent() || maps.visible || networks.visible || maps.current!==entry ||
            !entry.lease.alive || entry.sourceReturn!==bookmark) return false
        // Even a removed source must permit a safe return to its still-live map. The source panel
        // restoration itself remains subject to the existing bookmark membership check.
        val host=captureHost();if(!host()) return false
        onStarting()
        val token=++generation;val operation=Any()
        sourceOperation=true;failure=null;busy=true;returning=true;mapOperation=operation
        val identity=origin.nativeIdentity
        val sameNative=identity==null || editor.matchesNativePresentationIdentity(identity)
        val restoreSplit=identity!=null && sameNative && maps.splitPresentation.returnLayout!=null
        fun valid()=hostCurrent() && generation==token && originals.current===shown &&
            maps.current===entry && !maps.visible && networks.visible==false && entry.lease.alive && entry.sourceReturn===bookmark &&
            (!restoreSplit || editor.matchesNativePresentationIdentity(requireNotNull(identity))) && host()
        val accepted=maps.open(entry.uri,entry.title,preparePresentation={
            if(!valid()) null else {
                val persisted=prepareParking?.invoke(::valid)
                if(prepareParking!=null && persisted==null) null
                else editor.awaitMapSourceParking()?.let {proof->({valid() && persisted?.invoke()!=false && proof.current()})}
            }
        },stillCurrent=::valid,operation=operation,flushNativeOnPresent=false,onPresented={actual->
            if(validAfterMapPresentation(shown,entry,actual,token)) {
                // This return parks all native owners, including dirty NOTE panes.
                actual.skipNativePersistOnPresent=true
                if(actual.session.canRestoreSourceReturn(bookmark)) actual.presentation.applySourceReturn(actual.session,bookmark)
                if(actual.sourceReturn===bookmark) actual.sourceReturn=null
                if(!sameNative) failure=NativeReferenceIssue.CHANGED
                originals.close(binding,shown);mapOperation=null;busy=false;returning=false;onPresented()
            }
        },presentation=if(restoreSplit) MapOpenPresentation.RestoreSplit else MapOpenPresentation.Single,onFinished={presented->if(generation==token) {
            mapOperation=null;busy=false;returning=false
            if(!presented) failure=NativeReferenceIssue.CHANGED
        }})
        if(!accepted) {mapOperation=null;busy=false;returning=false}
        return accepted
    }
    private fun validAfterMapPresentation(shown:OriginalPdfWorkspace.Entry,expected:MapWorkspace.Entry,
        actual:MapWorkspace.Entry,token:Long)=hostCurrent() && generation==token && originals.current===shown &&
        actual===expected && maps.current===expected && maps.visible

    fun closeLibrary():Boolean {
        val entry=originals.current ?: return false
        if(busy || !hostCurrent() || entry.returnTo!==OriginalPdfWorkspace.Return.Library || maps.visible || networks.visible) return false
        return parkAndCommit(entry,source=false,valid={!maps.visible && !networks.visible}) {beforeAdopt->
            originals.close(binding,entry,beforeAdopt)
        }
    }
    fun close():Boolean=when(originals.current?.returnTo) {
        is OriginalPdfWorkspace.Return.Map->returnToMap()
        OriginalPdfWorkspace.Return.Library->closeLibrary()
        null->false
    }
    private fun parkAndCommit(entry:OriginalPdfWorkspace.Entry,source:Boolean,valid:()->Boolean,commit:(()->Boolean)->Boolean):Boolean {
        val host=captureHost();if(!host()) return false
        onStarting()
        editor.onPresentationIntent?.invoke()
        val token=++generation;sourceOperation=source;failure=null;busy=true;returning=true
        fun current()=hostCurrent() && generation==token && originals.current===entry && valid() && host()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var homeGate:com.xnotes.ui.PreparedHomeGate?=null
            try {
                val persisted=prepareParking?.invoke(::current)
                if(prepareParking!=null && persisted==null) {failure=NativeReferenceIssue.CHANGED;return@launch}
                val proof=if(current()) editor.awaitMapSourceParking() else null
                if(proof==null || !current() || !proof.current()) {if(hostCurrent() && generation==token) failure=NativeReferenceIssue.CHANGED;return@launch}
                val provider=editor.preparePresentationDeparture
                homeGate=provider?.invoke {current() && persisted?.invoke()!=false && proof.current()}
                if(provider!=null && homeGate==null) {failure=NativeReferenceIssue.CHANGED;return@launch}
                if(commit {homeGate?.commit {current() && persisted?.invoke()!=false && proof.current()}!=false}) onPresented()
                else if(hostCurrent() && generation==token) failure=NativeReferenceIssue.CHANGED
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {if(hostCurrent() && generation==token) failure=NativeReferenceIssue.UNAVAILABLE}
            finally {try {homeGate?.close()} finally {if(generation==token) {work=null;busy=false;returning=false}}}
        }
        work=pending;pending.start();return true
    }
    fun clearFailure() {failure=null}
    fun cancelSource() {if(sourceOperation) cancel()}
    fun cancel() {
        generation++;work?.cancel();work=null
        mapOperation?.let {maps.cancelOpen(it)};mapOperation=null
        busy=false;returning=false;failure=null
    }
    fun dispose() {cancel();active=false}
}
