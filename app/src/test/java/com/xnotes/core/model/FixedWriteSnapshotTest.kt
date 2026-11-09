package com.xnotes.core.model

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.shapes.Axis
import com.xnotes.core.shapes.Elbow
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class FixedWriteSnapshotTest {
    private val measurer=FakeTextMeasurer()
    private val noteCodec=DocumentCodec(FakeImageCodec(),measurer)
    private val canvasCodec=CanvasCodec(FakeImageCodec(),measurer)
    private val color=Rgba(12,34,56,200)
    private fun stroke()=Stroke(Tool.SPEED,ToolConfig(baseWidth=7.0,rgba=color,speedStrength=.7),
        listOf(Sample(10.0,20.0,.25,100.0),Sample(30.0,40.0,.75,200.0)),speedScale=1.2,smoothScale=.8).apply {
        cuts=listOf(CutEdge(1.0,2.0,0.0,1.0))
    }
    private fun zip(encode:(ByteArrayOutputStream)->Unit):Map<String,List<Byte>> {
        val output=ByteArrayOutputStream();encode(output)
        val entries=linkedMapOf<String,List<Byte>>()
        ZipInputStream(output.toByteArray().inputStream()).use {z->while(true) {
            val entry=z.nextEntry ?: break;entries[entry.name]=z.readBytes().toList()
        }}
        return entries
    }
    private fun scenario(block:(File)->Unit) {
        val root=Files.createTempDirectory("fixed-write-").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    @Test fun nativeStrokePrefixConfigAndCutsRemainFixedAcrossAppendTransformAndReset() {
        val live=stroke();val doc=Document.blank();doc.pages.single().items.add(live);doc.dirty=true
        val frozen=doc.fixedWriteSnapshot();val held=frozen.pages.single().items.single() as Stroke
        val samples=held.samples.toList();val config=held.config;val cuts=held.cuts
        repeat(40) {live.addSample(Sample(50.0+it,60.0,.5,300.0+it))}
        assertEquals(samples,held.samples);assertEquals(2,held.sampleCount)
        live.translate(100.0,200.0);live.config=live.config.copy(baseWidth=20.0);live.cuts=emptyList()
        live.setSamples(listOf(Sample(0.0,0.0,1.0)))
        assertEquals(samples,held.samples);assertEquals(config,held.config);assertEquals(cuts,held.cuts)
        assertEquals(100.0,held.tAt(0),.001);assertEquals(.25,held.pAt(0),.001)
        assertEquals(doc.bodyRevision,frozen.bodyRevision);assertSame(doc.snapshotOwnerToken,frozen.snapshotOwnerToken)
    }
    @Test fun straightEndpointReplacementAndTrimCannotOverwriteAPinnedSamplePrefix() {
        val ink=Stroke(Tool.PEN,ToolConfig(),listOf(Sample(1.0,2.0,.3),Sample(4.0,5.0,.8)),straight=true)
        val first=ink.copyForWrite();ink.setStraightEnd(Sample(20.0,30.0,.6))
        val second=ink.copyForWrite();ink.setStraightEnd(Sample(40.0,50.0,.9));ink.trimToSize()
        assertEquals(4.0,first.xAt(1),.001);assertEquals(5.0,first.yAt(1),.001)
        assertEquals(20.0,second.xAt(1),.001);assertEquals(30.0,second.yAt(1),.001)
        assertEquals(40.0,ink.xAt(1),.001)
    }
    @Test fun allSevenNoteKindsKeepExactlyTheCapturedCodecPayloads()=scenario {root->
        val doc=Document.blank().apply {enableReferenceAnchors()};val page=doc.pages.single()
        val layer=page.layers.create("captured layer")
        val ink=stroke()
        val image=ImageItem(ImageData(File(root,"image").apply {writeText("image payload")},64,48),Rect(1.0,2.0,30.0,40.0),orientation=1,angle=.2,part=Rect(.1,.2,.5,.6))
        val peaks=byteArrayOf(1,2,3)
        val audio=AudioItem(AudioData(File(root,"audio").apply {writeText("audio payload")},"aac",123,peaks),Rect(3.0,4.0,50.0,20.0),"captured recording",7,measurer)
        val text=TextItem(Pt(5.0,6.0),120.0,45.0,"captured text",color,17.0,measurer=measurer)
        val adj=doubleArrayOf(.2);val segments=mutableListOf<VectorSeg>(VectorSeg.Line(Pt(20.0,30.0)))
        val path=mutableListOf(VectorContour(Pt(1.0,2.0),segments,false))
        val shape=ShapeItem(ShapeKind.GLYPHS,Pt(0.0,0.0),Pt(50.0,60.0),color,5.0,fillRgba=Rgba(80,80,80),path=path,adj=adj).apply {
            label=ShapeLabel("captured label",TextStyle(color,17.0,TextItem.DEFAULT_FACE),measurer);labelAt=.3
        }
        val points=mutableListOf(Pt(4.0,5.0),Pt(40.0,50.0))
        val tape=TapeItem(TapeShape(TapeMode.STRAIGHT,points,12.0),color,TapePattern.DOTS)
        val route=doubleArrayOf(15.0);val waypoints=mutableListOf(Pt(20.0,10.0))
        val connector=ConnectorItem(Routing.ELBOW,ConnectorEnd(Pt(0.0,0.0),text,0),ConnectorEnd(Pt(50.0,60.0),shape,1),color,6.0).apply {
            elbow=Elbow(Axis.H,route);this.waypoints=waypoints;label=ShapeLabel("relation",TextStyle(color,17.0,TextItem.DEFAULT_FACE),measurer)
        }
        val items=listOf(ink,image,audio,text,shape,tape,connector)
        items.forEach {it.layerId=layer.id;it.locked=true;it.group=8;it.sync=SyncMark(7,33)};page.items.addAll(items)
        val before=zip {noteCodec.write(doc.snapshot(),it)};val frozen=doc.fixedWriteSnapshot()
        ink.translate(500.0,500.0);image.rect=Rect(100.0,200.0,60.0,70.0);image.image=ImageData(File(root,"replacement").apply {writeText("other image")},64,48)
        audio.name="later";audio.rid=99;peaks[0]=100;text.text="later";text.width=300.0
        shape.strokeWidth=40.0;shape.label=null;adj[0]=.9;segments.clear();path.clear();points.clear();route[0]=800.0;waypoints.clear()
        tape.pattern=TapePattern.GRID;connector.start=ConnectorEnd(Pt(900.0,900.0));connector.label=null
        items.forEach {it.layerId=DrawingLayers.DEFAULT;it.locked=false;it.group=null;it.sync=null}
        page.layers.delete(layer.id);page.items.clear()
        assertEquals(before,zip {noteCodec.write(frozen,it)})
        val frozenItems=frozen.pages.single().items
        val edge=frozenItems.last() as ConnectorItem
        assertSame(frozenItems[3],edge.start.target);assertSame(frozenItems[4],edge.end.target)
        assertNotSame(text,edge.start.target);assertEquals(layer.id,frozen.pages.single().layerIdForWrite(frozenItems.first()))
        assertEquals(1,(frozenItems[4] as ShapeItem).path!!.single().segments.size)
    }
    @Test fun containersPageIdentityAndFlowStayFixedWithoutAllocatingNewPageIds()=scenario {root->
        val doc=Document.blank(2).apply {enableReferenceAnchors()}
        val params=mutableMapOf("custom" to 5.0);val colors=mutableMapOf("custom" to color)
        val templates=mutableMapOf("custom" to "<svg/>");val packages=mutableMapOf("unused" to File(root,"template"))
        val rooms=mutableListOf(Room(30.0,40.0));doc.style=PageStyle(template="custom",params=params,colors=colors)
        doc.templates=templates;doc.templatePackages=packages;doc.pages[0].style=doc.style;doc.pages[0].rooms=rooms
        doc.flow.paragraphs.add(Paragraph(mutableListOf(Run("captured flow"))));doc.bookmarks.add(Bookmark(1,"captured bookmark"))
        val frozen=doc.fixedWriteSnapshot();val ids=frozen.pages.map {it.referenceIdentity.id}
        params.clear();colors.clear();templates.clear();packages.clear();rooms.clear()
        doc.flow.paragraphs[0].runs[0].text="later";doc.bookmarks[0].label="later";doc.pages.reverse();doc.pages.removeAt(0)
        assertEquals(5.0,frozen.style.params!!["custom"]!!,0.0);assertEquals(color,frozen.pages[0].style.colors!!["custom"])
        assertEquals("<svg/>",frozen.templates["custom"]);assertEquals(1,frozen.templatePackages.size);assertEquals(1,frozen.pages[0].rooms.size)
        assertEquals("captured flow",frozen.flow.paragraphs[0].runs[0].text);assertEquals("captured bookmark",frozen.bookmarks[0].label)
        assertEquals(ids,frozen.pages.map {it.referenceIdentity.id});assertEquals(doc.identity,frozen.identity)
    }
    @Test fun connectorScopesNeverPreserveCrossPageOrDeletedTargets() {
        val doc=Document.blank(2);val first=TextItem(Pt.ZERO,text="here",measurer=measurer)
        val foreign=TextItem(Pt.ZERO,text="other page",measurer=measurer)
        val deleted=TextItem(Pt.ZERO,text="deleted",measurer=measurer)
        val edge=ConnectorItem(Routing.STRAIGHT,ConnectorEnd(Pt(1.0,2.0),foreign,1),ConnectorEnd(Pt(3.0,4.0),deleted,2),color)
        doc.pages[0].items.addAll(listOf(first,edge));doc.pages[1].items.add(foreign)
        val frozen=doc.fixedWriteSnapshot();val saved=frozen.pages[0].items.last() as ConnectorItem
        assertNull(saved.start.target);assertNull(saved.end.target);assertEquals(edge.start.at,saved.start.at);assertEquals(edge.end.at,saved.end.at)
        assertSame(foreign,edge.start.target);assertSame(deleted,edge.end.target)
    }
    @Test fun fixedFlowPayloadDetachesMutableTableGeometryWhileKeepingCellMembership() {
        val doc=Document.blank();val widths=mutableListOf(.4,.6);val heights=mutableListOf(10.0)
        val table=com.xnotes.core.text.FlowTable(widths,heights)
        doc.flow.paragraphs.addAll(listOf(
            Paragraph(mutableListOf(Run("left")),table=table,cellStart=true),
            Paragraph(mutableListOf(Run("right")),table=table,cellStart=true)))
        val before=zip {noteCodec.write(doc.snapshot(),it)};val frozen=doc.fixedWriteSnapshot()
        widths[0]=.8;widths[1]=.2;heights[0]=80.0;table.width=.5
        assertEquals(before,zip {noteCodec.write(frozen,it)})
        val saved=frozen.flow.paragraphs[0].table!!
        assertSame(saved,frozen.flow.paragraphs[1].table);assertNotSame(table,saved)
        assertEquals(listOf(.4,.6),saved.widths);assertEquals(listOf(10.0),saved.minHeights)
    }
    @Test fun pageMarkupListsAndNestedQuadsDoNotKeepMutableAliases() {
        val doc=Document.blank();val quads=mutableListOf(com.xnotes.core.pdf.TextQuad(1f,2f,3f,4f,0))
        val marks=mutableListOf(TextMarkup("id",MarkupType.UNDERLINE,color,.5,quads,"text","note",1L,2L))
        doc.pages.single().markups=marks;val frozen=doc.fixedWriteSnapshot()
        marks.clear();quads.clear()
        val captured=frozen.pages.single().markups.single()
        assertEquals("note",captured.note);assertEquals(1,captured.quads.size);assertEquals(1f,captured.quads.single().left,0f)
    }
    @Test fun canvasFixedSnapshotKeepsPayloadAndRejectsUnsupportedTextInsteadOfDroppingIt() {
        val doc=InfiniteDocument();val ink=stroke();doc.add(ink);doc.dirty=true
        val before=zip {canvasCodec.write(doc.snapshotForWrite(),it)};val frozen=doc.snapshotForWrite(fixedItems=true)
        ink.translate(100.0,200.0);doc.remove(ink)
        assertEquals(before,zip {canvasCodec.write(frozen,it)})
        doc.add(TextItem(Pt.ZERO,text="unsupported canvas text",measurer=measurer))
        assertThrows(IllegalArgumentException::class.java) {doc.snapshotForWrite(fixedItems=true)}
    }
    @Test fun legacyDefaultStillSharesItemsAndTranslatingAWriterViewCannotMoveLiveGeometry() {
        val doc=Document.blank();val ink=stroke();doc.pages.single().items.add(ink)
        val shallow=doc.snapshot();val fixed=doc.fixedWriteSnapshot();val later=doc.fixedWriteSnapshot()
        assertSame(ink,shallow.pages.single().items.single());assertNotSame(ink,fixed.pages.single().items.single())
        (fixed.pages.single().items.single() as Stroke).translate(100.0,100.0)
        assertEquals(10.0,ink.xAt(0),.001);assertEquals(10.0,(later.pages.single().items.single() as Stroke).xAt(0),.001)
    }
    @Test fun unknownItemsAreRejectedRatherThanOmittedFromEitherFixedWriter() {
        val unknown=object:CanvasItem by stroke() {override val kind="future-object"}
        val doc=Document.blank();doc.pages.single().items.add(unknown)
        assertThrows(IllegalArgumentException::class.java) {doc.fixedWriteSnapshot()}
        val canvas=InfiniteDocument();canvas.add(unknown)
        assertThrows(IllegalArgumentException::class.java) {canvas.snapshotForWrite(fixedItems=true)}
        assertSame(unknown,doc.pages.single().items.single());assertSame(unknown,canvas.items.single())
    }
}
