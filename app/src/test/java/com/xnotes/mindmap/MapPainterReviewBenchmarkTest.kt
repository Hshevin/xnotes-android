package com.xnotes.mindmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.xnotes.core.geometry.Pt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest

/** Fixed-scene software Android Canvas evidence; never a hardware pen-latency benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapPainterReviewBenchmarkTest {
    private class CountedCanvas(bitmap:Bitmap):Canvas(bitmap) {
        var rectangles=0;var paths=0;var lines=0
        override fun drawRect(left:Float,top:Float,right:Float,bottom:Float,paint:Paint) {rectangles++;super.drawRect(left,top,right,bottom,paint)}
        override fun drawPath(path:Path,paint:Paint) {paths++;super.drawPath(path,paint)}
        override fun drawLine(x1:Float,y1:Float,x2:Float,y2:Float,paint:Paint) {lines++;super.drawLine(x1,y1,x2,y2,paint)}
        fun reset() {rectangles=0;paths=0;lines=0}
    }
    private fun fixture()=MindMapDocument(id="d41f635a-0ac8-4e6b-94f3-b505375ac5fb",nodes=List(1000) {i->
        val ink=if(i%5<2) List(3) {stroke->MapInk(List(64) {point->InkPoint(
            12.0+(point%32)*3,12.0+stroke*16+(point/32)*5+kotlin.math.sin(point*.3)*3,
            .6+.2*kotlin.math.cos(point*.2),point.toDouble())},2.0)} else emptyList()
        MapNode(id="n$i",parent=when {i==0->null;i<50->"n${i-1}";else->"n${i-50}"},
            kind=if(ink.isEmpty()) NodeKind.TEXT else NodeKind.INK,text="Topic $i",ink=ink,
            width=128.0,height=96.0,x=(i%50)*190.0,y=(i/50)*150.0)
    },relations=List(200) {i->MapRelation(id="e$i",from="n${i*4}",to="n${i*4+3}",label="Link $i",
        kind=if(i%2==0) RelationKind.RELATED else RelationKind.DEPENDS_ON)})
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun pixelHash(bitmap:Bitmap):String {
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val bytes=ByteArray(pixels.size*4)
        pixels.forEachIndexed {i,value->repeat(4) {offset->bytes[i*4+offset]=(value ushr (offset*8)).toByte()}}
        return hash(bytes)
    }
    @Test fun fixedThousandNodeSceneReportsViewportAndOverviewFrameCostWithoutAlteringContent() {
        val doc=fixture();doc.validate();val encoded=MapCodec.encode(doc);val identity=hash(encoded)
        val context=RuntimeEnvironment.getApplication();val density=context.resources.displayMetrics.density
        for(overview in listOf(false,true)) for(dark in listOf(false,true)) for(host in listOf("painter","view")) {
            val bitmap=Bitmap.createBitmap(960,640,Bitmap.Config.ARGB_8888)
            try {
                val canvas=CountedCanvas(bitmap);val painter=MapPainter()
                val zoom=if(overview) .095f/density else .8f/density
                val pan=if(overview) Pt(8.0,100.0) else Pt(-3450.0,-900.0)
                val view=if(host=="view") MindMapView(context).apply {
                    automaticInitialFit=false;document=doc;this.dark=dark;layout(0,0,960,640)
                    restoreViewport(MindMapView.Viewport(zoom,pan.x.toFloat(),pan.y.toFloat()),960,640,density)
                } else null
                fun frame() {
                    canvas.drawColor(if(dark) Color.BLACK else Color.WHITE);canvas.reset()
                    if(view!=null) view.draw(canvas) else {
                        canvas.save();canvas.translate(pan.x.toFloat(),pan.y.toFloat());canvas.scale(zoom*density,zoom*density)
                        painter.draw(canvas,doc,dark);canvas.restore()
                    }
                }
                repeat(5) {frame()}
                val samples=List(30) {val start=System.nanoTime();frame();(System.nanoTime()-start)/1_000_000.0}.sorted()
                val result=JSONObject().put("fixtureSha256",identity).put("nodes",doc.nodes.size)
                    .put("inkStrokes",doc.nodes.sumOf {it.ink.size}).put("relations",doc.relations.size)
                    .put("host",host).put("camera",if(overview) "overview" else "viewport").put("dark",dark)
                    .put("warmupFrames",5).put("sampleFrames",samples.size).put("medianMs",samples[samples.size/2])
                    .put("p95Ms",samples[(samples.size*.95).toInt().coerceAtMost(samples.lastIndex)])
                    .put("drawRect",canvas.rectangles).put("drawPath",canvas.paths).put("drawLine",canvas.lines)
                    .put("geometryBuilds",if(view==null) painter.geometryBuildCount else JSONObject.NULL)
                    .put("pixelSha256",pixelHash(bitmap))
                println("MAP_RENDER_REVIEW_BENCHMARK $result")
            } finally {bitmap.recycle()}
        }
        assertArrayEquals(encoded,MapCodec.encode(doc))
    }
}
