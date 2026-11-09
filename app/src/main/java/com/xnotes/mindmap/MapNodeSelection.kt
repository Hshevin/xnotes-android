package com.xnotes.mindmap

/** Runtime proof for one selection operation; never serialized with a map. */
internal class MapNodeSelectionCapture(
    val document:MindMapDocument,
    ids:Set<String>,
    internal val owner:Any=Unit,
    internal val epoch:Long=0L,
    internal val revision:Long=0L,
    internal val selectionRevision:Long=0L,
    internal val visibilityRevision:Long=0L,
) {
    val ids:Set<String> = ids.toSet()
}

enum class MapAlignment { LEFT, CENTER_X, RIGHT, TOP, CENTER_Y, BOTTOM }

/** Build once per operation so ancestor + descendant selections never move twice. */
private fun MindMapDocument.selectedSubtrees(ids:Set<String>):Set<String> {
    require(nodes.mapTo(hashSetOf()) {it.id}.containsAll(ids))
    val children=nodes.groupBy {it.parent}
    val todo=ArrayDeque(ids)
    val found=hashSetOf<String>()
    while(todo.isNotEmpty()) {
        val id=todo.removeFirst()
        if(found.add(id)) children[id].orEmpty().forEach {todo.add(it.id)}
    }
    return found
}

/** Only positions change: immutable node-local ink and source lists retain their identity. */
fun MindMapDocument.translateNodes(ids:Set<String>,dx:Double,dy:Double):MindMapDocument {
    require(dx.isFinite() && dy.isFinite())
    val moved=selectedSubtrees(ids)
    if(moved.isEmpty() || dx==0.0 && dy==0.0) return this
    return copy(nodes=nodes.map {if(it.id in moved) it.copy(x=it.x+dx,y=it.y+dy) else it}).also {it.validate()}
}

fun MindMapDocument.deleteNodes(ids:Set<String>):MindMapDocument {
    require(nodes.mapTo(hashSetOf()) {it.id}.containsAll(ids))
    // Exclude the root BEFORE expansion, otherwise selecting it would delete all other branches.
    val removed=selectedSubtrees(ids-listOfNotNull(centralRoot?.id).toSet())
    if(removed.isEmpty()) return this
    return copy(nodes=nodes.filter {it.id !in removed},
        relations=relations.filter {it.from !in removed && it.to !in removed}).also {it.validate()}
}

fun MindMapDocument.canAlignNodes(ids:Set<String>):Boolean {
    if(ids.size<2) return false
    val visible=visible().associateBy {it.id}
    return ids.all {id->visible[id]?.let {it.free && it.parent==null}==true}
}

/** Automatic branches cannot be individually aligned; each free topic carries its whole subtree. */
fun MindMapDocument.alignNodes(ids:Set<String>,alignment:MapAlignment):MindMapDocument {
    require(canAlignNodes(ids))
    val topics=nodes.filter {it.id in ids}
    val bounds=topics.map {it.bounds}.reduce {a,b->a.union(b)}
    val deltas=hashMapOf<String,com.xnotes.core.geometry.Pt>()
    val children=nodes.groupBy {it.parent}
    val todo=ArrayDeque<String>()
    for(topic in topics) {
        val dx=when(alignment) {
            MapAlignment.LEFT->bounds.left-topic.bounds.left
            MapAlignment.CENTER_X->bounds.centerX-topic.bounds.centerX
            MapAlignment.RIGHT->bounds.right-topic.bounds.right
            else->0.0
        }
        val dy=when(alignment) {
            MapAlignment.TOP->bounds.top-topic.bounds.top
            MapAlignment.CENTER_Y->bounds.centerY-topic.bounds.centerY
            MapAlignment.BOTTOM->bounds.bottom-topic.bounds.bottom
            else->0.0
        }
        if(dx!=0.0 || dy!=0.0) {deltas[topic.id]=com.xnotes.core.geometry.Pt(dx,dy);todo.add(topic.id)}
    }
    if(todo.isEmpty()) return this
    while(todo.isNotEmpty()) {
        val id=todo.removeFirst()
        for(child in children[id].orEmpty()) {
            deltas[child.id]=deltas.getValue(id);todo.add(child.id)
        }
    }
    // One pass over potentially large ink, instead of one full validation per aligned topic.
    return copy(nodes=nodes.map {node->deltas[node.id]?.let {node.copy(x=node.x+it.x,y=node.y+it.y)} ?: node})
        .also {it.validate()}
}
