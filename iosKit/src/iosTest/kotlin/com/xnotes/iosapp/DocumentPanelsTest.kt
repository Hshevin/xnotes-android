package com.xnotes.iosapp

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.DrawingLayers
import com.xnotes.edit.PageSpace
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.map.*
import com.xnotes.pen.pencil.PencilSample
import com.xnotes.platform.ios.CgImageCodec
import com.xnotes.platform.ios.CgTextMeasurer
import com.xnotes.storage.lite.*
import kotlin.test.*

class DocumentPanelsTest {
    private val fs = KFiles.system
    private val root = KFiles.tempDirectory() / Uuids.random()
    private val docs = (root / "Documents").also(fs::createDirectories)
    private val store = LiteStore(fs, docs, root / "Private", CgImageCodec, CgTextMeasurer)
    @AfterTest fun clean() = fs.deleteRecursively(root)

    @Test fun pageOwnershipHistoryAndIndependentCopiesSurviveReopen() {
        val session = assertIs<LiteOpen.Opened>(store.create(docs, "Pages", LiteKind.NOTE)).session
        session.use {
            val editor = InkEditor(session) {}; val panels = editor.panels
            val note = session.note!!; val original = note.pages.first()
            panels.edit { panels.layers.create("Ink") }
            panels.add(false); val blank = note.pages[1]
            assertSame(blank, panels.page); assertEquals(1, blank.layers.entries.size)
            panels.focus(original); panels.add(true); val copy = note.pages[1]
            assertEquals(2, copy.layers.entries.size); assertNotEquals(original.referenceIdentity.id, copy.referenceIdentity.id)
            panels.movePage(copy, blank); assertEquals(listOf(original, blank, copy), note.pages)
            panels.deletePage(copy); editor.replay(true); assertSame(copy, note.pages.last())
            editor.save(); assertNull(editor.warning)
            assertIs<LiteOpen.Opened>(store.open(session.path)).session.use { reopened ->
                assertEquals(note.pages.map { it.referenceIdentity.id }, reopened.note!!.pages.map { it.referenceIdentity.id })
                assertEquals(listOf(2, 1, 2), reopened.note!!.pages.map { it.layers.entries.size })
            }
        }
    }

    @Test fun inkUsesCurrentLayerHiddenAndLockedRejectInkAndSelectionIsNotUndoable() {
        for (kind in LiteKind.entries) {
            val session = assertIs<LiteOpen.Opened>(store.create(docs, "Ink", kind)).session
            session.use {
                val editor = InkEditor(session) {}; val panels = editor.panels; val layers = panels.layers
                val space = object : PageSpace {
                    override val zoom = 1.0
                    override fun pageAt(content: Pt) = 0
                    override fun hasPage(page: Int) = page == 0
                    override fun writable(page: Int) = layers.writable
                    override fun toPage(page: Int, content: Pt) = content
                    override fun fromPage(page: Int, local: Pt) = local
                }
                fun draw() { InkGesture(editor, space, false).apply {
                    begin(PencilSample(10.0, 10.0, 0.0, 1.0, 1.0, null, false)); finish(emptyList(), false)
                } }
                fun items() = session.note?.pages?.first()?.items ?: session.canvas!!.items
                panels.edit { layers.create("Overlay") }; val id = layers.activeId
                draw(); assertEquals(id, items().single().layerId)
                panels.edit { layers.update(id) { it.copy(locked = true) } }; draw(); assertEquals(1, items().size)
                panels.edit { layers.update(id) { it.copy(locked = false, visible = false) } }; draw(); assertEquals(1, items().size)
                panels.selectLayer(DrawingLayers.DEFAULT); editor.replay(true)
                assertTrue(layers.entries.last().locked && layers.entries.last().visible)
                panels.deleteLayer(id); assertTrue(items().isEmpty()); editor.replay(true); assertEquals(id, items().single().layerId)
                editor.save(); assertNull(editor.warning)
                assertIs<LiteOpen.Opened>(store.open(session.path)).session.use { reopened ->
                    val actual = reopened.note?.pages?.first()?.layers ?: reopened.canvas!!.layers
                    assertEquals(layers.entries, actual.entries); assertEquals(layers.activeId, actual.activeId)
                }
            }
        }
    }

    @Test fun movingDefaultLayerKeepsItsTreeAndDeletingItIsOneUndo() {
        assertIs<LiteOpen.Opened>(store.create(docs, "Map", LiteKind.CANVAS)).session.use { session ->
            val editor = InkEditor(session) {}; val panels = editor.panels; val layers = panels.layers
            val map = session.canvas!!.map; val id = NodeId(Uuids.random()); val base = DrawingLayers.DEFAULT
            val initial = MapState(nodes = mapOf(id to MapNode(id, "Tree")), roots = listOf(id),
                rootOrigin = mapOf(id to Pt.ZERO), layout = mapOf(id to LayoutKind.RIGHT))
            map.replace(initial)
            panels.edit { layers.create("Overlay") }; panels.edit { layers.move(base, 1) }
            assertEquals(base, layers.entries.last().id)
            val scene = PanelMapScene(wantsMeshes = false)
            fun parts() = scene.update(map.state, map.collapsed, layers.entries.map { it.id }, false)
            assertEquals(base, parts().single().layerId)
            panels.deleteLayer(base); assertTrue(parts().isEmpty()); assertEquals(1, layers.entries.size)
            editor.replay(true); assertEquals(initial, map.state); assertEquals(base, parts().single().layerId)
            assertEquals(base, layers.entries.last().id)
            editor.replay(false); assertTrue(parts().isEmpty()); assertEquals(1, layers.entries.size)
        }
    }
}
