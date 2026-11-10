package com.xnotes.mindmap

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.ui.Editor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Present a real editable file-card edge through the canonical actor and native parking barrier. */
@MainThread
internal class MapFileRelationNavigator(
    private val editor:Editor,
    private val workspace:MapWorkspace,
    private val binding:Any,
    private val scope:CoroutineScope,
    private val captureHost:()->(()->Boolean)={ {true} },
    private val onStarting:()->Unit={},
    private val onPresented:(MapWorkspace.Entry)->Unit={},
) {
    private val app=editor.view.context.applicationContext
    private var active=true
    private var generation=0L
    private var job:Job?=null
    private var operation:Any?=null
    private var failureOwner:Any?=null
    private var failure by mutableStateOf<BacklinkProblem?>(null)
    var busy by mutableStateOf(false);private set

    fun failureFor(owner:Any)=failure.takeIf {failureOwner===owner}
    fun canCancel(owner:Any)=busy && failureOwner===owner
    fun cancel(owner:Any) {if(canCancel(owner)) cancel()}

    fun open(request:FileRelationOpenRequest,onCommitted:(MapWorkspace.Entry)->Unit={}):Boolean {
        if(!active || busy || workspace.visible || !workspace.isHostCurrent(binding) || !request.current()) return false
        failureOwner=request.owner;failure=null
        val host=captureHost()
        if(!host()) {failure=BacklinkProblem.CHANGED;return false}
        onStarting();workspace.cancelOpen()
        val token=++generation;val op=Any()
        operation=op;busy=true
        fun current()=active && generation==token && workspace.isHostCurrent(binding) && request.current() && host()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var held:MapDocumentLease?=null
            var committed=false
            var preserveDirty=false
            val watcher=scope.launch {
                while(isActive && generation==token && !committed) {
                    if(!current()) {failure=BacklinkProblem.CHANGED;job?.cancel();break}
                    withContext(Dispatchers.Default) {delay(32)}
                }
            }
            try {
                val access=acquireFileRelation(app,request,::current)
                if(access is FileRelationAccessResult.Failed) {if(current()) failure=access.problem;return@launch}
                access as FileRelationAccessResult.Ready
                held=access.lease
                val lease=access.lease;val session=lease.session;val row=request.row
                val ticket=session.captureFileRelationFocus(row.location.graphId,row.relation,row.fromFileId,row.toFileId,access.membership)
                if(!current() || ticket==null) {
                    if(current()) failure=if(session.hasUnconfirmedContent) BacklinkProblem.BUSY else BacklinkProblem.CHANGED
                    return@launch
                }
                fun ready()=current() && lease.alive && !workspace.visible && session.fileRelationFocusCurrent(ticket)
                val finished=CompletableDeferred<Boolean>()
                var locationFailure:BacklinkProblem?=null
                val accepted=workspace.open(row.location.uri,row.location.title,preparePresentation={
                    if(!ready()) null else editor.awaitHostParking()?.let {proof->
                        locationFailure=backlinkLocationProblem(app,request.roots,row.location)
                        if(locationFailure==null && backlinkRecoveryOutside(app,lease.uri,row.location.aliases,session.recoveryKeys))
                            locationFailure=BacklinkProblem.RECOVERY_CONFLICT
                        if(locationFailure!=null) {preserveDirty=true;null} else ({ready() && proof.current()})
                    }
                },stillCurrent=::ready,operation=op,flushNativeOnPresent=request.flushNativeOnPresent,onPresented={shown->
                    // These checks and adoption run on Main without another suspension.
                    check(shown.session===session)
                    check(session.applyFileRelationFocus(ticket))
                    check(shown.presentation.requestFileRelationFocus(session,ticket))
                    committed=true;shown.sourceReturn=null;failure=null
                    onPresented(shown);onCommitted(shown)
                },onFinished={shown->finished.complete(shown)})
                if(!accepted || !finished.await()) {
                    if(current()) failure=locationFailure ?: if(session.hasUnconfirmedContent) BacklinkProblem.BUSY else BacklinkProblem.CHANGED
                }
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {if(!committed && current()) failure=BacklinkProblem.UNAVAILABLE}
            finally {
                watcher.cancel()
                if(!committed) workspace.cancelOpen(op)
                withContext(NonCancellable+Dispatchers.Main.immediate) {held?.close(preserveDirty=preserveDirty)?.join()}
                if(generation==token) {operation=null;job=null;busy=false}
            }
        }
        job=pending;pending.start();return true
    }
    fun cancel() {
        generation++;val pending=job;job=null
        operation?.let {workspace.cancelOpen(it)};operation=null
        pending?.cancel();busy=false;failure=null;failureOwner=null
    }
    fun dispose() {cancel();active=false}
}
