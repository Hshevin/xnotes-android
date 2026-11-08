package com.xnotes.mindmap

import android.content.Context
import com.xnotes.pen.PenTestRunner

class MapInputTest : MapInputContract() {
    @org.junit.Test fun collapsedCountUsesRealLocalizedResourcesAndDrawsInBothThemes()=ui {base->
        for(tag in listOf("en","zh-Hans","zh-CN")) {
            val configuration=android.content.res.Configuration(base.resources.configuration).apply {setLocale(java.util.Locale.forLanguageTag(tag))}
            val context=base.createConfigurationContext(configuration)
            org.junit.Assert.assertEquals("+999",context.getString(com.xnotes.R.string.graph_hidden_nodes_count,999))
            val doc=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,collapsed=true),MapNode(id="child",parent="root")))
            val view=MindMapView(context).apply {document=doc;layout(0,0,800,600);fit()}
            for(dark in listOf(false,true)) {
                view.dark=dark
                val image=android.graphics.Bitmap.createBitmap(800,600,android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    view.draw(android.graphics.Canvas(image))
                    val pixels=IntArray(800*600);image.getPixels(pixels,0,800,0,0,800,600)
                    org.junit.Assert.assertTrue(pixels.any {it==android.graphics.Color.BLACK})
                    org.junit.Assert.assertTrue(pixels.any {it==android.graphics.Color.WHITE})
                    org.junit.Assert.assertEquals(mapOf("root" to 1),view.document.hiddenNodeCounts())
                } finally {image.recycle()}
            }
        }
    }
    override fun ui(block: (Context) -> Unit) {
        val runner = PenTestRunner.instance
        var failure: Throwable? = null
        runner.runOnMainSync { try { block(runner.targetContext) } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
}
