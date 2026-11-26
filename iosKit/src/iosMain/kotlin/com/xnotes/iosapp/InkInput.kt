@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.edit.PageSpace
import com.xnotes.pen.pencil.*
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.Foundation.NSSelectorFromString
import platform.UIKit.*
import platform.darwin.NSObject

internal interface InkSpace : PageSpace {
    val origin: Pt
    val density get() = 1.0
    fun paper(page: Int): Rect?
    fun night(page: Int): Boolean
    fun scrollForCaret(delta: Pt, bottomInset: Double) {}
    fun overlayInk(page: Int) = if (night(page)) white else black
}

internal val fingerTypes = listOf(UITouchTypeDirect)

internal class InkInput(private val editor: InkEditor, private val space: InkSpace,
                        private val navigate: (Boolean) -> Unit) : UIView(frame = CGRectZero.readValue()) {
    private var owner: UITouch? = null
    private var ink: InkGesture? = null
    var layered: LayeredInk? = null
    private fun refreshInk() { layered?.update(ink); setNeedsDisplay() }
    private var hover: Pt? = null
    private var detached = false
    private val selection = ItemSelection(editor, space, ::setNeedsDisplay)
    private var selecting = false
    private val pencilDelegate = object : NSObject(), UIPencilInteractionDelegateProtocol {
        override fun pencilInteraction(interaction: UIPencilInteraction, didReceiveTap: UIPencilInteractionTap) {
            editor.gesture(PencilGesture.DOUBLE_TAP, preferred(UIPencilInteraction.preferredTapAction))
        }
        override fun pencilInteraction(interaction: UIPencilInteraction, didReceiveSqueeze: UIPencilInteractionSqueeze) {
            if (didReceiveSqueeze.phase == UIPencilInteractionPhase.UIPencilInteractionPhaseEnded)
                editor.gesture(PencilGesture.SQUEEZE, preferred(UIPencilInteraction.preferredSqueezeAction))
        }
    }

    init {
        editor.selection = selection
        opaque = false; backgroundColor = UIColor.clearColor; multipleTouchEnabled = true
        addInteraction(UIPencilInteraction().apply { delegate = pencilDelegate })
        addGestureRecognizer(UIHoverGestureRecognizer(this, NSSelectorFromString("hovering:")).apply {
            allowedTouchTypes = listOf(UITouchTypePencil); cancelsTouchesInView = false
        })
    }

    @ObjCAction fun hovering(sender: UIHoverGestureRecognizer) {
        hover = if (owner == null && (sender.state == UIGestureRecognizerStateBegan || sender.state == UIGestureRecognizerStateChanged))
            sender.locationInView(this).useContents { Pt(x, y) } else null
        setNeedsDisplay()
    }

    private fun sample(touch: UITouch) = touch.toPencilSample(this) { space.origin + it / space.zoom }
    private fun actual(touch: UITouch, event: UIEvent?) = event?.coalescedTouchesForTouch(touch)?.filterIsInstance<UITouch>()
        .orEmpty().ifEmpty { listOf(touch) }.map(::sample)
    private fun predicted(touch: UITouch, event: UIEvent?) = event?.predictedTouchesForTouch(touch)?.filterIsInstance<UITouch>().orEmpty().map(::sample)

    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
        if (detached || editor.tool == Tool.TEXT || (owner?.type == UITouchTypePencil && !selecting)) return
        val candidates = touches.filterIsInstance<UITouch>()
        val pencil = candidates.firstOrNull { it.type == UITouchTypePencil }
        val fingers = withEvent?.touchesForView(this)?.filterIsInstance<UITouch>().orEmpty().count { it.type == UITouchTypeDirect }
        if (fingers >= 2 && editor.tool.selectsItems) {
            if (selecting) finish(emptyList(), true)
            return
        }
        if (selecting && owner?.type == UITouchTypePencil) return
        if (owner != null && (pencil != null || fingers > 1)) finish(emptyList(), true)
        val next = pencil ?: candidates.singleOrNull { it.type == UITouchTypeDirect && fingers == 1 && editor.finger } ?: return
        if (owner != null || editor.active) return
        owner = next; hover = null
        val batch = actual(next, withEvent)
        if (editor.tool.selectsItems) {
            selecting = true; selection.begin(batch.first().point, ::cancelPointer); navigate(true); setNeedsDisplay(); return
        }
        if (pencil != null) navigate(false)
        val gesture = InkGesture(editor, space, pencil != null && (next.maximumPossibleForce > 0 || traitCollection.forceTouchCapability == UIForceTouchCapabilityAvailable))
        ink = gesture; gesture.begin(batch.first(), ::cancelPointer); gesture.move(batch.drop(1), predicted(next, withEvent)); refreshInk()
    }

    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
        val touch = owner?.takeIf { it in touches } ?: return
        if (selecting) {
            val batch = actual(touch, withEvent)
            if (editor.tool == Tool.LASSO && selection.ring.isNotEmpty()) batch.forEach { selection.move(it.point) }
            else selection.move(batch.last().point)
            return
        }
        ink?.move(actual(touch, withEvent), predicted(touch, withEvent)); refreshInk()
    }

    override fun touchesEstimatedPropertiesUpdated(touches: Set<*>) {
        if (owner?.type != UITouchTypePencil) return
        touches.filterIsInstance<UITouch>().filter { it.type == UITouchTypePencil && it.estimatedPropertiesExpectingUpdates and UITouchPropertyForce == 0L }.forEach {
            it.estimationUpdateIndex?.let { index -> ink?.update(index.longLongValue, it.force, it.maximumPossibleForce) }
        }
        refreshInk()
    }

    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
        val touch = owner?.takeIf { it in touches } ?: return
        finish(actual(touch, withEvent), false)
    }

    override fun touchesCancelled(touches: Set<*>, withEvent: UIEvent?) {
        if (owner in touches) finish(emptyList(), true)
    }

    private fun finish(last: List<PencilSample>, cancelled: Boolean) {
        val pencil = owner?.type == UITouchTypePencil
        val gesture = ink; ink = null; owner = null
        if (selecting) {
            selecting = false
            if (cancelled || last.isEmpty()) selection.cancel() else {
                if (editor.tool == Tool.LASSO && selection.ring.isNotEmpty()) last.dropLast(1).forEach { selection.move(it.point) }
                selection.finish(last.last().point)
            }
        }
        layered?.update(null)
        gesture?.finish(last, cancelled)
        if (pencil && !detached) navigate(true)
        setNeedsDisplay()
    }

    private val PencilSample.point get() = Pt(x, y)
    private fun cancelPointer() = finish(emptyList(), true)

    fun close() {
        detached = true; finish(emptyList(), true)
        if (editor.selection === selection) editor.selection = null
    }

    override fun drawRect(rect: CValue<CGRect>) {
        val context = UIGraphicsGetCurrentContext() ?: return
        hover?.let { point ->
            val ring = CGRectMake(point.x - 5, point.y - 5, 10.0, 10.0)
            CGContextSetLineWidth(context, 3.0); CGContextSetStrokeColorWithColor(context, UIColor.whiteColor.CGColor); CGContextStrokeEllipseInRect(context, ring)
            CGContextSetLineWidth(context, 1.0); CGContextSetStrokeColorWithColor(context, UIColor.blackColor.CGColor); CGContextStrokeEllipseInRect(context, ring)
        }
        if (editor.tool.selectsItems) selection.place()
        if (ink == null && !editor.tool.selectsItems) return
        CGContextSaveGState(context)
        CGContextScaleCTM(context, space.zoom, space.zoom); CGContextTranslateCTM(context, -space.origin.x, -space.origin.y)
        if (editor.tool.selectsItems) {
            val viewport = bounds.useContents { Rect(space.origin.x, space.origin.y, size.width / space.zoom, size.height / space.zoom) }
            selection.paint(context, space, editor.tool, viewport, editor.session.note?.pages?.size ?: 0)
        }
        ink?.preview { page, stroke -> ribbon(context, page, stroke) }
        CGContextRestoreGState(context)
    }

    private fun ribbon(context: CGContextRef, page: Int, stroke: Stroke) {
        if (!space.writable(page) || layered?.handles?.invoke(page) == true) return
        CGContextSaveGState(context)
        space.paper(page)?.let { CGContextClipToRect(context, CGRectMake(it.x, it.y, it.w, it.h)) }
        val offset = space.fromPage(page, Pt.ZERO)
        CGContextTranslateCTM(context, offset.x, offset.y)
        drawWetStroke(context, stroke, space.night(page)); CGContextRestoreGState(context)
    }
}

private fun preferred(action: UIPencilPreferredAction) = when (action) {
    UIPencilPreferredActionSwitchEraser -> PencilPreferredAction.SWITCH_ERASER
    UIPencilPreferredActionSwitchPrevious -> PencilPreferredAction.SWITCH_PREVIOUS
    UIPencilPreferredActionShowColorPalette -> PencilPreferredAction.SHOW_COLOR_PALETTE
    UIPencilPreferredActionShowInkAttributes -> PencilPreferredAction.SHOW_INK_ATTRIBUTES
    UIPencilPreferredActionShowContextualPalette -> PencilPreferredAction.SHOW_CONTEXTUAL_PALETTE
    UIPencilPreferredActionRunSystemShortcut -> PencilPreferredAction.RUN_SYSTEM_SHORTCUT
    else -> PencilPreferredAction.IGNORE
}
