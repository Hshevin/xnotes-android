package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import com.xnotes.canvas.PdfPageFilter
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.*
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.text.*
import java.io.IOException
import kotlin.math.roundToInt

/** The ordinary CPU snapshot painter, shared by recovery and source cards. No live Editor state. */
internal class NativeDocumentPreview(
    context:Context,
    textMeasurer:TextMeasurer?=null,
    private val highlighter:CodeHighlighter?=TreeSitterHighlighter(context.applicationContext).takeIf {TreeSitterNative.loaded},
):AutoCloseable {
    private val textMeasurer:TextMeasurer
    init {
        val app=context.applicationContext
        FontCatalog.ensureInitialized(app);TemplateLibrary.ensureInitialized(app)
        this.textMeasurer=textMeasurer ?: AndroidTextMeasurer()
    }
    private var closed=false
    private var pdf:PdfSource?=null
    private var flowDocument:Document?=null
    private var flowThrough=-1
    private var flowFrame:FlowFrame?=null
    private fun checkOpen() {check(!closed) {"Native preview painter is closed"}}

    fun prepareNote(doc:Document,throughPage:Int,checkActive:()->Unit={}) {
        checkOpen();checkActive()
        if(doc.flow.isEmpty || flowDocument===doc && flowThrough>=throughPage) return
        val theme=HighlightTheme.LIGHT
        val layout=FlowLayout(textMeasurer,MathRendering.Blocking).apply {
            codeBackground={theme.background};autoColor={Rgba(28,28,28)}
            accentColor={Rgba(0,0,0)};ruleColor={Rgba(100,100,100)}
        }
        val spans=HashMap<Paragraph,List<CodeSpan>>()
        val hl=highlighter
        if(hl!=null) {
            var index=0
            while(index<doc.flow.paragraphs.size) {
                checkActive();val lang=doc.flow.paragraphs[index].codeLang
                if(lang.isNullOrEmpty() || !hl.supports(lang)) {index++;continue}
                val start=index
                while(index<doc.flow.paragraphs.size && doc.flow.paragraphs[index].codeLang==lang) index++
                val paragraphs=doc.flow.paragraphs.subList(start,index)
                val highlighted=hl.highlight(paragraphs.joinToString("\n") {it.plainText()},lang)?.sortedBy {it.start} ?: continue
                var offset=0
                for(paragraph in paragraphs) {
                    checkActive();val length=paragraph.length
                    spans[paragraph]=highlighted.mapNotNull {span->
                        val a=(span.start-offset).coerceAtLeast(0);val b=(span.end-offset).coerceAtMost(length)
                        if(b>a) theme.colorFor(span.capture)?.let {CodeSpan(a,b,it)} else null
                    };offset+=length+1
                }
            }
        }
        layout.codeSpans={spans[it]}
        flowFrame=layout.layout(doc.flow,doc.pages.map {PageBox(it.sourceWidth,it.sourceHeight)},doc.dpi,
            throughPage=throughPage,firstPage=if(doc.pages.size>1 && doc.pages[0].cover) 1 else 0)
        checkActive();flowDocument=doc;flowThrough=throughPage
    }
    fun note(doc:Document,index:Int,maxSidePx:Int,region:Rect?=null,checkActive:()->Unit={}):Bitmap? {
        checkOpen();checkActive();require(maxSidePx in 1..2048)
        val page=doc.pages.getOrNull(index) ?: return null
        val cover=footprint(doc,page);val frame=region?.let {intersect(cover,it)} ?: if(region==null) cover else return null
        require(frame.w>0 && frame.h>0 && listOf(frame.x,frame.y,frame.w,frame.h).all {it.isFinite()})
        prepareNote(doc,index,checkActive)
        val scale=maxSidePx/maxOf(frame.w,frame.h)
        val width=(frame.w*scale).roundToInt().coerceIn(1,maxSidePx)
        val height=(frame.h*scale).roundToInt().coerceIn(1,maxSidePx)
        val surface=AndroidRasterSurface.create(width,height);var transferred=false
        try {
            surface.fill(page.resolvedPageColor(doc,null) ?: Rgba(255,255,255))
            val r=surface.renderer();r.scale(scale,scale);r.translate(-frame.left,-frame.top);r.clipRect(frame)
            checkActive();TemplateLibrary.paint(r,doc,page,cover,frame,maxOf(1.0,maxOf(width,height)/300.0)/scale)
            page.pdfKey?.let {key->
                val file=doc.pdfFile ?: throw IOException("PDF original is unavailable")
                val source=pdf ?: PdfSource.open(file).also {pdf=it}
                require(source.file.canonicalFile==file.canonicalFile) {"Preview PDF changed"}
                checkActive()
                if(source.pageCount<=0) throw IOException("PDF original could not be opened")
                val sw=page.sourceWidth;val sh=page.sourceHeight
                val pdfScale=minOf(scale,maxSidePx.toDouble()/maxOf(sw,sh))
                val pw=(sw*pdfScale).toInt().coerceIn(1,maxSidePx);val ph=(sh*pdfScale).toInt().coerceIn(1,maxSidePx)
                checkActive()
                val raster=PdfSource.withPriority(PdfPriority.THUMBNAIL) {
                    checkActive()
                    source.renderPage(key,pw,ph,PdfPageFilter.NONE,page.markups)
                } ?: throw IOException("PDF page could not be rendered")
                try {checkActive();Rooms.drawBanded(r,raster,Rect(0.0,0.0,sw,sh),page.rooms,sw,sh)} finally {raster.recycle()}
            }
            checkActive()
            if(page.pdfPage==null) MarkupPainter.paint(r,page.markups,doc.dpi/72.0)
            PageLayers.paint(page.layers.ordered(page.items.toList()),{
                checkActive();flowFrame?.takeIf {flowDocument===doc}?.let {FlowPainter.paintPage(r,it,index,Rect(0.0,0.0,page.width,page.height))}
            }) {checkActive();it.paint(r)}
            checkActive();transferred=true;return surface.bitmap
        } finally {if(!transferred) surface.recycle()}
    }
    fun canvas(doc:InfiniteDocument,widthPx:Int,heightPx:Int,checkActive:()->Unit={}):Bitmap {
        checkOpen();checkActive();require(widthPx in 1..2048 && heightPx in 1..2048)
        val surface=AndroidRasterSurface.create(widthPx,heightPx);var transferred=false
        try {
            surface.fill(doc.background.paperColor ?: Rgba(255,255,255))
            val items=doc.layers.ordered(doc.items.toList())
            val bounds=items.map {checkActive();it.paintBounds()}.reduceOrNull {a,b->a.union(b)}
            if(bounds!=null && bounds.w>0 && bounds.h>0) {
                val margin=minOf(widthPx,heightPx)*0.06
                val scale=minOf((widthPx-2*margin)/bounds.w,(heightPx-2*margin)/bounds.h).coerceAtMost(1.0)
                val r=surface.renderer()
                r.translate(margin+(widthPx-2*margin-bounds.w*scale)/2,margin+(heightPx-2*margin-bounds.h*scale)/2)
                r.scale(scale,scale);r.translate(-bounds.left,-bounds.top)
                val bg=doc.background
                val period=bg.clampedSpacing*com.xnotes.core.infinite.BackgroundPattern.levelMultiplier(bg.clampedSpacing,scale)
                paintPagePattern(r,bg.pattern,bg.patternColor,period,bounds,bounds)
                for(item in items) {checkActive();item.paint(r)}
            }
            checkActive();transferred=true;return surface.bitmap
        } finally {if(!transferred) surface.recycle()}
    }
    override fun close() {if(closed) return;closed=true;pdf?.close();pdf=null;flowFrame=null;flowDocument=null}
    companion object {
        fun footprint(doc:Document,page:Page):Rect=page.insets(doc).let {Rect(-it.left,-it.top,it.left+page.width+it.right,it.top+page.height+it.bottom)}
        fun intersect(a:Rect,b:Rect):Rect? {
            require(listOf(b.x,b.y,b.w,b.h).all {it.isFinite()} && b.w>0 && b.h>0)
            val left=maxOf(a.left,b.left);val top=maxOf(a.top,b.top);val right=minOf(a.right,b.right);val bottom=minOf(a.bottom,b.bottom)
            return if(right>left && bottom>top) Rect(left,top,right-left,bottom-top) else null
        }
    }
}
