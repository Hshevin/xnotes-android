package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Every node measures [w] × [h] unless its title is in [byTitle]. */
class FixedMeasure(private val w: Double = 100.0, private val h: Double = 40.0, private val byTitle: Map<String, Size> = emptyMap()) : NodeMeasure {
    override fun size(node: MapNode, isRoot: Boolean): Size = byTitle[node.title] ?: Size(w, h)
}

/** Titles measure 10 per character (capped at maxWidth) by 24, and record every call. */
class FakeTitles : TitleMeasure {
    val calls = ArrayList<Triple<String, Double, Double>>()
    override fun measure(text: String, textSize: Double, bold: Boolean, maxWidth: Double): Size {
        calls += Triple(text, textSize, maxWidth)
        return Size(minOf(maxWidth, 10.0 * text.length), 24.0)
    }
}

/** Builds a [MapState] by hand for layout and hit tests. */
class StateBuilder {
    private val nodes = LinkedHashMap<NodeId, MapNode>()
    private val roots = ArrayList<NodeId>()
    private val parent = LinkedHashMap<NodeId, NodeId>()
    private val children = LinkedHashMap<NodeId, MutableList<NodeId>>()
    private val origin = LinkedHashMap<NodeId, Pt>()
    private val layout = LinkedHashMap<NodeId, LayoutKind>()
    private val offsets = LinkedHashMap<NodeId, Vec>()
    private val relations = LinkedHashMap<RelationId, Relation>()

    fun root(id: String, at: Pt = Pt(0.0, 0.0), kind: LayoutKind = LayoutKind.RIGHT, title: String = id): NodeId {
        val n = NodeId(id)
        nodes[n] = MapNode(n, title, NodeStyle.ROOT)
        roots += n
        origin[n] = at
        layout[n] = kind
        return n
    }

    fun child(of: NodeId, id: String, title: String = id): NodeId {
        val n = NodeId(id)
        nodes[n] = MapNode(n, title)
        parent[n] = of
        children.getOrPut(of) { ArrayList() } += n
        return n
    }

    fun offset(id: NodeId, dx: Double, dy: Double) { offsets[id] = Vec(dx, dy) }

    fun relation(id: String, a: NodeId, b: NodeId) {
        relations[RelationId(id)] = Relation(RelationId(id), a, b)
    }

    fun build() = MapState(
        nodes = nodes.toMap(),
        roots = roots.toList(),
        parent = parent.toMap(),
        children = children.mapValues { it.value.toList() },
        rootOrigin = origin.toMap(),
        layout = layout.toMap(),
        offsets = offsets.toMap(),
        relations = relations.toMap(),
    )
}

fun id(s: String) = NodeId(s)

fun rect(x: Double, y: Double, w: Double = 100.0, h: Double = 40.0) = Rect(x, y, w, h)

fun assertRect(expected: Rect, actual: Rect?, msg: String = "") {
    requireNotNull(actual) { "$msg: no rect" }
    val eps = 1e-6
    assertEquals("$msg x", expected.x, actual.x, eps)
    assertEquals("$msg y", expected.y, actual.y, eps)
    assertEquals("$msg w", expected.w, actual.w, eps)
    assertEquals("$msg h", expected.h, actual.h, eps)
}

fun assertPt(expected: Pt, actual: Pt, msg: String = "") {
    assertEquals("$msg x", expected.x, actual.x, 1e-6)
    assertEquals("$msg y", expected.y, actual.y, 1e-6)
}

fun assertValid(state: MapState) {
    val problems = MapInvariants.check(state)
    assertTrue("invariants broken: $problems", problems.isEmpty())
}

fun pen(vararg pts: Pair<Double, Double>): Stroke =
    Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), pts.mapIndexed { i, (x, y) -> Sample(x, y, 0.5 + i * 0.01, i * 8.0) })

/** An editor over an empty canvas with deterministic ids "n1", "n2", ... */
class EditorFixture(titles: TitleMeasure = FakeTitles()) {
    private var next = 0
    val model = MapModel()
    val history = History()
    val metrics = NodeMetrics(titles)
    val editor = MapEditor(model, history, metrics) { "n${++next}" }
    val state get() = model.state

    fun root(title: String = "root", at: Pt = Pt(0.0, 0.0)): NodeId {
        editor.beginRoot(at)
        return editor.commitDraft(title)!!
    }

    fun child(of: NodeId, title: String): NodeId {
        editor.beginChild(of)
        return editor.commitDraft(title)!!
    }

    fun sibling(of: NodeId, title: String): NodeId {
        editor.beginSibling(of)
        return editor.commitDraft(title)!!
    }

    fun undoCount(): Int {
        var n = 0
        // count by undoing then redoing everything
        while (history.canUndo) { history.undo(); n++ }
        repeat(n) { history.redo() }
        return n
    }
}
