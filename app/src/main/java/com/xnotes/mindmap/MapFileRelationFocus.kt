package com.xnotes.mindmap

/** Actual relation and explicit file-card membership; never a content-source membership. */
internal class MapFileRelationMembership internal constructor(
    internal val issuer:MapSession,
    internal val documentId:String,
    internal val epoch:Long,
    internal val historyEpoch:Long,
    internal val relation:MapRelation,
    internal val fromFileId:String,
    internal val toFileId:String,
    internal val relationLifetime:Any,
    internal val fromLifetime:Any,
    internal val toLifetime:Any,
    private val entryCurrent:()->Boolean,
) {
    fun current():Boolean=entryCurrent() && !issuer.conflict && issuer.fileRelationMembershipCurrent(this)
    internal fun current(session:MapSession,graphId:String,expected:MapRelation,from:String,to:String):Boolean =
        issuer===session && documentId==graphId && relation==expected && fromFileId==from && toFileId==to && current()
}

internal class MapFileRelationFocusTicket internal constructor(internal val membership:MapFileRelationMembership) {
    val relationId get()=membership.relation.id
    val from get()=membership.relation.from
    val to get()=membership.relation.to
}
