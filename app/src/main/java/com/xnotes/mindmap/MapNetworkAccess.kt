package com.xnotes.mindmap

import android.content.Context

internal sealed interface MapNetworkAccessResult {
    data class Ready(val lease:MapDocumentLease,val anchorMembership:MapBacklinkMembership,
        val targetMembership:MapBacklinkMembership):MapNetworkAccessResult
    data class Failed(val problem:BacklinkProblem):MapNetworkAccessResult
}

/** A network keeps its original focus anchor while opening the exact co-source in the same concept. */
internal suspend fun acquireNetwork(app:Context,request:MapNetworkOpenRequest,valid:()->Boolean,
    roots:List<String> = request.query.roots):MapNetworkAccessResult {
    fun current()=request.current() && valid()
    if(!current()) return MapNetworkAccessResult.Failed(BacklinkProblem.CHANGED)
    return when(val result=acquireBacklinkMembers(app,listOf(request.anchor,request.target),::current,roots,targetAcquired=request::targetAcquired)) {
        is BacklinkMembersAccessResult.Ready->MapNetworkAccessResult.Ready(result.lease,result.memberships[0],result.memberships[1])
        is BacklinkMembersAccessResult.Failed->MapNetworkAccessResult.Failed(result.problem)
    }
}
