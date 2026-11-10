package com.xnotes.mindmap

/** Stable identity of one explicit relation in one actual graph observation. */
internal data class FileRelationKey(val locationKey:String,val graphId:String,val relationId:String) {
    val stableKey get()=listOf(locationKey,graphId,relationId).joinToString("") {"${it.length}:$it"}
}

/** A file-card edge is separate evidence; its identity sources are never content backlinks. */
internal data class MapFileRelationRow(
    val location:BacklinkLocation,val relation:MapRelation,val fromFileId:String,val toFileId:String,
    val fromTitle:String,val toTitle:String,val origin:BacklinkOrigin,val conflict:Boolean=false,
    val blocked:BacklinkProblem?=null,internal val membership:MapFileRelationMembership?=null,
) {
    val key get()=FileRelationKey(location.key,location.graphId,relation.id)
    val fileIds:Set<String> get()=setOf(fromFileId,toFileId)
}
internal data class FileRelationProjection(val rows:List<MapFileRelationRow>,val limited:Boolean=false)
