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
    private var failureOwner:Any?=null
    private var failure by mutableStateOf<BacklinkProblem?>(null)
    var busy by mutableStateOf(false);private set

    fun failureFor(context:NativeBacklinkContext):BacklinkProblem? =
        failure.takeIf {failureOwner===context && context.current()}

    fun failureForNetwork(owner:Any)=failure.takeIf {failureOwner===owner}
    fun cancelNetwork(owner:Any) {if(busy && failureOwner===owner) cancel()}

    fun open(request:MapBacklinkOpenRequest):Boolean = start(request.row,request.review,request.context.libraryRoots,
        request.context,request::current,null) {}

    fun openNetwork(request:MapNetworkOpenRequest,onCommitted:(MapWorkspace.Entry)->Unit={}):Boolean =
        start(request.target,null,request.query.roots,request.owner,request::current,request,onCommitted)

    private fun start(row:MapBacklinkRow,review:MapBacklinkRemovalTicket?,roots:List<String>,owner:Any,
        origin:()->Boolean,network:MapNetworkOpenRequest?,onCommitted:(MapWorkspace.Entry)->Unit):Boolean {
        if(!active || busy || workspace.visible || !workspace.isHostCurrent(binding) || !origin()) return false
        failureOwner=owner;failure=null
        row.blocked?.let {failure=it;return false}
        val host=captureHost()
        if(!host()) {failure=BacklinkProblem.CHANGED;return false}
        onStarting()
        workspace.cancelOpen()
        val token=++generation;val op=Any()
        operation=op;busy=true
        fun current()=active && generation==token && workspace.isHostCurrent(binding) && origin() && host()
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
                var anchor:MapBacklinkMembership?=null
                val membership:MapBacklinkMembership?
                if(network!=null) {
                    when(val access=acquireNetwork(app,network,::current,roots)) {
                        is MapNetworkAccessResult.Failed->{if(current()) failure=access.problem;return@launch}
                        is MapNetworkAccessResult.Ready->{held=access.lease;membership=access.targetMembership;anchor=access.anchorMembership}
                    }
                } else if(review==null) {
                    when(val access=acquireBacklink(app,row,::current,roots=roots)) {
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
                val ticket=when {
                    network!=null->session.captureNetworkFocus(row.location.graphId,row.nodeId,row.sourceId,row.target,requireNotNull(anchor),requireNotNull(membership))
                    review!=null->session.captureRemovedBacklinkFocus(review)
                    else->session.captureBacklinkFocus(row.location.graphId,row.nodeId,row.sourceId,row.target,membership)
                }
                if(!current() || ticket==null) {
                    if(current()) failure=if(session.hasUnconfirmedContent) BacklinkProblem.BUSY else BacklinkProblem.CHANGED
                    return@launch
                }
                fun ready()=current() && lease.alive && !workspace.visible && session.backlinkFocusCurrent(ticket)
                val finished=CompletableDeferred<Boolean>()
                var locationFailure:BacklinkProblem?=null
                val accepted=workspace.open(row.location.uri,row.location.title,preparePresentation={
                    if(!ready()) null else editor.awaitHostParking()?.let {proof->
                        locationFailure=backlinkLocationProblem(app,roots,row)
                        if(locationFailure==null && backlinkRecoveryOutside(app,lease.uri,
                            row.location.aliases+network?.anchor?.location?.aliases.orEmpty(),session.recoveryKeys))
                            locationFailure=BacklinkProblem.RECOVERY_CONFLICT
                        if(locationFailure!=null) {preserveDirty=true;null} else ({ready() && proof.current()})
                    }
                },stillCurrent=::ready,operation=op,flushNativeOnPresent=network==null,onPresented={shown->
                    // The final predicate above and this callback run on Main without suspension.
                    // Hiding the source panel after this point must not undo an accepted handoff.
                    check(shown.session===session)
                    check(session.applyBacklinkFocus(ticket))
                    check(shown.presentation.requestBacklinkFocus(session,ticket))
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

    fun canCancel(context:NativeBacklinkContext)=busy && failureOwner===context
    fun cancel(context:NativeBacklinkContext) {if(canCancel(context)) cancel()}

    fun cancel() {
        generation++;val pending=job;job=null
        operation?.let {workspace.cancelOpen(it)};operation=null
        pending?.cancel();busy=false;failure=null;failureOwner=null
    }
    fun dispose() {cancel();active=false}
}
