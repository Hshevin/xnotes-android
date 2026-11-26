@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.*
import com.xnotes.core.model.*
import com.xnotes.core.pal.NightInk
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.map.ui.*
import com.xnotes.platform.ios.*
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.*
import platform.UIKit.*
import platform.darwin.NSObject
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class CanvasView(private val editor: InkEditor, dark: Boolean, private val fail: () -> Unit) : UIView(frame = CGRectZero.readValue()) {
    private val doc = checkNotNull(editor.session.canvas)
    private val metal = CAMetalLayer()
    private val ink = checkNotNull(MetalInk.createOrNull())
    private val view = CanvasViewport()
    private val paper = doc.background.paperColor ?: if (dark) black else white
    private val night = NightInk.enabled(dark, paper)
    private val cache = mutableMapOf<CanvasItem, List<MetalPart>>()
    private val maps = PanelMapScene(wantsMeshes = true) { cache.remove(it) }
    private val geometry = object : InfiniteDocument.Listener {
        override fun onItemChanged(item: CanvasItem) { cache.remove(item) }
        override fun onItemRemoved(item: CanvasItem) { cache.remove(item) }
    }
    private var items = emptyList<CanvasItem>()
    private var wet = emptyList<Stroke>()
    private var focus = Pt(0.0, 0.0)
    private val panGesture = UIPanGestureRecognizer(this, NSSelectorFromString("pan:"))
    private val pinchGesture = UIPinchGestureRecognizer(this, NSSelectorFromString("pinch:"))
    private val navigationDelegate = object : NSObject(), UIGestureRecognizerDelegateProtocol {
        override fun gestureRecognizer(gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWithGestureRecognizer: UIGestureRecognizer): Boolean =
            setOf(gestureRecognizer, shouldRecognizeSimultaneouslyWithGestureRecognizer) == setOf(panGesture, pinchGesture)
    }
    private val space = object : InkSpace {
        override val origin get() = Pt(view.scrollX, view.scrollY)
        override val density get() = window?.screen?.scale ?: contentScaleFactor
        override val zoom get() = view.zoom / (window?.screen?.scale ?: contentScaleFactor)
        override fun scrollForCaret(delta: Pt, bottomInset: Double) {
            if (delta == Pt.ZERO) return
            view.panByContent(delta.x / zoom, delta.y / zoom); redraw()
        }
        override fun paper(page: Int): Rect? = null
        override fun night(page: Int) = night
        override fun overlayInk(page: Int) = if (this@CanvasView.paper.luminance() > 0.5) black else white
        override fun hasPage(page: Int) = page == 0
        override fun writable(page: Int) = doc.layers.writable
        override fun pageAt(content: Pt) = 0
        override fun toPage(page: Int, content: Pt) = content
        override fun fromPage(page: Int, local: Pt) = local
    }
    private val input = InkInput(editor, space) { enabled -> configure(editor.finger); panGesture.enabled = enabled; pinchGesture.enabled = enabled }
    private val textInput = TextSurface(editor, space) { paper.luminance() < 0.5 }
    private val invalidateItems: () -> Unit = ::refreshItems

    init {
        ink.attach(metal); layer.addSublayer(metal)
        doc.listener = geometry; refreshItems()
        addSubview(input); addSubview(textInput.view)
        addGestureRecognizer(panGesture.apply { allowedTouchTypes = fingerTypes; delegate = navigationDelegate })
        addGestureRecognizer(pinchGesture.apply { allowedTouchTypes = fingerTypes; delegate = navigationDelegate })
        configure(editor.finger); editor.invalidate = invalidateItems
        input.layered = LayeredInk({ doc.layers.writable && (doc.layers.entries.size > 1 || doc.map.state.nodes.isNotEmpty()) }) { next ->
            wet = next[0].orEmpty().onEach { it.layerId = doc.layers.activeId }; redraw()
        }
    }

    private fun refreshItems() {
        val mapItems = maps.update(doc.map.state, doc.map.collapsed, doc.layers.entries.map { it.id }, night)
        items = doc.layers.stacked(doc.items + mapItems); setNeedsLayout()
    }
    fun configure(finger: Boolean) {
        textInput.configure()
        panGesture.minimumNumberOfTouches = if (finger || !textInput.view.hidden || editor.selection?.tracking == true) 2u else 1u
        panGesture.maximumNumberOfTouches = 2u
        updateSidebarCover()
    }
    private fun updateSidebarCover() {
        view.insetRight = if (editor.panels.shown == Panel.LAYERS) Space.sidebar.value * (window?.screen?.scale ?: contentScaleFactor) else 0.0
    }
    fun close() {
        textInput.close(); input.close(); if (doc.listener === geometry) doc.listener = null
        if (editor.invalidate === invalidateItems) editor.invalidate = null
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val width = bounds.useContents { size.width }; val height = bounds.useContents { size.height }
        if (width <= 0 || height <= 0) return
        input.setFrame(bounds)
        textInput.view.setFrame(bounds)
        val density = window?.screen?.scale ?: contentScaleFactor
        updateSidebarCover()
        val first = view.widthPx == 0
        val nextWidth = (width * density).roundToInt(); val nextHeight = (height * density).roundToInt()
        if (view.widthPx != nextWidth || view.heightPx != nextHeight) {
            view.widthPx = nextWidth; view.heightPx = nextHeight
            CATransaction.begin(); CATransaction.setDisableActions(true)
            metal.frame = bounds; metal.drawableSize = CGSizeMake(nextWidth.toDouble(), nextHeight.toDouble())
            CATransaction.commit()
        }
        if (first) {
            val live = editor.liveCanvasView
            val saved = doc.lastView
            if (live != null) {
                view.scrollX = live.originX; view.scrollY = live.originY; view.zoom = live.scale
            } else if (saved != null) view.apply(saved) else {
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
        if (pinchGesture.state != UIGestureRecognizerStateBegan && pinchGesture.state != UIGestureRecognizerStateChanged)
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
        textInput.view.setNeedsDisplay()
        if (view.widthPx == 0 || editor.invalidate !== invalidateItems) return
        val viewport = MetalViewport(view.scrollX, view.scrollY, view.zoom)
        editor.liveCanvasView = viewport
        if (window == null) return
        if (editor.tool.selectsItems) input.setNeedsDisplay()
        try {
            val area = view.visibleContentRect()
            val parts = mutableListOf<MetalPart>()
            grid(area)?.let(parts::add)
            val visible = items.filter { it.paintBounds().intersects(area) }
            cache.keys.retainAll(visible.toSet())
            val stack = if (wet.isEmpty()) visible else doc.layers.stacked(visible.filterNot { it is MapPart } + wet + visible.filterIsInstance<MapPart>())
            for (item in stack) if (doc.layers.visible(item)) {
                parts.addAll(if (item in wet) prepare(item) else cache.getOrPut(item) { prepare(item) })
            }
            ink.present(metal, paper, viewport, parts)
        } catch (_: Exception) { fail() }
    }

    private fun prepare(item: CanvasItem): List<MetalPart> {
        val parts = maps.meshes[item] ?: ItemMesher.mesh(item)?.parts
        if (parts != null && (item as? Labelled)?.label == null && parts.all { it.pass == InkPass.OPAQUE }) {
            return parts.map { part -> MetalMesh(part.mesh, part.color, night) }
        }
        val box = item.paintBounds().outset(1.0)
        val scale = minOf(maxOf(view.zoom, 1.0), 2048.0 / maxOf(box.w, box.h))
        val surface = CgSurfaceFactory.create(ceil(box.w * scale).toInt(), ceil(box.h * scale).toInt())
        return try {
            surface.fill(Rgba(0, 0, 0, 0))
            val renderer = surface.renderer(nightInk = night)
            renderer.scale(surface.width / box.w, surface.height / box.h); renderer.translate(-box.x, -box.y)
            item.paint(renderer)
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
