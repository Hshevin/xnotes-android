package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import java.util.UUID

enum class SourceScope { FILE, PAGE }
enum class SourceStatus { UNVERIFIED, CURRENT, STALE, UNAVAILABLE, PARTIALLY_DELETED, DELETED, EMPTY, TRASHED }
/** Cached display fields never participate in source identity. Preview keys are opaque asset IDs. */
data class MapSource(
    val id: String = UUID.randomUUID().toString(),
    val documentId: String,
    val fileName: String = "",
    val scope: SourceScope = SourceScope.FILE,
    val pageId: String? = null,
    /** Optional page-local locator hint; never an object selection or a separate reference target. */
    val region: Rect? = null,
    val note: String = "",
    val status: SourceStatus = SourceStatus.UNVERIFIED,
    val previewKey: String? = null,
    val sourceVersion: String? = null,
    /** Display hint from the last actual catalog check. Never used to identify or locate the page. */
    val lastKnownPage: Int? = null,
) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 128)
        require(UUID.fromString(documentId).toString() == documentId)
        require(fileName.length <= 4096 && note.length <= 20_000)
        require(pageId == null || UUID.fromString(pageId).toString() == pageId)
        require(previewKey == null || previewKey.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(sourceVersion == null || sourceVersion.length <= 512)
        require(lastKnownPage == null || lastKnownPage in 1..com.xnotes.core.model.ReferenceAnchors.MAX_TARGETS)
        region?.let { r -> require(listOf(r.x,r.y,r.w,r.h).all { it.isFinite() && kotlin.math.abs(it) <= MindMapDocument.MAX_COORD } && r.w > 0 && r.h > 0) }
        when(scope) {
            SourceScope.FILE -> require(pageId == null && region == null && lastKnownPage == null)
            SourceScope.PAGE -> require(pageId != null)
        }
    }
}
enum class RelationKind { RELATED, REFERENCED_FROM, BELONGS_TO, DEPENDS_ON }
data class MapRelation(val id: String = UUID.randomUUID().toString(), val from: String, val to: String,
    val kind: RelationKind = RelationKind.RELATED, val label: String = "") {
    val directed get() = kind != RelationKind.RELATED
    fun duplicateKey(): List<String> = (if (directed) listOf(from,to) else listOf(from,to).sorted()) + kind.name
}
enum class MapDirection { BOTH, RIGHT, DOWN }

fun MindMapDocument.addFree(kind: NodeKind, text: String, x: Double, y: Double): Pair<MindMapDocument,String> {
    val added=MapNode(parent=null,kind=kind,text=text,x=x,y=y,free=true)
    return copy(nodes=nodes+added).also { it.validate() } to added.id
}
fun MindMapDocument.detach(id: String): MindMapDocument = if (id == root.id) this else update(id) { it.copy(parent=null,free=true) }
fun MindMapDocument.connect(from: String, to: String, kind: RelationKind): Pair<MindMapDocument,String> {
    require(from != to); node(from);node(to)
    val edge=MapRelation(from=from,to=to,kind=kind)
    relations.firstOrNull { it.duplicateKey()==edge.duplicateKey() }?.let { return this to it.id }
    return copy(relations=relations+edge).also { it.validate() } to edge.id
}

internal sealed interface ConnectedNodeResult {
    data class Applied(val document:MindMapDocument):ConnectedNodeResult
    data object Conflict:ConnectedNodeResult
    data object Invalid:ConnectedNodeResult
}

/** Both additions are validated before either enters history. Existing nodes keep their positions. */
internal fun MindMapDocument.connectNewNode(from:String,node:MapNode):ConnectedNodeResult {
    if(nodes.none {it.id==from}) return ConnectedNodeResult.Conflict
    if(node.parent!=null || !node.free || nodes.any {it.id==node.id}) return ConnectedNodeResult.Invalid
    val next=copy(nodes=nodes+node,relations=relations+MapRelation(from=from,to=node.id))
    return try {next.validate();ConnectedNodeResult.Applied(next)} catch(_:IllegalArgumentException) {ConnectedNodeResult.Invalid}
}

/** Result of a single ordinary-edge transaction against the latest graph. */
internal sealed interface RelationEditResult {
    data class Applied(val document:MindMapDocument):RelationEditResult
    data class Duplicate(val relation:MapRelation):RelationEditResult
    data object Conflict:RelationEditResult
    data object Invalid:RelationEditResult
}

/** Null initial creates; null proposed deletes. An existing edge keeps its stable ID. */
internal fun MindMapDocument.changeRelation(initial:MapRelation?,proposed:MapRelation?):RelationEditResult {
    if(initial!=null && relations.firstOrNull {it.id==initial.id}!=initial) return RelationEditResult.Conflict
    if(proposed==null) return if(initial==null) RelationEditResult.Invalid
        else RelationEditResult.Applied(copy(relations=relations.filterNot {it.id==initial.id}))
    if(initial!=null && initial.id!=proposed.id || initial==null && relations.any {it.id==proposed.id}) return RelationEditResult.Invalid
    if(proposed.id.isBlank() || proposed.id.length>128 || proposed.label.length>20_000 || proposed.from==proposed.to ||
        nodes.none {it.id==proposed.from} || nodes.none {it.id==proposed.to}) return RelationEditResult.Invalid
    relations.firstOrNull {it.id!=initial?.id && it.duplicateKey()==proposed.duplicateKey()}?.let {
        return RelationEditResult.Duplicate(it)
    }
    // Swapping the representation of the same undirected edge is not a content edit.
    val normalized=if(initial!=null && !initial.directed && !proposed.directed && initial.duplicateKey()==proposed.duplicateKey())
        proposed.copy(from=initial.from,to=initial.to) else proposed
    val next=if(initial==null) copy(relations=relations+normalized)
        else copy(relations=relations.map {if(it.id==initial.id) normalized else it})
    return try {next.validate();RelationEditResult.Applied(next)} catch(_:IllegalArgumentException) {RelationEditResult.Invalid}
}
