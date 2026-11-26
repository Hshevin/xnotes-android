package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.history.History
import com.xnotes.core.model.CutEdge
import com.xnotes.core.model.CutEdges
import com.xnotes.core.model.Stroke
import java.util.UUID

/**
 * Every edit of a canvas's mind maps.
 *
 * Each operation that changes [MapModel.state] pushes exactly one [Command] onto [history] holding
 * the state before and after; undo/redo swap the whole state back. An operation that would change
 * nothing pushes nothing and returns false/null. Every state an operation produces satisfies
 * [MapInvariants]. Operations given an id that does not exist change nothing.
 *
 * **Drafts.** A new node starts as a draft: [beginChild], [beginSibling] and [beginRoot] put an
 * untitled node into the model so it can be laid out and typed into, without touching history.
 * [commitDraft] then pushes one command whose "before" is the state from before the draft, so one
 * undo removes the node again. [cancelDraft] restores that state with nothing in history. While a
 * draft is open every other mutating operation throws [IllegalStateException]; only [draft],
 * [layout] and read access are allowed.
 *
 * [layout] is computed from the model's current state and collapsed set with [metrics], and is what
 * [addInk] uses to find a node's ink area on the canvas.
 */
class MapEditor(
    val model: MapModel,
    val history: History,
    val metrics: NodeMetrics,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** An open draft: the new node and the state to go back to on cancel. */
    class Draft internal constructor(val node: NodeId, internal val before: MapState)

    private var openDraft: Draft? = null
    val draft: Draft? get() = openDraft

    private var measuredState: MapState? = null
    private var measuredCollapsed: Set<NodeId>? = null
    private var measuredLayout: MapLayoutResult? = null

    /** The current layout. May be cached; must reflect the latest state and collapsed set. */
    fun layout(): MapLayoutResult {
        val state = model.state
        val collapsed = model.collapsed
        if (state === measuredState && collapsed === measuredCollapsed) return checkNotNull(measuredLayout)
        val result = MapLayout.compute(state, collapsed, metrics)
        measuredState = state
        measuredCollapsed = collapsed
        measuredLayout = result
        return result
    }

    private fun editableState(): MapState {
        check(openDraft == null) { "Finish or cancel the open node draft first" }
        return model.state
    }

    private fun record(before: MapState, after: MapState): Boolean {
        if (before == after) return false
        model.replace(after)
        history.push(MapStateCommand(model, before, after))
        return true
    }

    private inline fun editNode(id: NodeId, change: (MapNode) -> MapNode): Boolean {
        val state = editableState()
        val old = state.nodes[id] ?: return false
        val node = change(old)
        if (node == old) return false
        return record(state, state.copy(nodes = state.nodes + (id to node)))
    }

    // ---- creating nodes ----

    /**
     * Draft a new last child of [parent] with [NodeStyle.TOPIC]. If [parent] is collapsed it is
     * expanded first (outside history) so the draft is visible. Null when [parent] does not exist.
     */
    fun beginChild(parent: NodeId): Draft? {
        val state = editableState()
        if (parent !in state.nodes) return null
        return draftChild(state, parent, state.childrenOf(parent).size)
    }

    /** Draft a new sibling right after [of] with [NodeStyle.TOPIC]. Null when [of] is a root or does not exist. */
    fun beginSibling(of: NodeId): Draft? {
        val state = editableState()
        val parent = state.parent[of] ?: return null
        return draftChild(state, parent, state.childrenOf(parent).indexOf(of) + 1)
    }

    private fun draftChild(state: MapState, parent: NodeId, index: Int): Draft {
        val id = freshNodeId(state)
        val children = state.childrenOf(parent).toMutableList().apply { add(index, id) }
        val after = state.copy(
            nodes = state.nodes + (id to MapNode(id)),
            parent = state.parent + (id to parent),
            children = state.children + (parent to children),
        )
        return startDraft(id, state, after)
    }

    private fun freshNodeId(state: MapState): NodeId =
        generateSequence { NodeId(newId()) }.first { it !in state.nodes }

    /**
     * Draft a new root with its top-left at [at], [NodeStyle.ROOT], [LayoutKind.BALANCED], on canvas
     * layer [layer] (null = default layer, no [MapState.layers] entry). Appended to [MapState.roots].
     */
    fun beginRoot(at: Pt, layer: String? = null): Draft {
        val state = editableState()
        val id = freshNodeId(state)
        val after = state.copy(
            nodes = state.nodes + (id to MapNode(id, style = NodeStyle.ROOT)),
            roots = state.roots + id,
            rootOrigin = state.rootOrigin + (id to at),
            layout = state.layout + (id to LayoutKind.BALANCED),
            layers = if (layer == null) state.layers else state.layers + (id to layer),
        )
        return startDraft(id, state, after)
    }

    private fun startDraft(id: NodeId, before: MapState, after: MapState): Draft {
        val draft = Draft(id, before)
        openDraft = draft
        model.showDraft(after, id)
        return draft
    }

    /**
     * Finish the open draft with [title] (trimmed of leading/trailing whitespace). A blank title
     * cancels the draft unless [keepIfBlank] (used when the user starts writing into the new node
     * instead of typing). Returns the new node's id, or null when cancelled. Throws when no draft is open.
     */
    fun commitDraft(title: String, keepIfBlank: Boolean = false): NodeId? {
        val draft = checkNotNull(openDraft) { "No open node draft" }
        val text = title.trim()
        if (text.isEmpty() && !keepIfBlank) {
            cancelDraft()
            return null
        }
        val state = model.state
        val node = state.nodes.getValue(draft.node)
        val after = if (node.title == text) state else state.copy(nodes = state.nodes + (node.id to node.copy(title = text)))
        openDraft = null
        record(draft.before, after)
        return node.id
    }

    /** Drop the open draft; the state returns to before it with nothing in history. No-op without a draft. */
    fun cancelDraft() {
        val draft = openDraft ?: return
        openDraft = null
        model.replace(draft.before)
    }

    // ---- content ----

    /** Set [id]'s title (trimmed). False when unchanged. */
    fun setTitle(id: NodeId, title: String): Boolean = editNode(id) { it.copy(title = title.trim()) }

    /** Restyle each of [ids] with [change] in one command. False when no style actually changes. */
    fun updateStyle(ids: Collection<NodeId>, change: (NodeStyle) -> NodeStyle): Boolean {
        val state = editableState()
        val changed = HashMap<NodeId, MapNode>()
        for (id in ids.toSet()) {
            val node = state.nodes[id] ?: continue
            val style = change(node.style)
            if (style != node.style) changed[id] = node.copy(style = style)
        }
        return changed.isNotEmpty() && record(state, state.copy(nodes = state.nodes + changed))
    }

    /**
     * Write [stroke], given in canvas coordinates, into [id] in one command. The whole node box is
     * writable, title and padding included; ink never shows past the box.
     *
     * - Strokes are stored in area-local coordinates: the origin is [NodeFrame.ink]'s top-left from
     *   [layout] and [metrics]; for a node without an ink area, where the area will start (below the
     *   title line, or at the content top when no title line is shown), at `left + PAD_X`. Ink over
     *   the title or in the padding has negative or out-of-area coordinates.
     * - The stored stroke is an independent copy translated into those coordinates; [stroke] itself is
     *   not modified. Every raw sample keeps its pressure, time and other fields; only x and y move.
     *   Nothing is shifted and no sample is dropped: the copy is clipped by adding edges to its
     *   [com.xnotes.core.model.Stroke.cuts] ([com.xnotes.core.model.CutEdges.with]). Strokes already
     *   in the node are never moved, cut again or replaced.
     * - **A node that has an ink area** keeps its size: the copy is cut at the box's four edges. When
     *   nothing of it shows ([com.xnotes.core.model.Stroke.showsInk] false) nothing changes and it
     *   returns false.
     * - **The first stroke** (no ink area yet) sizes the area. The copy is cut at the current box's
     *   left and top edges, and also its right edge when the node is resized ([MapNode.width] set).
     *   With b the cut copy's bounds, the area is height = max(b.bottom + [INK_MARGIN], [INK_MIN_H])
     *   and, for a node sized to its content, width = max(b.right + [INK_MARGIN], [INK_MIN_W], the
     *   inner width the title gives); a resized node keeps its inner width. The copy is then also
     *   cut at the new box's edges.
     *
     * Returns false when [id] does not exist.
     */
    fun addInk(id: NodeId, stroke: Stroke): Boolean = editNode(id) { node ->
        val at = layout().rectOf(id)?.topLeft ?: return@editNode node
        val isRoot = id !in model.state.parent
        val ink = node.ink
        val local = stroke.inkCopy()

        if (!ink.isEmpty) {
            val frame = metrics.frame(node, isRoot, at)
            val area = checkNotNull(frame.ink)
            local.translate(-area.left, -area.top)
            local.cutTo(frame.rect.translate(-area.left, -area.top))
            if (!local.showsInk()) return@editNode node
            // The area keeps at least the width the ink was cut at, so a title shortened later cannot narrow it under the ink.
            return@editNode node.copy(ink = ink.copy(width = maxOf(ink.width, area.w), strokes = ink.strokes + local))
        }

        val shown = metrics.frame(node, isRoot, at).rect
        val area = checkNotNull(metrics.frame(node.copy(ink = NodeInk(INK_MIN_W, INK_MIN_H, emptyList())), isRoot, at).ink)
        local.translate(-area.left, -area.top)
        val box = shown.translate(-area.left, -area.top)
        local.cuts = CutEdges.with(local.cuts, CutEdge(box.left, 0.0, -1.0, 0.0))
        local.cuts = CutEdges.with(local.cuts, CutEdge(0.0, box.top, 0.0, -1.0))
        if (node.width != null) local.cuts = CutEdges.with(local.cuts, CutEdge(box.right, 0.0, 1.0, 0.0))
        if (!local.showsInk()) return@editNode node
        val b = local.bounds()
        val width = if (node.width == null) maxOf(b.right + INK_MARGIN, INK_MIN_W, area.w) else area.w
        val height = maxOf(b.bottom + INK_MARGIN, INK_MIN_H)
        val sized = node.copy(ink = NodeInk(width, height, emptyList()))
        val frame = metrics.frame(sized, isRoot, at)
        local.cutTo(frame.rect.translate(-area.left, -area.top))
        sized.copy(ink = NodeInk(width, height, listOf(local)))
    }

    /** Cut this stroke at [r]'s four edges. */
    private fun Stroke.cutTo(r: Rect) {
        for (edge in listOf(
            CutEdge(r.left, 0.0, -1.0, 0.0), CutEdge(r.right, 0.0, 1.0, 0.0),
            CutEdge(0.0, r.top, 0.0, -1.0), CutEdge(0.0, r.bottom, 0.0, 1.0),
        )) cuts = CutEdges.with(cuts, edge)
    }

    private fun Stroke.inkCopy(): Stroke = copyForWrite().also {
        it.layerId = layerId
        it.locked = locked
        it.sync = sync
        it.group = group
    }

    /**
     * Replace ink strokes of [id] in one command: each of [removed] (matched by identity) is taken out
     * and [added] (already area-local, e.g. eraser fragments) are appended. The area keeps its size.
     * False when nothing in [removed] belongs to the node and [added] is empty.
     */
    fun replaceInk(id: NodeId, removed: List<Stroke>, added: List<Stroke>): Boolean = editNode(id) { node ->
        val remove = removed.toHashSet() // Stroke uses object identity.
        node.copy(ink = node.ink.copy(strokes = node.ink.strokes.filterNot { it in remove } + added))
    }

    /** Remove [id]'s ink area and all its strokes. False when it has none. */
    fun removeInkArea(id: NodeId): Boolean = editNode(id) { it.copy(ink = NodeInk.NONE) }

    /**
     * Resize [id]'s box toward [target] (canvas units) in one command, as [NodeMetrics.resized]
     * decides. False when [id] does not exist or nothing would change.
     */
    fun resize(id: NodeId, target: Size): Boolean = editNode(id) { node ->
        metrics.resized(node, id !in model.state.parent, target)
    }

    // ---- sources ----

    /** Append [refs] to [id]'s sources, skipping any whose (kind, documentId, pageId) the node already has. False when nothing is added. */
    fun addSources(id: NodeId, refs: List<SourceRef>): Boolean = editNode(id) { node ->
        fun target(ref: SourceRef) = Triple(ref.kind, ref.documentId, ref.pageId)
        val seen = node.sources.mapTo(HashSet(), ::target)
        node.copy(sources = node.sources + refs.filter { seen.add(target(it)) })
    }

    fun removeSource(id: NodeId, sourceId: String): Boolean = editNode(id) { node ->
        node.copy(sources = node.sources.filterNot { it.id == sourceId })
    }

    /** Move a source to [toIndex] (clamped) in [id]'s list. False when unchanged. */
    fun moveSource(id: NodeId, sourceId: String, toIndex: Int): Boolean = editNode(id) { node ->
        val from = node.sources.indexOfFirst { it.id == sourceId }
        if (from < 0) return@editNode node
        val to = toIndex.coerceIn(node.sources.indices)
        if (from == to) node else node.copy(sources = node.sources.toMutableList().apply { add(to, removeAt(from)) })
    }

    // ---- structure ----

    /**
     * Delete [ids], each with its whole subtree, plus every relation touching a deleted node, in one
     * command. Ids that are descendants of other ids in the set are covered once. False when none exist.
     */
    fun delete(ids: Collection<NodeId>): Boolean {
        val state = editableState()
        return record(state, state.withoutSubtrees(ids))
    }

    /**
     * Move [id] (with its subtree) to be [newParent]'s child at [index] (counted after removing [id]
     * from its current place; clamped). Clears [id]'s own offset; its descendants keep theirs. If [id]
     * was a root, its origin, layout and layer entries go. False when [newParent] is [id] or one of its
     * descendants, when either does not exist, or when nothing changes.
     */
    fun reparent(id: NodeId, newParent: NodeId, index: Int): Boolean {
        val state = editableState()
        if (id !in state.nodes || newParent !in state.nodes) return false
        var ancestor: NodeId? = newParent
        while (ancestor != null) {
            if (ancestor == id) return false
            ancestor = state.parent[ancestor]
        }
        val oldParent = state.parent[id]
        val children = state.children.toMutableMap()
        if (oldParent != null) children[oldParent] = state.childrenOf(oldParent) - id
        val siblings = children[newParent].orEmpty().toMutableList()
        siblings.add(index.coerceIn(0, siblings.size), id)
        children[newParent] = siblings
        return record(state, state.copy(
            roots = if (oldParent == null) state.roots - id else state.roots,
            parent = state.parent + (id to newParent),
            children = children,
            offsets = state.offsets - id,
            rootOrigin = if (oldParent == null) state.rootOrigin - id else state.rootOrigin,
            layout = if (oldParent == null) state.layout - id else state.layout,
            layers = if (oldParent == null) state.layers - id else state.layers,
        ))
    }

    /** Move a child by [delta] (its offset grows; a resulting zero offset is removed) or a root's origin by [delta]. False for a zero delta. */
    fun nudge(id: NodeId, delta: Vec): Boolean {
        val state = editableState()
        return record(state, state.nudged(id, delta))
    }

    /** Apply a [DropAction] from [MapHit.drop] to [id]. */
    fun applyDrop(id: NodeId, action: DropAction): Boolean = when (action) {
        is DropAction.Reparent -> reparent(id, action.parent, action.index)
        is DropAction.Nudge -> nudge(id, action.delta)
        DropAction.None -> {
            editableState()
            false
        }
    }

    /**
     * Make child [id] a root with its top-left at [at], keeping its subtree. Its tree's layout kind and
     * layer are copied from its old root; its own offset is removed. Appended to [MapState.roots].
     * False when [id] is already a root or does not exist.
     */
    fun detach(id: NodeId, at: Pt): Boolean {
        val state = editableState()
        val parent = state.parent[id] ?: return false
        val oldRoot = state.rootOf(parent)
        val layer = state.layers[oldRoot]
        return record(state, state.copy(
            roots = state.roots + id,
            parent = state.parent - id,
            children = state.children + (parent to (state.childrenOf(parent) - id)),
            offsets = state.offsets - id,
            rootOrigin = state.rootOrigin + (id to at),
            layout = state.layout + (id to state.layout.getValue(oldRoot)),
            layers = if (layer == null) state.layers else state.layers + (id to layer),
        ))
    }

    /** Remove the offsets of [ids] (not their descendants'). False when none had one. */
    fun resetOffsets(ids: Collection<NodeId>): Boolean {
        val state = editableState()
        if (ids.none { it in state.offsets }) return false
        return record(state, state.copy(offsets = state.offsets - ids))
    }

    /** Remove every offset in [root]'s tree. False when there were none. */
    fun resetTree(root: NodeId): Boolean {
        val state = editableState()
        if (root !in state.layout || state.offsets.isEmpty()) return false
        return record(state, state.copy(offsets = state.offsets - state.subtreeIds(listOf(root))))
    }

    /** Set [root]'s layout kind and, in the same command, clear every offset in its tree. False when [root] is not a root or already has [kind]. */
    fun setLayout(root: NodeId, kind: LayoutKind): Boolean {
        val state = editableState()
        val old = state.layout[root] ?: return false
        if (kind == old) return false
        val offsets = if (state.offsets.isEmpty()) state.offsets else state.offsets - state.subtreeIds(listOf(root))
        return record(state, state.copy(layout = state.layout + (root to kind), offsets = offsets))
    }

    // ---- relations ----

    /**
     * Connect [a] and [b] with a new relation (no arrow, empty label), or return the existing relation
     * between them in either direction without touching history. Null when a == b or either does not exist.
     */
    fun addRelation(a: NodeId, b: NodeId): RelationId? {
        val state = editableState()
        if (a == b || a !in state.nodes || b !in state.nodes) return null
        val existing = state.relations.values.firstOrNull { (it.from == a && it.to == b) || (it.from == b && it.to == a) }
        if (existing != null) return existing.id
        val id = generateSequence { RelationId(newId()) }.first { it !in state.relations }
        record(state, state.copy(relations = state.relations + (id to Relation(id, a, b))))
        return id
    }

    /** Change a relation's label, arrow or colour (null = keep). False when unchanged. */
    fun updateRelation(
        id: RelationId,
        label: String? = null,
        arrow: RelationArrow? = null,
        color: com.xnotes.core.model.Rgba? = null,
    ): Boolean = editRelation(id) {
        it.copy(label = label ?: it.label, arrow = arrow ?: it.arrow, color = color ?: it.color)
    }

    /** Swap a relation's ends; FORWARD and BACKWARD swap too so the arrows keep pointing at the same nodes. */
    fun reverseRelation(id: RelationId): Boolean = editRelation(id) {
        val arrow = when (it.arrow) {
            RelationArrow.FORWARD -> RelationArrow.BACKWARD
            RelationArrow.BACKWARD -> RelationArrow.FORWARD
            else -> it.arrow
        }
        it.copy(from = it.to, to = it.from, arrow = arrow)
    }

    private inline fun editRelation(id: RelationId, change: (Relation) -> Relation): Boolean {
        val state = editableState()
        val old = state.relations[id] ?: return false
        val relation = change(old)
        if (relation == old) return false
        return record(state, state.copy(relations = state.relations + (id to relation)))
    }

    fun deleteRelations(ids: Collection<RelationId>): Boolean {
        val state = editableState()
        if (ids.none { it in state.relations }) return false
        return record(state, state.copy(relations = state.relations - ids))
    }

    // ---- clipboard ----

    /**
     * Add copies of [fragment]'s trees as new roots, with fresh node, relation and source ids, the
     * fragment's arrangement kept and its bounding box's top-left placed at [at], on layer [layer], in
     * one command. Returns the new roots in fragment order (empty for an empty fragment, no command).
     */
    fun paste(fragment: MapFragment, at: Pt, layer: String? = null): List<NodeId> {
        val state = editableState()
        if (fragment.isEmpty) return emptyList()
        val source = fragment.state
        val used = HashSet<String>()
        for (map in listOf(state, source)) {
            for (node in map.nodes.values) {
                used.add(node.id.value)
                node.sources.mapTo(used) { it.id }
            }
            map.relations.keys.mapTo(used) { it.value }
        }
        fun freshId(): String = generateSequence(newId).first(used::add)
        val ids = source.nodes.keys.associateWith { NodeId(freshId()) }
        val nodes = source.nodes.values.associate { node ->
            val id = ids.getValue(node.id)
            val ink = node.ink.copy(strokes = node.ink.strokes.map { it.inkCopy() })
            id to node.copy(id = id, ink = ink, sources = node.sources.map { it.copy(id = freshId()) })
        }
        val relations = source.relations.values.associate { relation ->
            val id = RelationId(freshId())
            id to relation.copy(id = id, from = ids.getValue(relation.from), to = ids.getValue(relation.to))
        }
        // Hidden-only selections have no visible bounds. Their recorded ancestor origins still
        // preserve the spacing of multiple trees.
        val anchor = fragment.bounds?.topLeft ?: Pt(
            source.rootOrigin.values.minOf { it.x }, source.rootOrigin.values.minOf { it.y },
        )
        val shift = at - anchor
        val roots = source.roots.map(ids::getValue)
        val after = state.copy(
            nodes = state.nodes + nodes,
            roots = state.roots + roots,
            parent = state.parent + source.parent.map { (id, parent) -> ids.getValue(id) to ids.getValue(parent) },
            children = state.children + source.children.map { (id, children) -> ids.getValue(id) to children.map(ids::getValue) },
            rootOrigin = state.rootOrigin + source.rootOrigin.map { (id, origin) -> ids.getValue(id) to origin + shift },
            layout = state.layout + source.layout.mapKeys { ids.getValue(it.key) },
            offsets = state.offsets + source.offsets.mapKeys { ids.getValue(it.key) },
            layers = if (layer == null) state.layers else state.layers + roots.associateWith { layer },
            relations = state.relations + relations,
        )
        record(state, after)
        return roots
    }

    companion object {
        const val INK_MARGIN = 16.0
        const val INK_MIN_W = 160.0
        const val INK_MIN_H = 80.0
    }
}

/** The command every [MapEditor] operation pushes. */
class MapStateCommand(
    private val model: MapModel,
    val before: MapState,
    val after: MapState,
) : Command {
    override fun redo() = model.replace(after)
    override fun undo() = model.replace(before)
}
