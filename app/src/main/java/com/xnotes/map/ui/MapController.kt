package com.xnotes.map.ui

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.map.DropAction
import com.xnotes.map.GrowSide
import com.xnotes.map.LayoutKind
import com.xnotes.map.MapClipboard
import com.xnotes.map.MapEditor
import com.xnotes.map.MapFragment
import com.xnotes.map.MapHit
import com.xnotes.map.MapLayout
import com.xnotes.map.MapLayoutResult
import com.xnotes.map.MapModel
import com.xnotes.map.MapState
import com.xnotes.map.NodeId
import com.xnotes.map.NodeMetrics
import com.xnotes.map.NodeShape
import com.xnotes.map.NodeStyle
import com.xnotes.map.RelationArrow
import com.xnotes.map.RelationId
import com.xnotes.map.Vec
import com.xnotes.map.nudged
import kotlin.math.min

/**
 * The canvas's mind maps as the person works on them: what is selected, the title being typed, a
 * node being dragged, a relation line being drawn. Everything that changes content goes through
 * [editor], one undo step each; everything here besides is view state that never enters history.
 *
 * All coordinates are canvas content coordinates. The host turns touches into the calls below and
 * draws [chrome]; [projection] keeps the scene's meshes in step through [sink].
 */
class MapController(
    val editor: MapEditor,
    private val projection: MapProjection,
    private val sink: MapSceneSink,
    private val host: Host,
) {

    interface Host {
        /** Whether a tree on [layer] (null = default) is drawn. */
        fun layerVisible(layer: String?): Boolean

        /** Whether a tree on [layer] may be edited: it is the active, unlocked, shown layer. */
        fun layerEditable(layer: String?): Boolean

        /** The layer a new tree goes on. */
        fun activeLayer(): String?

        /** Whether the canvas shows night paper, which content colours are drawn against. */
        fun night(): Boolean = false

        /** The canvas layer a tree stored on [layer] is drawn in (an unknown one falls back). */
        fun drawingLayer(layer: String?): String? = layer

        /** Something visible changed: the scene, the chrome, the menu or the title field. */
        fun mapChanged()

        /** A source chip of [node] was tapped: [sourceId], or null for the "+N" chip. */
        fun sourceTapped(node: NodeId, sourceId: String?) {}
    }

    /** A "+" handle beside the selected node. */
    enum class HandleKind { CHILD, SIBLING }

    class Handle(val kind: HandleKind, val center: Pt, val radius: Double)

    /** What the chrome layer draws over the map, in content coordinates. */
    class Chrome(
        val outline: Rect?,
        val handles: List<Handle>,
        /** The node a drop would put the dragged one under, outlined. */
        val dropTarget: Rect?,
        /** A relation line being drawn, from its first node to the pointer. */
        val relationLine: Pair<Pt, Pt>?,
        /** The selected relation line's ends. */
        val relation: Pair<Pt, Pt>?,
    )

    /** The title field over a node: where it sits, what it holds, and how it sets. */
    data class TitleField(val node: NodeId, val rect: Rect, val text: String, val style: NodeStyle, val isDraft: Boolean)

    private val model: MapModel get() = editor.model
    private val metrics: NodeMetrics get() = editor.metrics

    var selected: NodeId? = null
        private set
    var selectedRelation: RelationId? = null
        private set

    private var editing: Editing? = null

    private class Editing(val node: NodeId, var text: String, val isDraft: Boolean)

    private sealed interface Press {
        val at: Pt
        class OnNode(val node: NodeId, override val at: Pt) : Press
        class OnHandle(val node: NodeId, val kind: HandleKind, override val at: Pt) : Press
        class OnRelation(val id: RelationId, override val at: Pt) : Press
        class Relating(val from: NodeId, override val at: Pt) : Press
    }

    private var press: Press? = null
    private var dragging = false
    private var pointer = Pt.ZERO
    private var drop: DropAction = DropAction.None
    private var dragDelta = Vec.ZERO
    private var relationTarget: NodeId? = null

    /** The layout drawn last: of the model, or of the drag or typing preview over it. */
    var shown: MapLayoutResult = MapLayoutResult(emptyMap(), emptyList(), emptySet(), emptyMap())
        private set
    private var shownState: MapState = MapState.EMPTY

    private val listener = MapModel.Listener { onModelChanged() }

    init {
        model.addListener(listener)
        // Not announced: the host is still making this controller and cannot ask it anything yet.
        refresh(announce = false)
    }

    /** The scene was reset: put every part back. */
    fun reproject() {
        projection.forget()
        refresh()
    }

    /** Stop listening and take the map off the scene, as when the canvas's document is replaced. */
    fun dispose() {
        abandonGesture()
        if (editor.draft != null) editor.cancelDraft()
        model.removeListener(listener)
        projection.clear(sink)
    }

    private fun onModelChanged() {
        val state = model.state
        if (selected?.let { it !in state.nodes } == true) selected = null
        if (selectedRelation?.let { it !in state.relations } == true) selectedRelation = null
        editing?.let { e -> if (e.node !in state.nodes) editing = null }
        press?.let { p ->
            val gone = when (p) {
                is Press.OnNode -> p.node !in state.nodes
                is Press.OnHandle -> p.node !in state.nodes
                is Press.Relating -> p.from !in state.nodes
                is Press.OnRelation -> p.id !in state.relations
            }
            if (gone) abandonGesture()
        }
        refresh()
    }

    /** Re-lay and re-project what is shown, then tell the host (unless not to [announce] it). */
    fun refresh(announce: Boolean = true) {
        val base = model.state
        var state = base
        editing?.let { e ->
            state.nodes[e.node]?.let { n -> state = state.copy(nodes = state.nodes + (e.node to n.copy(title = e.text))) }
        }
        val p = press
        if (dragging && p is Press.OnNode) state = state.nudged(p.node, dragDelta)
        shownState = state
        shown = if (state === base) editor.layout() else MapLayout.compute(state, model.collapsed, metrics)
        projection.update(state, shown, sink, untitled = editing?.node, night = host.night(), layerFor = host::drawingLayer)
        if (announce) host.mapChanged()
    }

    /** The parts to stack into the scene, layered by their trees' layers. */
    val parts: List<MapPart> get() = projection.items

    // ---- hit testing ----

    private fun layerOf(id: NodeId): String? = shownState.layers[shownState.rootOf(id)]

    /** The visible node at [p] on an editable layer, topmost first. */
    fun nodeAt(p: Pt): NodeId? {
        val id = MapHit.nodeAt(shownState, shown, p) ?: return null
        return id.takeIf { host.layerEditable(layerOf(it)) }
    }

    /** The node a pen stroke starting at [p] writes into: an editable node whose box holds [p]. */
    fun inkTarget(p: Pt): NodeId? = nodeAt(p)

    fun handleAt(p: Pt, zoom: Double, touchPx: Double): Handle? =
        handles(zoom, touchPx).firstOrNull { it.center.distanceTo(p) <= it.radius }

    private fun relationAt(p: Pt, reach: Double): RelationId? =
        projection.relationAt(p, reach)?.takeIf { id ->
            shownState.relations[id]?.let { host.layerEditable(layerOf(it.from)) } == true
        }

    // ---- gestures ----

    /**
     * A pointer went down at [p]. Returns true when the map takes the whole gesture; false leaves it
     * to the canvas (to pan, draw, erase as it would).
     *
     * - A "+" handle of the selected node is taken by any pointer but the eraser.
     * - A node is taken by a finger under any tool (unless finger drawing is on and the tool is
     *   neither the map nor the select tool), and by a pen under the map, select and pan tools.
     * - With the connector tool armed, a press on a node starts a relation line.
     * - A relation line is taken where a node would be.
     */
    fun press(p: Pt, zoom: Double, touchPx: Double, finger: Boolean, eraser: Boolean, tool: Tool, connector: Boolean, fingerDraws: Boolean): Boolean {
        abandonGesture()
        if (eraser) return false
        // A node under the press beats a handle whose target spills over it.
        handleAt(p, zoom, touchPx)?.takeIf { shown.placements.values.none { it.rect.contains(p) } }?.let { h ->
            press = Press.OnHandle(checkNotNull(selected), h.kind, p)
            return true
        }
        val grabs = if (finger) !fingerDraws || tool == Tool.MAP || tool == Tool.SELECT
        else tool == Tool.MAP || tool == Tool.SELECT || tool == Tool.PAN
        val node = nodeAt(p)
        if (node != null && connector && (!finger || fingerDraws || grabs)) {
            commitTitle()
            press = Press.Relating(node, p)
            pointer = p
            relationTarget = null
            refreshChrome()
            return true
        }
        if (!grabs) return false
        if (node != null) {
            press = Press.OnNode(node, p)
            return true
        }
        relationAt(p, RELATION_REACH_PX / zoom)?.let { id ->
            press = Press.OnRelation(id, p)
            return true
        }
        return false
    }

    /** The pointer of a taken gesture moved to [p]. */
    fun move(p: Pt, zoom: Double) {
        val pr = press ?: return
        pointer = p
        when (pr) {
            is Press.OnNode -> {
                if (!dragging && pr.at.distanceTo(p) * zoom < DRAG_SLOP_PX) return
                if (!dragging) {
                    commitTitle()
                    if (editor.draft != null) return
                    dragging = true
                    select(pr.node)
                }
                dragDelta = Vec(p.x - pr.at.x, p.y - pr.at.y)
                drop = MapHit.drop(model.state, editor.layout(), pr.node, p, dragDelta)
                refresh()
            }
            is Press.Relating -> {
                relationTarget = nodeAt(p)?.takeIf { it != pr.from }
                refreshChrome()
            }
            else -> Unit
        }
    }

    /** The pointer of a taken gesture lifted at [p]. */
    fun release(p: Pt) {
        val pr = press ?: return
        press = null
        when (pr) {
            is Press.OnHandle -> {
                commitTitle()
                if (editor.draft == null) when (pr.kind) {
                    HandleKind.CHILD -> editor.beginChild(pr.node)
                    HandleKind.SIBLING -> editor.beginSibling(pr.node)
                }?.let { startEditing(it.node, draft = true) }
            }
            is Press.OnNode -> if (dragging) {
                dragging = false
                val action = drop
                drop = DropAction.None
                dragDelta = Vec.ZERO
                editor.applyDrop(pr.node, action)
                refresh()
            } else {
                val chip = projection.chipAt(p)?.takeIf { it.first == pr.node }
                if (chip != null) {
                    commitTitle()
                    select(pr.node)
                    host.sourceTapped(pr.node, chip.second)
                } else if (selected == pr.node && editing == null) editTitle(pr.node)
                else {
                    commitTitle()
                    select(pr.node)
                }
            }
            is Press.OnRelation -> {
                commitTitle()
                selectRelation(pr.id)
            }
            is Press.Relating -> {
                val target = nodeAt(p)?.takeIf { it != pr.from }
                relationTarget = null
                if (target != null) editor.addRelation(pr.from, target)?.let { selectRelation(it) } else refreshChrome()
            }
        }
    }

    /** Drop the taken gesture without changing anything. */
    fun cancel() {
        abandonGesture()
        refresh()
    }

    private fun abandonGesture() {
        press = null
        relationTarget = null
        if (dragging) {
            dragging = false
            drop = DropAction.None
            dragDelta = Vec.ZERO
        }
    }

    val gestureActive: Boolean get() = press != null

    /**
     * A tap on empty canvas that the canvas did not use. Ends an edit or clears the selection; with
     * the map tool and nothing to put away, starts a new central topic there.
     */
    fun tapBlank(p: Pt, mapTool: Boolean): Boolean {
        val hadSomething = editing != null || selected != null || selectedRelation != null
        if (hadSomething) {
            commitTitle()
            clearSelection()
            return true
        }
        if (!mapTool || !host.layerEditable(host.activeLayer())) return false
        val draft = editor.beginRoot(Pt(p.x - NodeMetrics.ROOT_MIN_W / 2, p.y - NodeStyle.ROOT.textSize), host.activeLayer())
        startEditing(draft.node, draft = true)
        return true
    }

    // ---- selection ----

    fun select(id: NodeId?) {
        if (selected == id && selectedRelation == null) return
        selected = id
        selectedRelation = null
        host.mapChanged()
    }

    private fun selectRelation(id: RelationId) {
        selected = null
        selectedRelation = id
        host.mapChanged()
    }

    fun clearSelection() {
        if (selected == null && selectedRelation == null) return
        selected = null
        selectedRelation = null
        host.mapChanged()
    }

    val hasSelection: Boolean get() = selected != null || selectedRelation != null

    // ---- titles ----

    /** The field to show over the node being typed into, in content coordinates. */
    val titleField: TitleField?
        get() {
            val e = editing ?: return null
            val node = shownState.nodes[e.node] ?: return null
            val rect = shown.rectOf(e.node) ?: return null
            val frame = metrics.frame(node, e.node !in shownState.parent, rect.topLeft)
            val title = frame.title ?: Rect(rect.left + NodeMetrics.PAD_X, rect.top + NodeMetrics.PAD_Y, rect.w - 2 * NodeMetrics.PAD_X, node.style.textSize)
            return TitleField(e.node, title, e.text, node.style, e.isDraft)
        }

    fun editTitle(id: NodeId) {
        commitTitle()
        if (editor.draft != null || id !in model.state.nodes) return
        startEditing(id, draft = false)
    }

    private fun startEditing(id: NodeId, draft: Boolean) {
        selected = id
        selectedRelation = null
        editing = Editing(id, model.state.nodes[id]?.title.orEmpty(), draft)
        refresh()
    }

    /** The field's text changed. */
    fun typing(text: String) {
        val e = editing ?: return
        if (e.text == text) return
        e.text = text
        refresh()
    }

    /**
     * End the edit: a new node with no words is not made; an existing node takes the words as one
     * undo step. [keepBlank] keeps a new node even without words, as when the pen writes into it.
     */
    fun commitTitle(keepBlank: Boolean = false) {
        val e = editing ?: return
        editing = null
        if (e.isDraft) {
            if (editor.draft?.node == e.node) {
                val id = editor.commitDraft(e.text, keepIfBlank = keepBlank)
                selected = id
            }
        } else {
            editor.setTitle(e.node, e.text)
        }
        refresh()
    }

    /** Drop the edit: a new node goes, an existing one keeps its old title. */
    fun cancelTitle() {
        val e = editing ?: return
        editing = null
        if (e.isDraft && editor.draft?.node == e.node) {
            editor.cancelDraft()
            selected = null
        }
        refresh()
    }

    val isEditingTitle: Boolean get() = editing != null

    /** Finish the edit and draft a new child (Tab) or sibling (Enter) of the node, typing into it. */
    fun commitAndAdd(kind: HandleKind) {
        val id = editing?.node ?: selected ?: return
        commitTitle()
        if (editor.draft != null || id !in model.state.nodes) return
        val draft = when (kind) {
            HandleKind.CHILD -> editor.beginChild(id)
            HandleKind.SIBLING -> editor.beginSibling(id) ?: editor.beginChild(id)
        } ?: return
        startEditing(draft.node, draft = true)
    }

    // ---- ink ----

    /**
     * A finished pen stroke that started in node [id]: write it into the node's ink area. A node
     * still being created is kept, words or not, so it can hold the writing.
     */
    fun writeInk(id: NodeId, stroke: Stroke): Boolean {
        val e = editing
        if (e != null) {
            if (e.node == id) commitTitle(keepBlank = true) else commitTitle()
        }
        if (editor.draft != null || id !in model.state.nodes) return false
        return editor.addInk(id, stroke)
    }

    /** The ink strokes of visible editable nodes whose drawn copies the eraser circle touches. */
    fun erasable(cx: Double, cy: Double, radius: Double): Map<NodeId, List<Stroke>> {
        val out = HashMap<NodeId, List<Stroke>>()
        val circle = Rect(cx - radius, cy - radius, 2 * radius, 2 * radius)
        for ((id, placement) in shown.placements) {
            if (!placement.rect.intersects(circle) || !host.layerEditable(layerOf(id))) continue
            val node = shownState.nodes[id] ?: continue
            val area = metrics.frame(node, id !in shownState.parent, placement.rect.topLeft).ink ?: continue
            val hit = node.ink.strokes.filter { it.intersectsCircle(cx - area.left, cy - area.top, radius) }
            if (hit.isNotEmpty()) out[id] = hit
        }
        return out
    }

    // ---- menu actions on the selection ----

    val selectedHasChildren: Boolean get() = selected?.let { model.state.childrenOf(it).isNotEmpty() } == true
    val selectedCollapsed: Boolean get() = selected?.let { it in model.collapsed } == true
    val selectedIsRoot: Boolean get() = selected?.let { it in model.state.rootOrigin } == true
    val selectedHasOffset: Boolean get() = selected?.let { it in model.state.offsets } == true
    val selectedTreeHasOffsets: Boolean
        get() = selected?.let { s -> val root = model.state.rootOf(s); model.state.offsets.keys.any { model.state.rootOf(it) == root } } == true
    val selectedLayout: LayoutKind? get() = selected?.let { model.state.layout[model.state.rootOf(it)] }
    /** The selected node's sources, in order; empty when none is selected. */
    val selectedSources: List<com.xnotes.map.SourceRef>
        get() = selected?.let { shownState.nodes[it]?.sources }.orEmpty()

    /**
     * Add [refs] to the selected node as one undoable step, skipping any it already links to.
     * False when nothing was added (no selection, a locked layer, or all were there already).
     */
    fun addSources(refs: List<com.xnotes.map.SourceRef>): Boolean {
        val id = selected ?: return false
        if (!host.layerEditable(layerOf(id))) return false
        commitTitle()
        return editor.addSources(id, refs)
    }

    /** Remove one source from the selected node as one undoable step. */
    fun removeSource(sourceId: String): Boolean {
        val id = selected ?: return false
        if (!host.layerEditable(layerOf(id))) return false
        return editor.removeSource(id, sourceId)
    }

    val selectedStyle: NodeStyle? get() = selected?.let { model.state.nodes[it]?.style }
    val selectedArrow: RelationArrow? get() = selectedRelation?.let { model.state.relations[it]?.arrow }
    val selectedRelationLabel: String? get() = selectedRelation?.let { model.state.relations[it]?.label }

    private inline fun onSelected(action: (NodeId) -> Unit) {
        commitTitle()
        if (editor.draft != null) return
        selected?.let(action)
    }

    fun toggleCollapsed() = onSelected { model.setCollapsed(it, it !in model.collapsed) }
    fun setLayout(kind: LayoutKind) = onSelected { editor.setLayout(model.state.rootOf(it), kind) }
    fun resetPosition() = onSelected { editor.resetOffsets(listOf(it)) }
    fun resetTree() = onSelected { editor.resetTree(model.state.rootOf(it)) }
    fun detachSelected() = onSelected { id ->
        val rect = shown.rectOf(id) ?: return@onSelected
        editor.detach(id, rect.topLeft)
    }
    fun deleteSelected() {
        commitTitle()
        if (editor.draft != null) return
        selected?.let { editor.delete(listOf(it)) }
        selectedRelation?.let { editor.deleteRelations(listOf(it)) }
    }

    fun restyle(change: (NodeStyle) -> NodeStyle) = onSelected { editor.updateStyle(listOf(it)) { s -> change(s) } }
    fun setShape(shape: NodeShape) = restyle { it.copy(shape = shape) }
    fun setBorder(color: Rgba) = restyle { it.copy(border = color) }
    fun setFill(color: Rgba?) = restyle { it.copy(fill = color) }
    fun setTextColor(color: Rgba) = restyle { it.copy(text = color) }
    fun setBold(bold: Boolean) = restyle { it.copy(bold = bold) }
    fun setTextSize(size: Double) = restyle { it.copy(textSize = size) }

    fun setArrow(arrow: RelationArrow) { selectedRelation?.let { editor.updateRelation(it, arrow = arrow) } }
    fun reverseRelation() { selectedRelation?.let { editor.reverseRelation(it) } }
    fun setRelationLabel(text: String) { selectedRelation?.let { editor.updateRelation(it, label = text.trim()) } }
    fun setRelationColor(color: Rgba) { selectedRelation?.let { editor.updateRelation(it, color = color) } }

    /** The selected node's subtree as a fragment, for the clipboard. */
    fun copySelected(): MapFragment? {
        commitTitle()
        val id = selected ?: return null
        return MapClipboard.copy(model.state, editor.layout(), listOf(id))
    }

    /** Paste [fragment] with its top-left at [at] on the active layer, selecting the first new tree. */
    fun paste(fragment: MapFragment, at: Pt): Boolean {
        commitTitle()
        if (editor.draft != null || !host.layerEditable(host.activeLayer())) return false
        val roots = editor.paste(fragment, at, host.activeLayer())
        roots.firstOrNull()?.let(::select)
        return roots.isNotEmpty()
    }

    /** Before undo or redo: no draft or field may outlive the state it was typed over. */
    fun settleForHistory() {
        abandonGesture()
        commitTitle()
    }

    // ---- chrome ----

    /** The selected node's box on the canvas. */
    val selectedRect: Rect? get() = selected?.let(shown::rectOf)

    /** The selected relation line's bounding box on the canvas. */
    val selectedRelationRect: Rect?
        get() = selectedRelation?.let { id ->
            val r = shownState.relations[id] ?: return null
            val a = shown.rectOf(r.from) ?: return null
            val b = shown.rectOf(r.to) ?: return null
            MapProjection.relationEnds(a, b)?.let { (p, q) -> Rect.fromPoints(p, q) }
        }

    fun chrome(zoom: Double, touchPx: Double): Chrome? {
        // While a title is typed the node's own box and the caret mark it; an outline would frame it twice.
        val outline = selected?.takeIf { editing == null }?.let(shown::rectOf)
        val pr = press
        val relationLine = (pr as? Press.Relating)?.let { r ->
            val from = shown.rectOf(r.from) ?: return@let null
            val to = relationTarget?.let(shown::rectOf)
            if (to != null) MapProjection.relationEnds(from, to) else from.center to pointer
        }
        val target = (drop as? DropAction.Reparent)?.takeIf { dragging }?.parent?.let(shown::rectOf)
            ?: relationTarget?.let(shown::rectOf)
        val relation = selectedRelation?.let { id ->
            val r = shownState.relations[id] ?: return@let null
            val a = shown.rectOf(r.from) ?: return@let null
            val b = shown.rectOf(r.to) ?: return@let null
            MapProjection.relationEnds(a, b)
        }
        val handles = if (dragging || editing != null) emptyList() else handles(zoom, touchPx)
        if (outline == null && relationLine == null && target == null && relation == null) return null
        return Chrome(outline, handles, target, relationLine, relation)
    }

    private fun refreshChrome() = host.mapChanged()

    /**
     * The selected node's handles: a child on the side its children grow to, a sibling across from
     * it (none for a root). Each is a [touchPx]-wide target on screen whatever the zoom.
     */
    private fun handles(zoom: Double, touchPx: Double): List<Handle> {
        val id = selected ?: return emptyList()
        if (editing != null || dragging) return emptyList()
        val placement = shown.placements[id] ?: return emptyList()
        if (!host.layerEditable(layerOf(id))) return emptyList()
        val r = placement.rect
        val radius = touchPx / 2 / zoom
        val clear = radius + HANDLE_GAP_PX / zoom
        // Off a side with a neighbour, a handle sits halfway across the gap, on the line, rather than
        // over the neighbour; off an empty side it clears the node (and its count badge).
        fun reach(gap: Double?, extra: Double = 0.0) =
            if (gap != null && gap > 0.0) min(clear + extra, gap / 2) else clear + extra
        val kids = shownState.childrenOf(id).mapNotNull(shown::rectOf)
        val badge = if ((shown.hiddenCount[id] ?: 0) > 0) MapProjection.BADGE + BADGE_CLEAR else 0.0
        val child = when (placement.side) {
            GrowSide.RIGHT -> Pt(r.right + reach(kids.minOfOrNull { it.left - r.right }, badge), r.centerY)
            GrowSide.LEFT -> Pt(r.left - reach(kids.minOfOrNull { r.left - it.right }, badge), r.centerY)
            GrowSide.DOWN -> Pt(r.centerX, r.bottom + reach(kids.minOfOrNull { it.top - r.bottom }, badge))
            GrowSide.INDENT -> Pt(r.right + clear, r.centerY)
        }
        val out = arrayListOf(Handle(HandleKind.CHILD, child, radius))
        val parent = shownState.parent[id]
        if (parent != null) {
            val across = shown.placements[parent]?.side
            val siblings = shownState.childrenOf(parent)
            val next = siblings.getOrNull(siblings.indexOf(id) + 1)?.let(shown::rectOf)
            out += Handle(
                HandleKind.SIBLING,
                if (across == GrowSide.DOWN) Pt(r.right + reach(next?.let { it.left - r.right }), r.centerY)
                else Pt(r.centerX, r.bottom + reach(next?.let { it.top - r.bottom })),
                radius,
            )
        }
        return out
    }

    companion object {
        /** How far a press must travel on screen before it drags a node. */
        const val DRAG_SLOP_PX = 12.0
        const val HANDLE_GAP_PX = 6.0
        /** Canvas units between a count badge and the child handle beyond it. */
        const val BADGE_CLEAR = 4.0
        const val RELATION_REACH_PX = 16.0

    }
}
