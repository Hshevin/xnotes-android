@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.Rect
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Page
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.NightInk
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.UIKit.*
import platform.darwin.NSObject
import kotlin.math.roundToInt

internal class PagedView(private val editor: InkEditor, private val dark: Boolean, private val fail: () -> Unit) : UIScrollView(frame = CGRectZero.readValue()) {
    private val doc = checkNotNull(editor.session.note)
    private val painter = PageImages(doc, dark)
    private val paper = UIView()
    private var placement = PagePlacement(doc)
    private val places get() = placement.places
    private val frames get() = placement.frames
    private var topLeft = Pt.ZERO
    private val input: InkInput
    private val textInput: TextSurface
    private var wet = emptyMap<Int, List<Stroke>>()
    private var panelFocus: Page? = null
    private val wetViews = mutableMapOf<Int, PageWetImage>()
    private val invalidatePages: () -> Unit = {
        if (placement.pages != doc.pages) {
            val oldWidth = placement.width
            placement = PagePlacement(doc); sizePaper()
            if (oldWidth != placement.width) fittedWidth = 0.0
            if (panelFocus !in doc.pages) panelFocus = null
            if (editor.panels.page !in doc.pages) editor.panels.page = doc.pages.first()
        }
        painter.invalidateFlow()
        visible.values.forEach { it.removeFromSuperview() }; visible.clear(); wetViews.clear(); setNeedsLayout()
    }
    private val visible = mutableMapOf<Int, UIImageView>()
    private fun sizePaper() {
        val width = placement.width; val height = placement.height
        paper.setBounds(CGRectMake(0.0, 0.0, width, height))
        paper.center = CGPointMake(width * zoomScale / 2, height * zoomScale / 2)
        setContentSize(CGSizeMake(width * zoomScale, height * zoomScale))
    }
    private val jump: (Page) -> Unit = { page ->
        panelFocus = page
        layoutIfNeeded()
        places.getOrNull(doc.pages.indexOf(page))?.let { rect ->
            val maxY = contentSize.useContents { height } - bounds.useContents { size.height }
            setContentOffset(CGPointMake(contentOffset.useContents { x }, ((rect.y - placement.gap) * zoomScale).coerceIn(0.0, maxY.coerceAtLeast(0.0))))
        }
    }
    private var fittedWidth = 0.0
    private var closed = false
    private val zoomDelegate = object : NSObject(), UIScrollViewDelegateProtocol {
        override fun viewForZoomingInScrollView(scrollView: UIScrollView): UIView = paper
        override fun scrollViewWillBeginDragging(scrollView: UIScrollView) { panelFocus = null }
        override fun scrollViewWillBeginZooming(scrollView: UIScrollView, withView: UIView?) { panelFocus = null }
    }

    init {
        backgroundColor = if (dark) UIColor.darkGrayColor else UIColor.lightGrayColor
        sizePaper(); addSubview(paper); editor.panels.scrollTo = jump
        delegate = zoomDelegate
        delaysContentTouches = false
        panGestureRecognizer.allowedTouchTypes = fingerTypes; pinchGestureRecognizer?.allowedTouchTypes = fingerTypes
        val space = object : InkSpace {
            override val origin get() = topLeft
            override val density get() = window?.screen?.scale ?: contentScaleFactor
            override val zoom get() = zoomScale
            override fun paper(page: Int) = places[page]
            override fun hasPage(page: Int) = page in places.indices
            override fun writable(page: Int) = doc.pages[page].layers.writable
            override fun pageAt(content: Pt) = places.indexOfFirst { it.contains(content) }.takeIf { it >= 0 }
            private fun offset(page: Int) = Pt(places[page].x - frames[page].x, places[page].y - frames[page].y)
            override fun toPage(page: Int, content: Pt) = content - offset(page)
            override fun fromPage(page: Int, local: Pt) = local + offset(page)
            override fun night(page: Int): Boolean {
                val model = doc.pages[page]
                return NightInk.enabled(dark, model.resolvedPageColor(doc, if (dark) black else white) ?: white, model.pdfPage != null)
            }
            override fun overlayInk(page: Int) = doc.pages[page].let { model ->
                val color = model.resolvedPageColor(doc, if (dark) black else white) ?: white
                if (model.pdfPage != null || color.luminance() > 0.5) black else white
            }
        }
        input = InkInput(editor, space) { enabled -> configure(editor.finger); panGestureRecognizer.enabled = enabled; pinchGestureRecognizer?.enabled = enabled }
        textInput = TextSurface(editor, space, painter) { index -> doc.pages[index].let { page ->
            page.pdfPage == null && (page.resolvedPageColor(doc, if (dark) black else white) ?: white).luminance() < 0.5
        } }
        addSubview(input); addSubview(textInput.view); configure(editor.finger); editor.invalidate = invalidatePages
        input.layered = LayeredInk({ index -> doc.pages[index].layers.let { it.entries.size > 1 && it.writable } }) { next ->
            wet = next; setNeedsLayout()
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        if (closed) return
        val width = bounds.useContents { size.width }
        if (width <= 0) return
        if (fittedWidth != width) {
            val previous = fittedWidth; fittedWidth = width
            val fit = width / placement.width
            minimumZoomScale = fit; maximumZoomScale = fit * 8
            zoomScale = if (previous == 0.0) fit else (zoomScale * width / previous).coerceIn(fit, maximumZoomScale)
        }
        val viewport = convertRect(bounds, toView = paper).useContents { Rect(origin.x, origin.y, size.width, size.height) }
        topLeft = Pt(viewport.x, viewport.y); input.setFrame(bounds)
        if (editor.tool.selectsItems) input.setNeedsDisplay()
        textInput.view.setFrame(bounds); textInput.view.setNeedsDisplay()
        if (!editor.active) editor.panels.page = panelFocus ?: doc.pages[places.indexOfFirst { it.bottom >= viewport.centerY }.let { if (it < 0) places.lastIndex else it }]
        val indices = places.indices.filter { places[it].intersects(viewport) }.toSet()
        for (index in (visible.keys - indices) + (wetViews.keys - wet.keys)) {
            visible.remove(index)?.removeFromSuperview(); wetViews.remove(index)
        }
        try {
            for (index in indices) {
                val strokes = wet[index]; val overlay = wetViews[index]
                if (index in visible && (strokes == null || overlay != null)) {
                    if (strokes != null && overlay != null) { overlay.strokes = strokes; overlay.setNeedsDisplay() }; continue
                }
                val page = places[index]
                val edge = (maxOf(page.w, page.h) * minimumZoomScale * (window?.screen?.scale ?: 1.0)).roundToInt().coerceIn(1, 2560)
                visible.remove(index)?.removeFromSuperview()
                val image = UIImageView(image = painter.page(index, edge, if (strokes != null) false else null))
                image.setFrame(CGRectMake(page.x, page.y, page.w, page.h))
                if (strokes != null) {
                    val model = doc.pages[index]; val paperColor = model.resolvedPageColor(doc, if (dark) black else white) ?: white
                    val layer = PageWetImage(image.bounds, frames[index], NightInk.enabled(dark, paperColor, model.pdfPage != null), painter.page(index, edge, true))
                    layer.contentScaleFactor = edge / maxOf(page.w, page.h); layer.strokes = strokes
                    image.addSubview(layer); wetViews[index] = layer
                }
                paper.addSubview(image); visible[index] = image
            }
        } catch (_: Exception) { close(); fail() }
    }

    fun configure(finger: Boolean) {
        textInput.configure(); panGestureRecognizer.minimumNumberOfTouches = if (finger || editor.selection?.tracking == true || !textInput.view.hidden) 2u else 1u
    }
    fun close() {
        if (closed) return
        closed = true; delegate = null; textInput.close(); input.close(); painter.close()
        if (editor.panels.scrollTo === jump) editor.panels.scrollTo = null
        if (editor.invalidate === invalidatePages) editor.invalidate = null
    }

}
