package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import java.util.UUID

internal enum class ConnectedDraftProblem { CHANGED,LIMIT,SOURCE }

/**
 * Where a creation draft lands on confirmation. CONNECTED keeps the drop point as the card's center and
 * adds one undirected relation from [ConnectedNodeDraft.from]; CHILD and SIBLING attach under the parent
 * held in `from`; FREE places a free topic beside the visible graph.
 */
internal enum class NodePlacement { CONNECTED,CHILD,SIBLING,FREE }

/**
 * Unconfirmed content belongs to the session. Kind, title and sources stay in this draft (never in history)
 * until one confirmation adds node, hierarchy or relation, and sources together.
 */
internal data class ConnectedNodeDraft(
    val token:String=UUID.randomUUID().toString(),val epoch:Long,val documentId:String,val from:String,val center:Pt,
    val nodeId:String=UUID.randomUUID().toString(),val node:MapNode?=null,
    val loadingSource:Boolean=false,val problem:ConnectedDraftProblem?=null,
    val placement:NodePlacement=NodePlacement.CONNECTED,
    /** Session lifetime of the anchor at begin; delete then undo is a different node for this draft. */
    internal val anchorLife:Any?=null,
)

internal data class ConnectedSourceRequest(val id:String=UUID.randomUUID().toString(),val token:String,val epoch:Long)

internal data class SourceEditSnapshot(val epoch:Long,val documentId:String,val node:MapNode)
