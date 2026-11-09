package com.xnotes.mindmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.xnotes.core.model.Stroke
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapPainterAllocationReviewTest {
    @Test fun anUnclippedLocalizedSourceLabelStaysVisibleWhenItsNarrowNodeBodyHasLeftTheViewport() {
        val source=MapSource(documentId="00000000-0000-0000-0000-000000000001",fileName="Source",status=SourceStatus.UNAVAILABLE)
        val doc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,x=-120.0,y=70.0,width=48.0,height=48.0,sources=listOf(source))))
        doc.validate()
        for(dark in listOf(false,true)) {
            val bitmap=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888)
            try {
                val paper=if(dark) Color.BLACK else Color.WHITE
                val canvas=Canvas(bitmap);canvas.drawColor(paper);var callbacks=0
                val painter=MapPainter().apply {sourceLabel={callbacks++;"A long localized source count label"}}
                painter.draw(canvas,doc,dark)
                assertTrue("The source label is drawn outside the node's inner clip",(90..120).any {y->(0..220).any {x->bitmap.getPixel(x,y)!=paper}})
                assertEquals("Culling and painting must share the actual formatted label",1,callbacks)
            } finally {bitmap.recycle()}
        }
    }
    @Test fun clippingRetainsCrossingLinesLabelsAndABadgeWhoseNodeBodyIsOffscreen() {
        val doc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,x=-500.0,y=20.0,width=100.0,height=80.0),
            MapNode(id="right",parent="r",x=400.0,y=20.0,width=100.0,height=80.0),
            MapNode(id="folded",parent=null,free=true,x=-110.0,y=100.0,width=100.0,height=48.0,collapsed=true))+
            List(80) {MapNode(id="hidden$it",parent="folded")},
            relations=listOf(MapRelation(id="crossing",from="r",to="right",label="Crossing label",kind=RelationKind.DEPENDS_ON)))
        doc.validate()
        for(dark in listOf(false,true)) {
            val bitmap=Bitmap.createBitmap(240,180,Bitmap.Config.ARGB_8888)
            try {
                val paper=if(dark) Color.BLACK else Color.WHITE
                val ink=if(dark) Color.WHITE else Color.BLACK
                val canvas=Canvas(bitmap);canvas.drawColor(paper)
                MapPainter().apply {collapsedLabel={"+$it"}}.draw(canvas,doc,dark)
                assertEquals("Hierarchy endpoints may both be offscreen",ink,bitmap.getPixel(100,60))
                assertTrue("Relation labels remain visible without their node bodies",(30..55).any {y->(1..150).any {x->bitmap.getPixel(x,y)!=paper}})
                assertTrue("A folded badge extends past the offscreen node's right edge",(136..160).any {y->(0..12).any {x->bitmap.getPixel(x,y)!=paper}})
            } finally {bitmap.recycle()}
        }
    }

    @Test fun documentBasedInkRetentionKeepsCompletedGeometryAndEvictsEveryPriorLivePreview() {
        val completed=MapInk(listOf(InkPoint(10.0,10.0),InkPoint(90.0,80.0)))
        val doc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,kind=NodeKind.INK,ink=listOf(completed))))
        val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        try {
            val painter=MapPainter();val canvas=Canvas(bitmap)
            painter.retainInk(doc);painter.drawInk(canvas,listOf(completed))
            repeat(8) {frame->
                painter.retainInk(doc)
                painter.drawInk(canvas,listOf(completed,MapInk(listOf(InkPoint(20.0,20.0),InkPoint(30.0+frame,35.0)))))
            }
            assertEquals(9,painter.geometryBuildCount)
            painter.retainInk(doc);painter.drawInk(canvas,listOf(completed));assertEquals(9,painter.geometryBuildCount)
            val field=MapPainter::class.java.getDeclaredField("strokes").apply {isAccessible=true}
            assertEquals("Temporary preview geometry is released even while the document instance stays fixed",1,(field.get(painter) as Map<*,*>).size)
        } finally {bitmap.recycle()}
    }
    @Test fun immutableDocumentVisibilityIsReusedAndExpandedSetMutationCannotReturnStaleMembers() {
        val doc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="b",parent="r",collapsed=true),MapNode(id="c",parent="b")))
        val expanded=mutableSetOf<String>();val ordinary=doc.visible(expanded)
        assertSame(ordinary,doc.visible(expanded))
        expanded+="b";val shown=doc.visible(expanded);assertEquals(listOf("r","b","c"),shown.map {it.id})
        assertSame(shown,doc.visible(expanded));expanded.clear()
        assertEquals(listOf("r","b"),doc.visible(expanded).map {it.id});assertEquals(listOf("r","b","c"),shown.map {it.id})
        val copy=doc.copy();assertEquals(doc.visible(),copy.visible());assertNotSame(doc.visible(),copy.visible())
    }
    @Test fun repeatedlyUsedTextLayoutSurvivesColdCacheChurnAndCacheStaysBounded() {
        val painter=MapPainter();painter.textHeight("Hot text",200.0)
        val field=MapPainter::class.java.getDeclaredField("layouts").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") val cache=field.get(painter) as Map<Pair<String,Int>,*>
        val hot=requireNotNull(cache["Hot text" to 168])
        repeat(MindMapDocument.MAX_NODES*2+200) {index->painter.textHeight("Cold $index",200.0);painter.textHeight("Hot text",200.0)}
        assertSame(hot,cache["Hot text" to 168]);assertTrue(cache.size<=MindMapDocument.MAX_NODES*2)
    }
    @Test fun stableInkDoesNotAllocateANewStyleUntilTheActualDisplayColorChanges() {
        val ink=MapInk(listOf(InkPoint(10.0,10.0),InkPoint(90.0,80.0)),3.0)
        val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap);val painter=MapPainter();painter.drawInk(canvas,listOf(ink),Color.BLACK)
            val field=MapPainter::class.java.getDeclaredField("strokes").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val strokes=field.get(painter) as Map<MapInk,Stroke>
            val stroke=requireNotNull(strokes[ink]);val black=stroke.config
            painter.drawInk(canvas,listOf(ink),Color.BLACK);assertSame(black,stroke.config)
            painter.drawInk(canvas,listOf(ink),Color.WHITE);val white=stroke.config;assertNotSame(black,white)
            painter.drawInk(canvas,listOf(ink),Color.WHITE);assertSame(white,stroke.config);assertEquals(1,painter.geometryBuildCount)
        } finally {bitmap.recycle()}
    }
}
