package com.xnotes.mindmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Native Android drawing on the host, not a MovinkPad GPU acceptance test. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapRenderTest {
    @Test fun multipleNodeHighlightsAreMonochromeAndStayOutOfThumbnailsAndPdfExports() {
        val context=RuntimeEnvironment.getApplication()
        val doc=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null,x=-200.0,width=100.0),
            MapNode(id="b",parent=null,free=true,x=200.0,width=100.0)))
        val bytes=MapCodec.encode(doc)
        val file=File.createTempFile("node-selection-render-",".xmap",context.cacheDir).apply {writeBytes(bytes)}
        val uri=Uri.fromFile(file).toString()
        try {
            val before=MapFiles.thumbnail(context,uri,600,400)
            val view=MindMapView(context).apply {document=doc;nodeSelectionMode=true;layout(0,0,1000,600);fit();setSelectedNodes(setOf("a","b"))}
            for(dark in listOf(false,true)) {
                view.dark=dark
                val bitmap=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    for(n in doc.nodes) {
                        val p=view.screenPoint(com.xnotes.core.geometry.Pt(n.x+8,n.y+8))
                        assertEquals(if(dark) Color.BLACK else Color.WHITE,bitmap.getPixel(p.x.toInt(),p.y.toInt()))
                        val zoom=view.viewport().zoom
                        val offset=maxOf(4f/zoom,4f)+maxOf(2f/zoom,3f)/2
                        val ring=view.screenPoint(com.xnotes.core.geometry.Pt(n.x-offset,n.y+8))
                        assertEquals(if(dark) Color.WHITE else Color.BLACK,bitmap.getPixel(ring.x.toInt(),ring.y.toInt()))
                    }
                    val pixels=IntArray(1000*600);bitmap.getPixels(pixels,0,1000,0,0,1000,600)
                    assertTrue(pixels.all {Color.red(it)==Color.green(it) && Color.green(it)==Color.blue(it)})
                } finally {bitmap.recycle()}
            }
            val after=MapFiles.thumbnail(context,uri,600,400)
            try {assertTrue(before.sameAs(after))} finally {before.recycle();after.recycle()}
            val output=ByteArrayOutputStream();MapFiles.exportPdf(context,uri,output){false}
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(output.toByteArray()).use {pdf->
                val resources=pdf.getPage(0).resources
                val exported=(resources.getXObject(resources.xObjectNames.single()) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject).image
                val ordinary=MapFiles.thumbnail(context,uri,exported.width,exported.height)
                try {assertTrue(ordinary.sameAs(exported))} finally {ordinary.recycle();exported.recycle()}
            }
            assertArrayEquals(bytes,MapCodec.encode(view.document));assertArrayEquals(bytes,file.readBytes())
        } finally {file.delete()}
    }
    @Test fun aNodeMarqueePreviewReplacesThePriorSingletonHighlightAndCancelsWithoutPersisting() {
        val doc=MindMapDocument(nodes=listOf(MapNode(id="b",parent=null,x=-300.0,width=100.0),MapNode(id="a",parent=null,free=true,x=300.0,width=100.0)))
        val bytes=MapCodec.encode(doc)
        for(dark in listOf(false,true)) {
            val view=MindMapView(RuntimeEnvironment.getApplication()).apply {document=doc;nodeSelectionMode=true;layout(0,0,1000,600);fit();setSelectedNodes(setOf("a"));this.dark=dark}
            fun event(action:Int,p:com.xnotes.core.geometry.Pt) {
                val e=android.view.MotionEvent.obtain(100,200,action,p.x.toFloat(),p.y.toFloat(),0)
                try {view.onTouchEvent(e)} finally {e.recycle()}
            }
            fun pixel(id:String):Int {
                val image=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
                try {view.draw(Canvas(image));val n=doc.node(id);val zoom=view.viewport().zoom;val offset=maxOf(4f/zoom,4f)+maxOf(2f/zoom,3f)/2;val p=view.screenPoint(com.xnotes.core.geometry.Pt(n.x-offset,n.y+8));return image.getPixel(p.x.toInt(),p.y.toInt())}
                finally {image.recycle()}
            }
            val paper=if(dark) Color.BLACK else Color.WHITE;val selected=if(dark) Color.WHITE else Color.BLACK
            assertEquals(selected,pixel("a"));assertEquals(paper,pixel("b"))
            event(android.view.MotionEvent.ACTION_DOWN,view.screenPoint(com.xnotes.core.geometry.Pt(-320.0,-20.0)))
            event(android.view.MotionEvent.ACTION_MOVE,view.screenPoint(com.xnotes.core.geometry.Pt(-180.0,100.0)))
            assertEquals("Only the preview set has an outline",paper,pixel("a"));assertEquals(selected,pixel("b"))
            event(android.view.MotionEvent.ACTION_CANCEL,view.screenPoint(com.xnotes.core.geometry.Pt(-180.0,100.0)))
            assertEquals(selected,pixel("a"));assertEquals(paper,pixel("b"));assertArrayEquals(bytes,MapCodec.encode(view.document))
        }
    }
    @Test fun outlinesRemainVisibleAtDifferentZoomsWithoutChangingNodeInteriorsOrAdjacentBorders() {
        for(dark in listOf(false,true)) for(zoom in listOf(.1f,1f,4f)) {
            val paper=if(dark) Color.BLACK else Color.WHITE
            val fg=if(dark) Color.WHITE else Color.BLACK
            val doc=MindMapDocument(nodes=listOf(
                MapNode(id="a",parent=null,x=0.0,y=0.0,width=100.0,height=80.0,text=""),
                MapNode(id="b",parent=null,free=true,x=104.0,y=0.0,width=100.0,height=80.0,text="")))
            fun render(selected:Set<String>):Bitmap=Bitmap.createBitmap(1000,500,Bitmap.Config.ARGB_8888).also {image->
                val canvas=Canvas(image);canvas.drawColor(paper);canvas.translate(100f,100f);canvas.scale(zoom,zoom)
                MapPainter().draw(canvas,doc,dark,selectedNodes=selected,viewZoom=zoom)
            }
            val normal=render(emptySet());val selected=render(setOf("b"))
            try {
                // Selecting the second node must not clear the already painted first node's right edge.
                for(y in 102 until (100+78*zoom).toInt()) {
                    val x=(100+100*zoom).toInt()
                    assertEquals(normal.getPixel(x,y),selected.getPixel(x,y))
                }
                val left=(100+104*zoom).toInt()
                val top=100
                val width=(100*zoom).toInt();val height=(80*zoom).toInt()
                for(y in top+2 until top+height-2) for(x in left+2 until left+width-2)
                    assertEquals(normal.getPixel(x,y),selected.getPixel(x,y))
                assertTrue("Outer selection remains visible at $zoom",(left until left+width).any {x->
                    (top-40 until top-1).any {y->selected.getPixel(x,y)==fg && normal.getPixel(x,y)==paper}
                })
            } finally {normal.recycle();selected.recycle()}
        }
    }

    @Test fun relationLabelGeometryMatchesTheLeftAlignedTruncatedTextAndSelectionIsTransient() {
        val label="relationship description ".repeat(8)
        val doc=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null,width=100.0,x=-300.0),
            MapNode(id="b",parent=null,free=true,width=100.0,x=200.0)),
            relations=listOf(MapRelation(id="e",from="a",to="b",label=label,kind=RelationKind.DEPENDS_ON)))
        val bytes=MapCodec.encode(doc)
        val painter=MapPainter()
        val line=painter.relationGeometry(doc).single()
        val text=android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {textSize=18f}
        val box=requireNotNull(line.labelBounds)
        assertEquals(line.labelAnchor.x,box.left,0.0)
        assertEquals(line.labelAnchor.y+text.fontMetrics.ascent,box.top,.001)
        assertEquals(text.measureText(label.take(80)).toDouble(),box.w,.001)
        assertTrue(box.w<text.measureText(label))
        val bounds=painter.drawingBounds(doc)
        assertTrue(bounds.contains(box.topLeft));assertTrue(bounds.contains(com.xnotes.core.geometry.Pt(box.right,box.bottom)))
        val farLabel=com.xnotes.core.geometry.Pt(box.right-1,box.centerY)
        assertTrue(farLabel.x>line.end.x+24)
        assertEquals(listOf("e"),MapRelationGeometry.hits(listOf(line),farLabel,.1))
        assertTrue(MapRelationGeometry.hits(listOf(line.copy(labelBounds=null)),farLabel,.1).isEmpty())
        for(dark in listOf(false,true)) {
            fun pixels(selected:String?):IntArray {
                val image=Bitmap.createBitmap(1400,500,Bitmap.Config.ARGB_8888)
                try {
                    val canvas=Canvas(image);canvas.drawColor(if(dark) Color.BLACK else Color.WHITE);canvas.translate(400f,200f)
                    painter.draw(canvas,doc,dark,selectedRelation=selected)
                    return IntArray(1400*500).also {image.getPixels(it,0,1400,0,0,1400,500)}
                } finally {image.recycle()}
            }
            val ordinary=pixels(null);val selected=pixels("e")
            assertFalse(ordinary.contentEquals(selected))
            assertTrue(selected.all {Color.red(it)==Color.green(it) && Color.green(it)==Color.blue(it)})
            assertArrayEquals(ordinary,pixels(null))
        }
        assertArrayEquals(bytes,MapCodec.encode(doc))
    }
    @Test fun aLiveRelationSelectionNeverAppearsInStoredThumbnailsOrThePdfImage() {
        val context=RuntimeEnvironment.getApplication()
        val doc=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null,width=100.0,x=-300.0),
            MapNode(id="b",parent=null,free=true,width=100.0,x=200.0)),
            relations=listOf(MapRelation(id="e",from="a",to="b",label="Displayed label")))
        val bytes=MapCodec.encode(doc)
        val file=File.createTempFile("relation-render-",".xmap",context.cacheDir).apply {writeBytes(bytes)}
        val uri=Uri.fromFile(file).toString()
        try {
            val before=MapFiles.thumbnail(context,uri,600,400)
            val view=MindMapView(context).apply {document=doc;layout(0,0,1000,600);fit();selectedRelation="e"}
            assertEquals("e",view.selectedRelation)
            val after=MapFiles.thumbnail(context,uri,600,400)
            try {assertTrue(before.sameAs(after))} finally {before.recycle();after.recycle()}
            val output=ByteArrayOutputStream()
            MapFiles.exportPdf(context,uri,output){false}
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(output.toByteArray()).use {pdf->
                val resources=pdf.getPage(0).resources
                val image=resources.getXObject(resources.xObjectNames.single()) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
                val exported=image.image
                val ordinary=MapFiles.thumbnail(context,uri,exported.width,exported.height)
                try {assertTrue(ordinary.sameAs(exported))} finally {ordinary.recycle();exported.recycle()}
            }
            assertEquals("e",view.selectedRelation)
            assertArrayEquals(bytes,MapCodec.encode(view.document));assertArrayEquals(bytes,file.readBytes())
        } finally {file.delete()}
    }
    /** This host suite omits app resources; inject only the formatter at the Android boundary. */
    private fun graphContext():android.content.Context {
        val app=RuntimeEnvironment.getApplication()
        return object:android.content.ContextWrapper(app) {
            private val graphResources=object:android.content.res.Resources(app.resources.assets,app.resources.displayMetrics,app.resources.configuration) {
                override fun getString(id:Int,vararg formatArgs:Any):String = if(id==com.xnotes.R.string.graph_hidden_nodes_count) "+${formatArgs.single()}" else super.getString(id,*formatArgs)
            }
            override fun getResources()=graphResources
        }
    }
    private fun example(): MindMapDocument {
        val ink = listOf(MapInk(listOf(InkPoint(24.0, 55.0, .8), InkPoint(65.0, 130.0, .8), InkPoint(110.0, 38.0, .5)), 5.0),
            MapInk(listOf(InkPoint(140.0, 85.0, .8), InkPoint(270.0, 85.0, .8)), 3.0))
        return MapLayout.arrange(MindMapDocument(nodes = listOf(
            MapNode(id = "root", parent = null, text = "Project plan"),
            MapNode(id = "a", parent = "root", text = "Ideas and questions"),
            MapNode(id = "b", parent = "root", kind = NodeKind.INK, text = "Sketch", ink = ink),
            MapNode(id = "c", parent = "a", text = "Text and handwriting"),
            MapNode(id = "d", parent = "a", text = "Local editable files"),
            MapNode(id = "e", parent = "root", text = "Next steps", collapsed = true),
            MapNode(id = "f", parent = "e", text = "Hidden child")
        )))
    }
    @Test fun nativeGraphDrawsLightAndDarkAndExportsAPdfWithoutChangingTheEditableFile() {
        val context = graphContext()
        val doc = example()
        val v = MindMapView(context).apply { document = doc; selected = doc.root.id; layout(0, 0, 1600, 1000); fit() }
        for (dark in listOf(false, true)) {
            v.dark = dark
            val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap))
            val pixels = IntArray(1600 * 1000)
            bitmap.getPixels(pixels, 0, 1600, 0, 0, 1600, 1000)
            assertTrue(pixels.count { it == Color.BLACK } > 1000)
            assertTrue(pixels.count { it == Color.WHITE } > 1000)
            assertEquals(if (dark) Color.BLACK else Color.WHITE, bitmap.getPixel(0, 0))
            System.getProperty("mindmap.evidence")?.let { dir ->
                File(dir).mkdirs()
                File(dir, if (dark) "mind-map-dark.png" else "mind-map-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
        val file = File(context.cacheDir, "example.xmap").apply { writeBytes(MapCodec.encode(doc)) }
        val before = file.readBytes()
        val out = ByteArrayOutputStream()
        MapFiles.exportPdf(context, Uri.fromFile(file).toString(), out) { false }
        assertTrue(out.size() > 100)
        assertTrue(out.toString("ISO-8859-1").startsWith("%PDF"))
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(out.toByteArray()).use { assertEquals(1, it.numberOfPages) }
        assertArrayEquals(before, file.readBytes())
        val copy = File(context.cacheDir, "copy.xmap").apply { writeBytes(before) }
        MapFiles.assignCopy(context, Uri.fromFile(copy))
        val copied = MapFiles.read(context, Uri.fromFile(copy).toString())
        assertNotEquals(doc.id, copied.id)
        assertEquals(doc.nodes, copied.nodes)
        assertArrayEquals(before, file.readBytes())
        val thumb = MapFiles.thumbnail(context, Uri.fromFile(file).toString(), 600, 400)
        assertEquals(600, thumb.width)
        thumb.recycle()
    }
    @Test fun livePreviewEvictionKeepsCompletedInkGeometryCached() {
        val p=MapPainter()
        val completed=MapInk(List(20000) {i->InkPoint((i%300).toDouble(),(i/300).toDouble(),.7,i.toDouble())})
        val image=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image)
        p.drawInk(canvas,listOf(completed))
        assertEquals(1,p.geometryBuildCount)
        repeat(8) {frame->
            p.retainInk(listOf(completed))
            val live=MapInk(listOf(InkPoint(20.0,20.0),InkPoint(30.0+frame,35.0)))
            p.drawInk(canvas,listOf(completed,live))
        }
        assertEquals(9,p.geometryBuildCount)
        p.retainInk(listOf(completed));p.drawInk(canvas,listOf(completed))
        assertEquals(9,p.geometryBuildCount)
        image.recycle()
    }
    @Test fun transientConnectionLineDrawsInBothThemesAndDisappearsWithoutChangingTheGraph() {
        val context=RuntimeEnvironment.getApplication()
        val doc=MindMapDocument(nodes=listOf(MapNode(id="source",parent=null),MapNode(id="target",parent=null,free=true,x=500.0)))
        val bytes=MapCodec.encode(doc)
        val v=MindMapView(context).apply {document=doc;selected="source";layout(0,0,1000,600);fit()}
        fun pixels():IntArray {
            val bitmap=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
            return try {v.draw(Canvas(bitmap));IntArray(1000*600).also {bitmap.getPixels(it,0,1000,0,0,1000,600)}} finally {bitmap.recycle()}
        }
        fun event(action:Int,p:com.xnotes.core.geometry.Pt) {
            val e=android.view.MotionEvent.obtain(100,200,action,p.x.toFloat(),p.y.toFloat(),0)
            try {v.onTouchEvent(e)} finally {e.recycle()}
        }
        for(dark in listOf(false,true)) {
            v.dark=dark;val before=pixels();assertTrue(v.beginConnection("source"))
            event(android.view.MotionEvent.ACTION_DOWN,v.screenPoint(doc.root.bounds.center))
            event(android.view.MotionEvent.ACTION_MOVE,v.screenPoint(com.xnotes.core.geometry.Pt(350.0,36.0)))
            val preview=pixels();assertTrue(before.indices.count {before[it]!=preview[it]}>20)
            assertTrue(preview.all {Color.red(it)==Color.green(it) && Color.green(it)==Color.blue(it)})
            v.cancelConnection();event(android.view.MotionEvent.ACTION_UP,v.screenPoint(doc.root.bounds.center))
            assertArrayEquals(before,pixels());assertArrayEquals(bytes,MapCodec.encode(v.document))
        }
    }
    @Test fun collapsedCountsDrawForVisibleBranchesAndTheirLargestBadgeFitsTheExportBounds() {
        val root=MapNode(id="root",parent=null,x=-300.0,y=-100.0)
        val free=MapNode(id="free",parent=null,free=true,collapsed=true,x=300.0,y=150.0)
        val doc=MindMapDocument(nodes=listOf(root,free)+List(MindMapDocument.MAX_NODES-2) {MapNode(id="child$it",parent="free")})
        doc.validate();val bytes=MapCodec.encode(doc)
        for(dark in listOf(false,true)) {
            val counts=mutableListOf<Int>();val painter=MapPainter().apply {collapsedLabel={counts+=it;"+$it"}}
            val bounds=painter.drawingBounds(doc);counts.clear()
            val image=Bitmap.createBitmap(kotlin.math.ceil(bounds.w).toInt(),kotlin.math.ceil(bounds.h).toInt(),Bitmap.Config.ARGB_8888)
            try {
                val bg=if(dark) Color.BLACK else Color.WHITE
                val canvas=Canvas(image);canvas.drawColor(bg);canvas.translate(-bounds.x.toFloat(),-bounds.y.toFloat())
                painter.draw(canvas,doc,dark,selected="free")
                assertEquals(listOf(MindMapDocument.MAX_NODES-2),counts)
                val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                assertTrue(pixels.any {it!=bg});assertTrue(pixels.all {Color.red(it)==Color.green(it) && Color.green(it)==Color.blue(it)})
                for(x in 0 until image.width) {assertEquals(bg,image.getPixel(x,0));assertEquals(bg,image.getPixel(x,image.height-1))}
                for(y in 0 until image.height) {assertEquals(bg,image.getPixel(0,y));assertEquals(bg,image.getPixel(image.width-1,y))}
                assertEquals(MapPainter().textHeight("Body",220.0),painter.textHeight("Body",220.0),0.0)
            } finally {image.recycle()}
        }
        val plain=MindMapDocument(nodes=listOf(root))
        assertEquals(root.bounds.outset(32.0),MapPainter().drawingBounds(plain))
        assertArrayEquals(bytes,MapCodec.encode(doc))
    }
    @Test fun selectedConnectionPortIsAThemeAwareViewOverlayAndDoesNotChangeExportGeometry() {
        val doc=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null),MapNode(id="free",parent=null,free=true,x=500.0)))
        val bytes=MapCodec.encode(doc);val bounds=MapPainter.bounds(doc)
        val v=MindMapView(RuntimeEnvironment.getApplication()).apply {document=doc;layout(0,0,1000,600);fit()}
        fun pixels():IntArray {
            val image=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
            return try {v.draw(Canvas(image));IntArray(1000*600).also {image.getPixels(it,0,1000,0,0,1000,600)}} finally {image.recycle()}
        }
        for(dark in listOf(false,true)) {
            v.dark=dark;v.selected=null;val plain=pixels()
            v.selected="root";val port=requireNotNull(v.connectionPort());val selected=pixels()
            var changed=0
            for(y in kotlin.math.ceil(port.top).toInt() until port.bottom.toInt()) for(x in kotlin.math.ceil(port.left).toInt() until port.right.toInt()) {
                if(plain[y*1000+x]!=selected[y*1000+x]) changed++
            }
            assertTrue(changed>10);assertTrue(selected.all {Color.red(it)==Color.green(it) && Color.green(it)==Color.blue(it)})
            assertTrue(v.beginConnection("root"));assertNull(v.connectionPort());v.cancelConnection()
        }
        assertEquals(bounds,MapPainter.bounds(doc));assertArrayEquals(bytes,MapCodec.encode(v.document))
    }

}
