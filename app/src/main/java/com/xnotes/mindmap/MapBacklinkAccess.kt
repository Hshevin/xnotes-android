package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface BacklinkAccessResult {
    data class Ready(val lease:MapDocumentLease,val membership:MapBacklinkMembership):BacklinkAccessResult
    data class Failed(val problem:BacklinkProblem):BacklinkAccessResult
}

/** Extra aliases may reveal recovery work that an already-open actor never loaded. */
internal suspend fun backlinkRecoveryOutside(app:Context,uri:Uri,aliases:Set<String>,covered:Set<String>):Boolean =
    withContext(Dispatchers.IO) {
        val coroutine=currentCoroutineContext();val check={coroutine.ensureActive()}
        MapSaves.lockFor(uri).withLock {
            check();MapFiles.store(app,uri,checkActive=check,knownAliases=aliases).recoveryOutside(covered).also {check()}
        }
    }

/** Main-thread action access. Only this action's own new actor can adopt a stored row's first proof. */
internal suspend fun acquireBacklink(app:Context,row:MapBacklinkRow,valid:()->Boolean,roots:List<String> = emptyList()):BacklinkAccessResult {
    if(!valid()) return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
    row.blocked?.let {return BacklinkAccessResult.Failed(it)}
    val place=try {backlinkLocationProblem(app,roots,row)}
        catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
        catch(_:Exception) {BacklinkProblem.UNAVAILABLE}
    place?.let {return BacklinkAccessResult.Failed(it)}
    if(!valid()) return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
    val original=MapDocuments.observations(app).firstOrNull {it.key==row.location.key}
    val member=row.membership
    if(member==null && original!=null) return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
    if(member!=null && (original==null || !member.current(original.session,row.location.graphId,row.nodeId,row.sourceId,row.target)))
        return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
    val lease=MapDocuments.acquire(app,original?.uri ?: row.location.uri,row.location.aliases)
    var transferred=false
    var preserveDirty=false
    try {
        snapshotFlow {Triple(lease.session.loading,lease.alive,valid())}.first {!it.first || !it.second || !it.third}
        if(!lease.alive || !valid()) return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
        val session=lease.session
        if(session.loadFailed || session.document==null) return BacklinkAccessResult.Failed(BacklinkProblem.UNAVAILABLE)
        if(session.document?.id!=row.location.graphId) return BacklinkAccessResult.Failed(BacklinkProblem.IDENTITY)
        if(member!=null && !member.current(session,row.location.graphId,row.nodeId,row.sourceId,row.target))
            return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
        if(backlinkRecoveryOutside(app,lease.uri,row.location.aliases,session.recoveryKeys)) {
            preserveDirty=true;return BacklinkAccessResult.Failed(BacklinkProblem.RECOVERY_CONFLICT)
        }
        backlinkLocationProblem(app,roots,row)?.let {preserveDirty=true;return BacklinkAccessResult.Failed(it)}
        if(!valid()) return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
        val observation=MapDocuments.observations(app).firstOrNull {it.session===session && it.key==row.location.key}
            ?: return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
        val proof=member ?: observation.captureMembership(row.nodeId,row.sourceId,row.target)
            ?: return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
        if(!proof.current(session,row.location.graphId,row.nodeId,row.sourceId,row.target))
            return BacklinkAccessResult.Failed(BacklinkProblem.CHANGED)
        transferred=true
        return BacklinkAccessResult.Ready(lease,proof)
    } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
    catch(_:Exception) {return BacklinkAccessResult.Failed(BacklinkProblem.UNAVAILABLE)}
    finally {if(!transferred) withContext(NonCancellable+Dispatchers.Main.immediate) {lease.close(preserveDirty)}}
}
