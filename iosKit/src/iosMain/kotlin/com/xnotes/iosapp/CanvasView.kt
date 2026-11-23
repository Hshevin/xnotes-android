@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.*
import com.xnotes.core.model.*
import com.xnotes.core.pal.NightInk
import com.xnotes.core.pal.NightInkRenderer
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Renderer
import com.xnotes.core.vector.VectorContour
import com.xnotes.map.MapLayout
import com.xnotes.map.NodeMetrics
import com.xnotes.map.ui.*
import com.xnotes.platform.ios.*
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.*
import platform.UIKit.*
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class CanvasView(private val doc: InfiniteDocument, dark: Boolean, private val fail: () -> Unit) : UIView(frame = CGRectZero.readValue()) {
    private val metal = CAMetalLayer()
    private val ink = checkNotNull(MetalInk.createOrNull())
    private val view = CanvasViewport()
    private val paper = doc.background.paperColor ?: if (dark) black else white
    private val night = NightInk.enabled(dark, paper)
    private val projected = mutableMapOf<CanvasItem, List<MeshPart>>()
    private val items: List<CanvasItem>
    private val cache = mutableMapOf<CanvasItem, List<MetalPart>>()
    private var focus = Pt(0.0, 0.0)

    init {
        ink.attach(metal); layer.addSublayer(metal)
        val titles = MapTitles(CgTextMeasurer)
        val metrics = NodeMetrics(titles)
        val map = MapProjection(titles, CgTextMeasurer, metrics)
        val state = doc.map.state
        val layers = doc.layers.entries.map { it.id }
        map.update(state, MapLayout.compute(state, doc.map.collapsed, metrics), object : MapSceneSink {
            override fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect) { projected[item] = parts }
            override fun remove(item: CanvasItem) { projected.remove(item) }
        }, night = night, layerFor = { id -> id?.takeIf { it in layers } ?: layers.firstOrNull() })
        items = doc.layers.ordered(doc.items.toList() + map.items)
        addGestureRecognizer(UIPanGestureRecognizer(this, NSSelectorFromString("pan:")).apply { maximumNumberOfTouches = 1u })
        addGestureRecognizer(UIPinchGestureRecognizer(this, NSSelectorFromString("pinch:")))
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val width = bounds.useContents { size.width }; val height = bounds.useContents { size.height }
        if (width <= 0 || height <= 0) return
        val density = window?.screen?.scale ?: contentScaleFactor
        val first = view.widthPx == 0
        view.widthPx = (width * density).roundToInt(); view.heightPx = (height * density).roundToInt()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        metal.frame = bounds; metal.drawableSize = CGSizeMake(view.widthPx.toDouble(), view.heightPx.toDouble())
        CATransaction.commit()
        if (first) {
            val saved = doc.lastView
            if (saved != null) view.apply(saved) else {
                val extent = items.map { it.paintBounds() }.reduceOrNull(Rect::union)
                if (extent == null) view.centerOn(0.0, 0.0) else {
                    view.fit(extent, minOf(view.widthPx, view.heightPx) * 0.08)
                    view.zoom = minOf(view.zoom, 4.0 * density); view.centerOn(extent.centerX, extent.centerY)
                }
            }
        }
        redraw()
    }

    @ObjCAction fun pan(sender: UIPanGestureRecognizer) {
        val density = window?.screen?.scale ?: return
        sender.translationInView(this).useContents { view.panByViewport(x * density, y * density) }
        sender.setTranslation(CGPointZero.readValue(), this); redraw()
    }

    @ObjCAction fun pinch(sender: UIPinchGestureRecognizer) {
        val density = window?.screen?.scale ?: return
        val center = sender.locationInView(this).useContents { Pt(x * density, y * density) }
        if (sender.state == UIGestureRecognizerStateBegan) focus = center
        view.zoomAround(focus.x, focus.y, view.zoom * sender.scale)
        view.panByViewport(center.x - focus.x, center.y - focus.y)
        focus = center; sender.scale = 1.0; redraw()
    }

    private fun redraw() {
        if (window == null || view.widthPx == 0) return
        try {
            val area = view.visibleContentRect()
            val parts = mutableListOf<MetalPart>()
            grid(area)?.let(parts::add)
            val visible = items.filter { it.paintBounds().intersects(area) }
            cache.keys.retainAll(visible.toSet())
            for (item in visible) parts.addAll(cache.getOrPut(item) { prepare(item) })
            ink.present(metal, paper, MetalViewport(view.scrollX, view.scrollY, view.zoom), parts)
        } catch (_: Exception) { fail() }
    }

    private fun prepare(item: CanvasItem): List<MetalPart> {
        val parts = projected[item] ?: ItemMesher.mesh(item)?.parts
        if (parts != null && (item as? Labelled)?.label == null && parts.all { it.pass == InkPass.OPAQUE }) {
            return parts.map { part -> MetalMesh(part.mesh, part.color, night) }
        }
        val box = item.paintBounds().outset(1.0)
        val scale = minOf(maxOf(view.zoom, 1.0), 2048.0 / maxOf(box.w, box.h))
        val surface = CgSurfaceFactory.create(ceil(box.w * scale).toInt(), ceil(box.h * scale).toInt())
        return try {
            surface.fill(Rgba(0, 0, 0, 0))
            val raw = surface.renderer()
            raw.scale(surface.width / box.w, surface.height / box.h); raw.translate(-box.x, -box.y)
            val renderer = if (night) NightInkRenderer(raw) else raw
            item.paint(if (night && item is MapPart) object : Renderer by renderer {
                override fun fillPath(contours: List<VectorContour>, color: Rgba, rule: FillRule) =
                    raw.fillPath(contours, NightInk.color(color), rule)
                override fun fillRect(rect: Rect, color: Rgba) = raw.fillRect(rect, NightInk.color(color))
            } else renderer)
            listOf(ink.picture(surface, box))
        } finally { surface.recycle() }
    }

    private fun grid(area: Rect): MetalMesh? {
        val background = doc.background
        if (background.pattern == PagePattern.NONE) return null
        val spacing = background.clampedSpacing * BackgroundPattern.levelMultiplier(background.clampedSpacing, view.zoom)
        val startX = area.x - BackgroundPattern.phase(area.x, spacing)
        val startY = area.y - BackgroundPattern.phase(area.y, spacing)
        val columns = ceil(area.w / spacing).toInt() + 1; val rows = ceil(area.h / spacing).toInt() + 1
        val thickness = BackgroundPattern.LINE_WIDTH_PX / view.zoom
        val builder = MeshBuilder()
        if (background.pattern == PagePattern.DOTS) {
            for (row in 0..rows) for (column in 0..columns)
                builder.circle(startX + column * spacing, startY + row * spacing, BackgroundPattern.DOT_RADIUS_PX / view.zoom, thickness / 4)
        } else {
            for (row in 0..rows) builder.rect(area.x, startY + row * spacing, area.w, thickness)
            if (background.pattern == PagePattern.GRID) for (column in 0..columns)
                builder.rect(startX + column * spacing, area.y, thickness, area.h)
        }
        return MetalMesh(builder.build(), background.patternColor)
    }
}
