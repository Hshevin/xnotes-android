@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.Rect
import com.xnotes.iosapp.ui.theme.Space
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.UIKit.*
import platform.darwin.NSObject
import kotlin.math.roundToInt

internal class PagedView(session: ViewerSession, dark: Boolean, private val fail: () -> Unit) : UIScrollView(frame = CGRectZero.readValue()) {
    private val doc = checkNotNull(session.note)
    private val painter = PageImages(doc, dark, session.assets)
    private val paper = UIView()
    private val places: List<Rect>
    private val visible = mutableMapOf<Int, UIImageView>()
    private val widest: Double
    private var fittedWidth = 0.0
    private var closed = false
    private val zoomDelegate = object : NSObject(), UIScrollViewDelegateProtocol {
        override fun viewForZoomingInScrollView(scrollView: UIScrollView): UIView = paper
    }

    init {
        backgroundColor = if (dark) UIColor.darkGrayColor else UIColor.lightGrayColor
        val frames = doc.pages.map { it.paperRect(doc) }
        require(frames.all { it.w.isFinite() && it.h.isFinite() && it.w > 0 && it.h > 0 })
        widest = frames.maxOf { it.w }
        var top = 0.0
        val gap = Space.s16.value.toDouble()
        places = frames.map { page -> Rect((widest - page.w) / 2, top, page.w, page.h).also { top += page.h + gap } }
        paper.setFrame(CGRectMake(0.0, 0.0, widest, top - gap))
        addSubview(paper); setContentSize(CGSizeMake(widest, top - gap))
        delegate = zoomDelegate
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

    fun close() { closed = true; delegate = null; painter.close() }

}
