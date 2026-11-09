package com.xnotes.mindmap

import java.util.UUID

/** An exploration focus is data, independent of whichever native document is parked underneath it. */
internal enum class NetworkBrowseMode { LOCAL,FILES,LIBRARY }
internal class NetworkQuery private constructor(val focusId:String,title:String,roots:List<String>,val hasLocalFocus:Boolean) {
    constructor(focusId:String,title:String,roots:List<String>):this(focusId,title,roots,true)
    val title=title.take(BacklinkBudget.LABEL_CHARS)
    val roots:List<String> = java.util.Collections.unmodifiableList(roots.distinct())
    init {
        require(UUID.fromString(focusId).toString()==focusId)
        require(this.roots.size<=4096 && this.roots.all {it.isNotBlank() && it.length<=8192})
    }
    companion object {
        // This ID names the runtime frame, never a source document or a native edit authority.
        fun library(title:String,roots:List<String>)=NetworkQuery(UUID.randomUUID().toString(),title,roots,false)
    }
}
internal data class NetworkConceptKey(val locationKey:String,val graphId:String,val nodeId:String) {
    val stableKey get()=listOf(locationKey,graphId,nodeId).joinToString("") {"${it.length}:$it"}
}
internal data class LocalNetworkBudget(val read:BacklinkBudget=BacklinkBudget(),val maxSceneNodes:Int=250,
    val maxSceneEdges:Int=750,val maxEvidence:Int=5000) {
    init {require(maxSceneNodes in 1..MindMapDocument.MAX_NODES && maxSceneEdges in 1..4000 && maxEvidence>0)}
    val evidenceLimit get()=minOf(maxEvidence,read.maxRows)
}
internal data class NetworkConceptProjection(val key:NetworkConceptKey,val location:BacklinkLocation,
    val title:String,val kind:NodeKind,val members:List<MapBacklinkRow>)
internal data class NetworkProjection(val concepts:List<NetworkConceptProjection>,val limited:Boolean=false)
internal data class NetworkStoredGraph(val candidate:BacklinkCandidate,val graphId:String?,val concepts:List<NetworkConceptProjection>,
    val keys:Set<String> = emptySet(),val problem:BacklinkProblem?=null)
internal data class NetworkLibraryScan(val candidates:List<BacklinkCandidate>,val graphs:List<NetworkStoredGraph>,
    val issues:List<BacklinkIssue>,val omittedIssues:Int,val excludedMaps:Int,val reservedIds:Map<String,BacklinkProblem>,
    val limited:Boolean,val liveBlocked:Map<String,BacklinkProblem> = emptyMap())

/** The controller signs each displayed member. UI callbacks keep this original evidence object. */
internal class LocalNetworkEvidence internal constructor(val row:MapBacklinkRow,private val issuer:Any,private val generation:Long) {
    val key get()=row.key
    val conceptKey get()=NetworkConceptKey(row.location.key,row.location.graphId,row.nodeId)
    val fileId get()=row.target.documentId
    internal fun issuedBy(owner:Any,version:Long)=issuer===owner && generation==version
}
internal data class LocalNetworkConcept(val key:NetworkConceptKey,val location:BacklinkLocation,val title:String,
    val kind:NodeKind,val members:List<LocalNetworkEvidence>,val origin:NetworkConceptOrigin?=null)
internal data class LocalNetworkFile(val documentId:String,val observedNames:List<String>)
internal data class LocalFileNetworkReport(val focusDocumentId:String,val files:List<LocalNetworkFile>,
    val concepts:List<LocalNetworkConcept>,val issues:List<BacklinkIssue>,val checkedMaps:Int,val complete:Boolean,
    val omittedIssues:Int=0,val excludedMaps:Int=0,val limited:Boolean=false,
    val filterActive:Boolean=false,val matchedFiles:Set<String> = emptySet(),val unknownFilterFiles:Set<String> = emptySet(),
    val filteredConcepts:Int=0,val contextConcepts:Set<NetworkConceptKey> = emptySet(),
    val browseMode:NetworkBrowseMode=NetworkBrowseMode.LOCAL,val centreFiles:Set<String> = emptySet())

/** A graph explanation proves both its connection to the focus and the specific source to inspect. */
internal class MapNetworkOpenRequest private constructor(val query:NetworkQuery,val anchor:MapBacklinkRow,
    val target:MapBacklinkRow,val owner:Any,private val valid:()->Boolean,private val origin:NetworkConceptOrigin?=null) {
    fun current():Boolean=coherent(query,anchor,target,origin) && valid() && (origin?.path.orEmpty()+listOf(anchor,target)).all {row->
        row.blocked==null && row.membership?.let {it.current(it.issuer,row.location.graphId,row.nodeId,row.sourceId,row.target)}!=false
    }
    /** A view/host may only narrow an existing request; it cannot reissue either source membership. */
    fun withPresentationGuard(guard:()->Boolean):MapNetworkOpenRequest? =
        MapNetworkOpenRequest(query,anchor,target,owner,{current() && guard()},origin).takeIf {it.current()}
    companion object {
        private fun coherent(query:NetworkQuery,anchor:MapBacklinkRow,target:MapBacklinkRow,origin:NetworkConceptOrigin?):Boolean {
            val local=when(origin?.kind) {
                null->query.hasLocalFocus && anchor.target.documentId==query.focusId
                NetworkOriginKind.SOURCE->anchor.target.documentId==origin.fileId
                NetworkOriginKind.OWNER->target.location.graphId==origin.fileId && anchor.sourceId==target.sourceId && anchor.target==target.target
            }
            if(!local || anchor.blocked!=null || target.blocked!=null ||
                anchor.location.key!=target.location.key || anchor.location.graphId!=target.location.graphId || anchor.nodeId!=target.nodeId) return false
            val a=anchor.membership;val t=target.membership
            if((a==null)!=(t==null)) return false
            if(a==null) return anchor.origin!=BacklinkOrigin.LIVE && target.origin!=BacklinkOrigin.LIVE
            return anchor.origin==BacklinkOrigin.LIVE && target.origin==BacklinkOrigin.LIVE && a.issuer===t!!.issuer
        }
        internal fun capture(query:NetworkQuery,anchor:MapBacklinkRow,target:MapBacklinkRow,owner:Any,
            valid:()->Boolean):MapNetworkOpenRequest? =
            MapNetworkOpenRequest(query,anchor,target,owner,valid).takeIf {it.current()}
        internal fun captureExpanded(query:NetworkQuery,origin:NetworkConceptOrigin,anchor:MapBacklinkRow,target:MapBacklinkRow,
            owner:Any,valid:()->Boolean):MapNetworkOpenRequest? =
            MapNetworkOpenRequest(query,anchor,target,owner,valid,origin).takeIf {it.current()}
    }
}
