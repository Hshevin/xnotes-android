package com.xnotes.iosapp

import com.xnotes.canvas.HandleId
import com.xnotes.canvas.ResizeMath
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.model.*
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.platform.ios.CgImageCodec
import com.xnotes.platform.ios.CgTextMeasurer
import com.xnotes.storage.lite.*
import kotlin.test.*

class ItemSelectionTest {
    private val fs = KFiles.system
    private val root = KFiles.tempDirectory() / "selection-${Uuids.random()}"
    private val documents = root / "Documents"
    private lateinit var editor: InkEditor
    private lateinit var selection: ItemSelection

    private fun stroke() = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(100.0, 100.0, 0.5), Sample(220.0, 180.0, 0.7)))
    private fun setup(infinite: Boolean) {
        fs.createDirectories(documents)
        val file = documents / if (infinite) "test.xcanvas" else "test.xnote"
        fs.write(file) {
            if (infinite) CanvasCodec(CgImageCodec, CgTextMeasurer).write(InfiniteDocument().also { it.add(stroke()) }, this)
            else DocumentCodec(CgImageCodec, CgTextMeasurer).write(Document(mutableListOf(Page(600.0, 600.0, mutableListOf(stroke())), Page(600.0, 600.0))), this)
        }
        val store = LiteStore(fs, documents, root / "Private", CgImageCodec, CgTextMeasurer)
        editor = InkEditor(assertIs<LiteOpen.Opened>(store.open(file)).session) {}
        val space = object : InkSpace {
            override val origin = Pt.ZERO
            override val zoom = 1.0
            override fun paper(page: Int) = if (infinite) null else Rect(0.0, page * 610.0, 600.0, 600.0)
            override fun pageAt(content: Pt) = if (infinite) 0 else (0..1).firstOrNull { paper(it)!!.contains(content) }
            override fun hasPage(page: Int) = page == 0 || !infinite && page == 1
            override fun writable(page: Int) = hasPage(page)
            override fun toPage(page: Int, content: Pt) = content - Pt(0.0, page * 610.0)
            override fun fromPage(page: Int, local: Pt) = local + Pt(0.0, page * 610.0)
            override fun night(page: Int) = false
        }
        selection = ItemSelection(editor, space) {}; editor.selection = selection
        editor.select(Tool.SELECT)
    }

    @AfterTest fun close() { if (::editor.isInitialized) editor.releaseOwner(); fs.deleteRecursively(root) }

    private fun drag(start: Pt, end: Pt) { selection.begin(start); selection.move(end); selection.finish(end) }
    private fun enclose() = drag(Pt(60.0, 60.0), Pt(260.0, 200.0))
    private fun tap(at: Pt) { selection.begin(at); selection.finish(at) }

    @Test fun pagedBandMoveAcrossGapCommitsOnceAndToolChangeAbortsTheNextDrag() {
        setup(false)
        val pages = editor.session.note!!.pages
        val original = pages[0].items.single() as Stroke
        val top = stroke().also { it.translate(320.0, 320.0); pages[0].items.add(it) }
        val samples = original.samples.toList()
        enclose(); assertSame(original, selection.members.single().item); assertFalse(editor.canUndo)
        val center = selection.frame!!.center
        drag(center, Pt(4000.0, 3000.0)); assertEquals(samples, original.samples); assertFalse(editor.canUndo)
        drag(center, center + Pt(15.0, 610.0))
        assertSame(top, pages[0].items.single()); assertSame(original, pages[1].items.single()); assertEquals(1, selection.members.single().pageIndex)
        assertEquals(115.0, original.samples.first().x); assertEquals(100.0, original.samples.first().y)
        editor.replay(true); assertEquals(samples, original.samples); assertEquals(listOf<CanvasItem>(original, top), pages[0].items.toList()); assertFalse(editor.canUndo)
        editor.replay(false); assertSame(original, pages[1].items.single())
        tap(Pt(115.0, 710.0)); val saved = original.samples.toList()
        selection.begin(selection.frame!!.center); selection.move(center + Pt(80.0, 650.0))
        editor.select(Tool.PEN); selection.finish(Pt(15.0, 30.0))
        assertFalse(editor.active); assertEquals(saved, original.samples); assertTrue(selection.members.isEmpty())
    }

    @Test fun lassoResizeRotateDuplicateCopyPasteDeleteAreIndependentUndoSteps() {
        setup(true)
        val doc = editor.session.canvas!!; val original = doc.items.single() as Stroke; val before = original.samples.toList()
        editor.select(Tool.LASSO); selection.begin(Pt(68.0, 68.0)); selection.move(Pt(250.0, 67.0))
        selection.move(Pt(255.0, 205.0)); selection.finish(Pt(67.0, 201.0))
        assertSame(original, selection.members.single().item)
        val corner = ResizeMath.obbHandles(selection.frame!!).single { it.id == HandleId.BR }.content
        drag(corner, corner + Pt(120.0, 80.0)); val scaled = original.samples.toList()
        assertTrue(scaled.last().x > before.last().x + 50)
        val frame = selection.frame!!; val grip = ResizeMath.obbRotateGrip(frame, OverlayTessellator.GRIP_ARM_PX)
        drag(grip, frame.center + Pt(frame.center.distanceTo(grip), 0.0)); val turned = original.samples.toList(); assertNotEquals(scaled, turned)
        selection.copy(); selection.insert(true); val duplicate = doc.items.last() as Stroke
        assertEquals(turned.first().x + 24, duplicate.samples.first().x); assertEquals(turned.first().y + 24, duplicate.samples.first().y)
        tap(Pt(900.0, 800.0)); assertTrue(selection.members.isEmpty()); selection.insert(false)
        assertEquals(3, doc.itemCount); val pasted = selection.members.single().item
        assertEquals(900.0, pasted.bounds().x, 0.001); assertEquals(800.0, pasted.bounds().y, 0.001)
        selection.delete(); assertEquals(2, doc.itemCount)
        editor.replay(true); assertSame(pasted, doc.items.last()); editor.replay(true); assertEquals(2, doc.itemCount)
        editor.replay(true); assertEquals(listOf(original), doc.items); editor.replay(true); assertEquals(scaled, original.samples)
        editor.replay(true); assertEquals(before, original.samples); assertFalse(editor.canUndo)
        repeat(5) { editor.replay(false) }; assertEquals(2, doc.itemCount); assertEquals(turned, original.samples)
    }

    @Test fun cancellingOrBackgroundingRestoresCanvasIndexAndNeverWritesThePreview() {
        setup(true)
        val doc = editor.session.canvas!!; val original = doc.items.single() as Stroke; val before = original.samples.toList()
        val bytes = fs.read(documents / "test.xcanvas") { readByteArray() }
        enclose(); val center = selection.frame!!.center; val away = center + Pt(4000.0, 3000.0)
        repeat(2) { pass ->
            selection.begin(center); selection.move(away)
            assertTrue(doc.itemsIn(Rect(away.x - 5, away.y - 5, 10.0, 10.0)).contains(original))
            if (pass == 0) selection.cancel() else editor.save(background = true)
            selection.finish(away); assertEquals(before, original.samples); assertFalse(editor.canUndo)
            assertFalse(editor.session.dirty); assertFalse(editor.active)
            assertFalse(doc.itemsIn(Rect(away.x - 5, away.y - 5, 10.0, 10.0)).contains(original))
        }
        assertContentEquals(bytes, fs.read(documents / "test.xcanvas") { readByteArray() })
    }

    @Test fun aFollowingConnectorRollsBackGeometryAndBindingAndSharesTheUndoStep() {
        setup(true)
        val doc = editor.session.canvas!!
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(350.0, 350.0), Pt(460.0, 450.0), black, fillRgba = white)
        val port = Glue.points(shape).first().at; val connector = ConnectorItem(Routing.STRAIGHT, ConnectorEnd(port, shape, 0), ConnectorEnd(Pt(750.0, 680.0)), black)
        doc.add(shape); doc.add(connector)
        tap(Pt(400.0, 400.0)); val center = selection.frame!!.center; val end = center + Pt(70.0, 40.0)
        selection.begin(center); selection.move(end); assertNotEquals(port, connector.start.at)
        selection.cancel(); assertEquals(port, connector.start.at); assertSame(shape, connector.start.target); assertFalse(editor.canUndo)
        drag(center, end); val newPort = connector.start.at; assertNotEquals(port, newPort)
        editor.replay(true); assertEquals(port, connector.start.at); assertFalse(editor.canUndo)
        editor.replay(false); assertEquals(newPort, connector.start.at); assertSame(shape, connector.start.target)
        val order = doc.items.toList()
        tap(shape.bounds().let { Pt(it.left + 8, it.bottom - 8) }); assertSame(shape, selection.members.single().item)
        selection.delete(); assertFalse(shape in doc.items); assertNull(connector.start.target); assertEquals(newPort, connector.start.at)
        editor.replay(true); assertSame(shape, connector.start.target); assertEquals(order, doc.items)
    }
}
