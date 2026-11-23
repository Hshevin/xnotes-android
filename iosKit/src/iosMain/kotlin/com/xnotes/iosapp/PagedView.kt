@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.Rect
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.pal.NightInk
import com.xnotes.iosapp.ui.theme.Space
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.UIKit.*
import platform.darwin.NSObject
import kotlin.math.roundToInt

internal class PagedView(private val editor: InkEditor, private val dark: Boolean, private val fail: () -> Unit) : UIScrollView(frame = CGRectZero.readValue()) {
    private val doc = checkNotNull(editor.session.note)
    private val painter = PageImages(doc, dark)
    private val paper = UIView()
    private val places: List<Rect>
    private val frames = doc.pages.map { it.paperRect(doc) }
    private var topLeft = Pt.ZERO
    private val input: InkInput
    private val invalidatePages: () -> Unit = {
        visible.values.forEach { it.removeFromSuperview() }; visible.clear(); setNeedsLayout()
    }
    private val visible = mutableMapOf<Int, UIImageView>()
    private val widest: Double
    private var fittedWidth = 0.0
    private var closed = false
    private val zoomDelegate = object : NSObject(), UIScrollViewDelegateProtocol {
        override fun viewForZoomingInScrollView(scrollView: UIScrollView): UIView = paper
    }

    init {
        backgroundColor = if (dark) UIColor.darkGrayColor else UIColor.lightGrayColor
        require(frames.all { it.w.isFinite() && it.h.isFinite() && it.w > 0 && it.h > 0 })
        widest = frames.maxOf { it.w }
        var top = 0.0
        val gap = Space.s16.value.toDouble()
        places = frames.map { page -> Rect((widest - page.w) / 2, top, page.w, page.h).also { top += page.h + gap } }
        paper.setFrame(CGRectMake(0.0, 0.0, widest, top - gap))
        addSubview(paper); setContentSize(CGSizeMake(widest, top - gap))
        delegate = zoomDelegate
        delaysContentTouches = false
        panGestureRecognizer.allowedTouchTypes = fingerTypes; pinchGestureRecognizer?.allowedTouchTypes = fingerTypes
        input = InkInput(editor, object : InkSpace {
            override val origin get() = topLeft
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
        }) { enabled -> panGestureRecognizer.enabled = enabled; pinchGestureRecognizer?.enabled = enabled }
        addSubview(input); configure(editor.finger); editor.invalidate = invalidatePages
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        if (closed) return
        val width = bounds.useContents { size.width }
        if (width <= 0) return
        if (fittedWidth != width) {
            val previous = fittedWidth; fittedWidth = width
            val fit = width / widest
            minimumZoomScale = fit; maximumZoomScale = fit * 8
            zoomScale = if (previous == 0.0) fit else (zoomScale * width / previous).coerceIn(fit, maximumZoomScale)
        }
        val viewport = convertRect(bounds, toView = paper).useContents { Rect(origin.x, origin.y, size.width, size.height) }
        topLeft = Pt(viewport.x, viewport.y); input.setFrame(bounds)
        val indices = places.indices.filter { places[it].intersects(viewport) }.toSet()
        for (index in visible.keys - indices) visible.remove(index)?.removeFromSuperview()
        try {
            for (index in indices) if (index !in visible) {
                val page = places[index]
                val edge = (maxOf(page.w, page.h) * minimumZoomScale * (window?.screen?.scale ?: 1.0)).roundToInt().coerceIn(1, 2560)
                val image = UIImageView(image = painter.page(index, edge))
                image.setFrame(CGRectMake(page.x, page.y, page.w, page.h))
                paper.addSubview(image); visible[index] = image
            }
        } catch (_: Exception) { close(); fail() }
    }

    fun configure(finger: Boolean) { panGestureRecognizer.minimumNumberOfTouches = if (finger) 2u else 1u }
    fun close() {
        if (closed) return
        closed = true; delegate = null; input.close(); painter.close()
        if (editor.invalidate === invalidatePages) editor.invalidate = null
    }

}
