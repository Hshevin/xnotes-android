package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import java.util.UUID

internal enum class ConnectedDraftProblem { CHANGED,LIMIT,SOURCE }

/** Unconfirmed content belongs to the session; the drop point remains the new card's center. */
internal data class ConnectedNodeDraft(
    val token:String=UUID.randomUUID().toString(),val epoch:Long,val documentId:String,val from:String,val center:Pt,
    val nodeId:String=UUID.randomUUID().toString(),val node:MapNode?=null,
    val loadingSource:Boolean=false,val problem:ConnectedDraftProblem?=null,
)

internal data class ConnectedSourceRequest(val id:String=UUID.randomUUID().toString(),val token:String,val epoch:Long)

internal data class SourceEditSnapshot(val epoch:Long,val documentId:String,val node:MapNode)
