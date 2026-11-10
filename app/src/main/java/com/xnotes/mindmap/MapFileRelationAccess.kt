package com.xnotes.mindmap

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal sealed interface FileRelationAccessResult {
    data class Ready(val lease:MapDocumentLease,val membership:MapFileRelationMembership):FileRelationAccessResult
    data class Failed(val problem:BacklinkProblem):FileRelationAccessResult
}

/** A stored edge can first join only the actor created by this action; live proof never changes issuer. */
internal suspend fun acquireFileRelation(app:Context,request:FileRelationOpenRequest,
    valid:()->Boolean):FileRelationAccessResult {
    fun current()=request.current() && valid()
    fun failed(problem:BacklinkProblem)=FileRelationAccessResult.Failed(problem)
    if(!current()) return failed(BacklinkProblem.CHANGED)
    val row=request.row
    val location=row.location
    val aliases=location.aliases+location.uri.toString()
    val place=try {backlinkLocationProblem(app,request.roots,location)}
        catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
        catch(_:Exception) {BacklinkProblem.UNAVAILABLE}
    if(!current()) return failed(BacklinkProblem.CHANGED)
    place?.let {
        MapDocuments.preserveCanonicalActor(app,location.uri,location.graphId,row.membership?.issuer)
        return failed(it)
    }
    val original=MapDocuments.observations(app).firstOrNull {it.key==location.key}
    if(row.membership==null && original!=null) return failed(BacklinkProblem.CHANGED)
    if(row.membership?.let {member->original==null ||
        !member.current(original.session,location.graphId,row.relation,row.fromFileId,row.toFileId)}==true)
        return failed(BacklinkProblem.CHANGED)
    // No suspension: a stored observation cannot accidentally adopt an actor opened by another action.
    val lease=MapDocuments.acquire(app,original?.uri ?: location.uri,aliases)
    var transferred=false
    var preserveDirty=false
    try {
        if(!request.targetAcquired(lease.session)) return failed(BacklinkProblem.CHANGED)
        snapshotFlow {Triple(lease.session.loading,lease.alive,current())}.first {!it.first || !it.second || !it.third}
        if(!lease.alive || !current()) return failed(BacklinkProblem.CHANGED)
        val session=lease.session
        if(session.loadFailed || session.document==null) return failed(BacklinkProblem.UNAVAILABLE)
        if(session.document?.id!=location.graphId) {preserveDirty=true;return failed(BacklinkProblem.IDENTITY)}
        if(row.membership?.current(session,location.graphId,row.relation,row.fromFileId,row.toFileId)==false)
            return failed(BacklinkProblem.CHANGED)
        if(backlinkRecoveryOutside(app,lease.uri,aliases,session.recoveryKeys)) {
            preserveDirty=true;return failed(BacklinkProblem.RECOVERY_CONFLICT)
        }
        backlinkLocationProblem(app,request.roots,location)?.let {preserveDirty=true;return failed(it)}
        if(!current()) return failed(BacklinkProblem.CHANGED)
        val observation=MapDocuments.observations(app).firstOrNull {it.session===session && it.key==location.key}
            ?: return failed(BacklinkProblem.CHANGED)
        val membership=row.membership ?: observation.captureFileRelation(row.relation.id)
            ?: return failed(BacklinkProblem.CHANGED)
        if(!membership.current(session,location.graphId,row.relation,row.fromFileId,row.toFileId))
            return failed(BacklinkProblem.CHANGED)
        transferred=true
        return FileRelationAccessResult.Ready(lease,membership)
    } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
    catch(_:Exception) {return failed(BacklinkProblem.UNAVAILABLE)}
    finally {if(!transferred) withContext(NonCancellable+Dispatchers.Main.immediate) {lease.close(preserveDirty)}}
}
