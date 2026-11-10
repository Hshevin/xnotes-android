package com.xnotes.mindmap

import java.util.Collections

/** The original displayed explicit edge, bound to its panel/network and narrowed by host guards. */
internal class FileRelationOpenRequest private constructor(
    val row:MapFileRelationRow,roots:List<String>,val owner:Any,val flushNativeOnPresent:Boolean,private val valid:()->Boolean,private val storedPath:NetworkStoredPathGuard?,
) {
    val roots:List<String> = Collections.unmodifiableList(roots.distinct())
    fun current():Boolean=valid() && storedPath?.current()!=false && !row.conflict && row.blocked==null &&
        if(row.membership==null) row.origin!=BacklinkOrigin.LIVE
        else row.origin==BacklinkOrigin.LIVE && row.membership.current(row.membership.issuer,
            row.location.graphId,row.relation,row.fromFileId,row.toFileId)
    internal fun targetAcquired(session:MapSession)=storedPath?.targetAcquired(session) ?: true
    fun withPresentationGuard(guard:()->Boolean):FileRelationOpenRequest? =
        FileRelationOpenRequest(row,roots,owner,flushNativeOnPresent,{current() && guard()},storedPath).takeIf {it.current()}
    companion object {
        fun capture(row:MapFileRelationRow,roots:List<String>,owner:Any,flushNativeOnPresent:Boolean=true,storedPath:NetworkStoredPathGuard?=null,valid:()->Boolean):FileRelationOpenRequest? =
            FileRelationOpenRequest(row,roots,owner,flushNativeOnPresent,valid,storedPath).takeIf {it.current()}
    }
}
