package com.xnotes.mindmap

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeBacklinkContext
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

/** A source pane hands off to the same broker actor, after a fresh native parking proof. */
@MainThread
internal class MapBacklinkNavigator(
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
    private var failureContext:NativeBacklinkContext?=null
    private var failure by mutableStateOf<BacklinkProblem?>(null)
    var busy by mutableStateOf(false);private set

    fun failureFor(context:NativeBacklinkContext):BacklinkProblem? =
        failure.takeIf {failureContext===context && context.current()}

    fun open(request:MapBacklinkOpenRequest):Boolean {
        if(!active || busy || workspace.visible || !workspace.isHostCurrent(binding) || !request.current()) return false
        failureContext=request.context;failure=null
        request.row.blocked?.let {failure=it;return false}
        val host=captureHost()
        if(!host()) {failure=BacklinkProblem.CHANGED;return false}
        onStarting()
        workspace.cancelOpen()
        val token=++generation;val op=Any()
        operation=op;busy=true
        fun current()=active && generation==token && workspace.isHostCurrent(binding) && request.current() && host()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var held:MapDocumentLease?=null
            var committed=false
            var preserveDirty=false
            // A panel may close without another physical input event to wake native parking.
            val watcher=scope.launch {
                while(isActive && generation==token && !committed) {
                    if(!current()) {failure=BacklinkProblem.CHANGED;job?.cancel();break}
                    withContext(Dispatchers.Default) {delay(32)}
                }
            }
            try {
                val row=request.row
                val review=request.review
                val membership:MapBacklinkMembership?
                if(review==null) {
                    when(val access=acquireBacklink(app,row,::current,roots=request.context.libraryRoots)) {
                        is BacklinkAccessResult.Failed->{if(current()) failure=access.problem;return@launch}
                        is BacklinkAccessResult.Ready->{held=access.lease;membership=access.membership}
                    }
                } else {
                    // Only the explicit applied-removal receipt can review a source-less node.
                    val existing=MapDocuments.observations(app).singleOrNull {it.key==row.location.key && it.session===review.issuer}
                    if(existing==null || !current() || review.issuer.captureRemovedBacklinkFocus(review)==null) {
                        if(current()) failure=BacklinkProblem.CHANGED
                        return@launch
                    }
                    held=MapDocuments.acquire(app,row.location.uri,row.location.aliases)
                    membership=null
                }
                val lease=requireNotNull(held);val session=lease.session
                val ticket=if(review==null) session.captureBacklinkFocus(row.location.graphId,row.nodeId,row.sourceId,row.target,membership)
                    else session.captureRemovedBacklinkFocus(review)
                if(!current() || ticket==null) {
                    if(current()) failure=if(session.hasUnconfirmedContent) BacklinkProblem.BUSY else BacklinkProblem.CHANGED
                    return@launch
                }
                fun ready()=current() && lease.alive && !workspace.visible && session.backlinkFocusCurrent(ticket)
                val finished=CompletableDeferred<Boolean>()
                var locationFailure:BacklinkProblem?=null
                val accepted=workspace.open(row.location.uri,row.location.title,preparePresentation={
                    if(!ready()) null else editor.awaitHostParking()?.let {proof->
                        locationFailure=backlinkLocationProblem(app,request.context.libraryRoots,row)
                        if(locationFailure==null && backlinkRecoveryOutside(app,lease.uri,row.location.aliases,session.recoveryKeys))
                            locationFailure=BacklinkProblem.RECOVERY_CONFLICT
                        if(locationFailure!=null) {preserveDirty=true;null} else ({ready() && proof.current()})
                    }
                },stillCurrent=::ready,operation=op,onPresented={shown->
                    // The final predicate above and this callback run on Main without suspension.
                    // Hiding the source panel after this point must not undo an accepted handoff.
                    check(shown.session===session)
                    check(session.applyBacklinkFocus(ticket))
                    check(shown.presentation.requestBacklinkFocus(session,ticket))
                    committed=true;shown.sourceReturn=null;failure=null
                    onPresented(shown)
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

    fun canCancel(context:NativeBacklinkContext)=busy && failureContext===context
    fun cancel(context:NativeBacklinkContext) {if(canCancel(context)) cancel()}

    fun cancel() {
        generation++;val pending=job;job=null
        operation?.let {workspace.cancelOpen(it)};operation=null
        pending?.cancel();busy=false;failure=null;failureContext=null
    }
    fun dispose() {cancel();active=false}
}
