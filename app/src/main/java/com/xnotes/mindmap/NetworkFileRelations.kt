package com.xnotes.mindmap

/** One render edge can explain several precise user-authored relations in different graphs. */
internal data class NetworkFileRelationKey(val fromFileId:String,val toFileId:String,val kind:RelationKind) {
    val stableKey get()=listOf(fromFileId,toFileId,kind.name).joinToString("") {"${it.length}:$it"}
    companion object {
        fun of(row:MapFileRelationRow):NetworkFileRelationKey {
            val ends=if(row.relation.directed) listOf(row.fromFileId,row.toFileId) else listOf(row.fromFileId,row.toFileId).sorted()
            return NetworkFileRelationKey(ends[0],ends[1],row.relation.kind)
        }
    }
}
internal data class LocalNetworkFileRelation(val key:NetworkFileRelationKey,val rows:List<MapFileRelationRow>)
internal class NetworkFileRelationOrigin(val fileId:String,val depth:Int,path:List<MapBacklinkRow> = emptyList(),
    relationPath:List<MapFileRelationRow> = emptyList()) {
    val path:List<MapBacklinkRow> = java.util.Collections.unmodifiableList(path.toList())
    val relationPath:List<MapFileRelationRow> = java.util.Collections.unmodifiableList(relationPath.toList())
    init {require(depth in 1..251 && path.size<=500 && relationPath.size<=250)}
    fun accepts(row:MapFileRelationRow)=fileId in row.fileIds
}
internal fun MapFileRelationRow.networkUsable():Boolean = blocked==null && !conflict &&
    canonicalNetworkId(location.graphId) && canonicalNetworkId(fromFileId) && canonicalNetworkId(toFileId) && fromFileId!=toFileId &&
    (if(origin==BacklinkOrigin.LIVE) membership?.current()==true else membership==null)
internal fun canonicalNetworkId(id:String)=runCatching {java.util.UUID.fromString(id).toString()==id}.getOrDefault(false)
