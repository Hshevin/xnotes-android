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

/** Both entry points share one broker acquisition and the same action-time location checks. */
internal sealed interface BacklinkMembersAccessResult {
    data class Ready(val lease:MapDocumentLease,val memberships:List<MapBacklinkMembership>):BacklinkMembersAccessResult
    data class Failed(val problem:BacklinkProblem):BacklinkMembersAccessResult
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
    return when(val result=acquireBacklinkMembers(app,listOf(row),valid,roots)) {
        is BacklinkMembersAccessResult.Ready->BacklinkAccessResult.Ready(result.lease,result.memberships.single())
        is BacklinkMembersAccessResult.Failed->BacklinkAccessResult.Failed(result.problem)
    }
}

/** Main-thread access; a stored observation can first sign only this call's own new actor. */
internal suspend fun acquireBacklinkMembers(app:Context,rows:List<MapBacklinkRow>,valid:()->Boolean,
    roots:List<String>,targetAcquired:(MapSession)->Boolean={true}):BacklinkMembersAccessResult {
    if(!valid()) return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
    val first=rows.firstOrNull() ?: return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
    if(rows.any {it.location.key!=first.location.key || it.location.graphId!=first.location.graphId || it.nodeId!=first.nodeId} ||
        rows.any {it.membership==null} && rows.any {it.membership!=null} ||
        rows.any {it.membership?.issuer!==first.membership?.issuer}) return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
    rows.firstNotNullOfOrNull {it.blocked}?.let {return BacklinkMembersAccessResult.Failed(it)}
    val aliases=rows.flatMapTo(linkedSetOf()) {it.location.aliases}.apply {rows.forEach {add(it.location.uri.toString())}}
    val location=BacklinkLocation(first.location.uri,aliases,first.location.key,first.location.graphId,first.location.title)
    val row=first.copy(location=location)
    val place=try {backlinkLocationProblem(app,roots,row)}
        catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
        catch(_:Exception) {BacklinkProblem.UNAVAILABLE}
    if(!valid()) return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
    place?.let {
        MapDocuments.preserveCanonicalActor(app,row.location.uri,row.location.graphId,first.membership?.issuer)
        return BacklinkMembersAccessResult.Failed(it)
    }
    val original=MapDocuments.observations(app).firstOrNull {it.key==row.location.key}
    if(rows.all {it.membership==null} && original!=null) return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
    if(rows.any {r->r.membership?.let {member->original==null ||
        !member.current(original.session,r.location.graphId,r.nodeId,r.sourceId,r.target)}==true})
        return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
    // No suspension between the directory check and acquisition: only this call can create this entry.
    val lease=MapDocuments.acquire(app,original?.uri ?: row.location.uri,aliases)
    var transferred=false
    var preserveDirty=false
    try {
        if(!targetAcquired(lease.session)) return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
        snapshotFlow {Triple(lease.session.loading,lease.alive,valid())}.first {!it.first || !it.second || !it.third}
        if(!lease.alive || !valid()) return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
        val session=lease.session
        if(session.loadFailed || session.document==null) return BacklinkMembersAccessResult.Failed(BacklinkProblem.UNAVAILABLE)
        if(session.document?.id!=row.location.graphId) return BacklinkMembersAccessResult.Failed(BacklinkProblem.IDENTITY)
        if(rows.any {r->r.membership?.current(session,r.location.graphId,r.nodeId,r.sourceId,r.target)==false})
            return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
        if(backlinkRecoveryOutside(app,lease.uri,aliases,session.recoveryKeys)) {
            preserveDirty=true;return BacklinkMembersAccessResult.Failed(BacklinkProblem.RECOVERY_CONFLICT)
        }
        backlinkLocationProblem(app,roots,row)?.let {preserveDirty=true;return BacklinkMembersAccessResult.Failed(it)}
        if(!valid()) return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
        val observation=MapDocuments.observations(app).firstOrNull {it.session===session && it.key==row.location.key}
            ?: return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
        val proofs=rows.map {r->
            val proof=r.membership ?: observation.captureMembership(r.nodeId,r.sourceId,r.target)
                ?: return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
            if(!proof.current(session,r.location.graphId,r.nodeId,r.sourceId,r.target))
                return BacklinkMembersAccessResult.Failed(BacklinkProblem.CHANGED)
            proof
        }
        transferred=true
        return BacklinkMembersAccessResult.Ready(lease,proofs)
    } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
    catch(_:Exception) {return BacklinkMembersAccessResult.Failed(BacklinkProblem.UNAVAILABLE)}
    finally {if(!transferred) withContext(NonCancellable+Dispatchers.Main.immediate) {lease.close(preserveDirty)}}
}
