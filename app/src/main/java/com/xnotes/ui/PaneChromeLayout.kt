package com.xnotes.ui

import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition
import com.xnotes.ui.theme.Space

/**
 * Keeps the editor at one composition location while its toolbar changes edges or disappears.
 * Only measurement and placement change; the content's native View never moves between parents.
 * The floating bar still draws at the caller's overlay position, below its menus and popups.
 *
 * [strip] runs along the bottom, docked or floating, over a bar docked there. A long press on the
 * bar lifts it and [onPlace] hears where it was put down. While [reading] the bar fades toward its
 * edge, its room goes to the pages and [islands] lie over the canvas in its place.
 */
@Composable
internal fun PaneChromeLayout(
    look: ToolbarLook,
    showToolbar: Boolean,
    bar: @Composable () -> Unit,
    onCover: (Double, Double, Double, Double) -> Unit,
    onPlace: (ToolbarPosition, floating: Boolean) -> Unit,
    strip: @Composable () -> Unit,
    reading: Boolean,
    islands: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (floatingBar: @Composable BoxScope.() -> Unit) -> Unit,
) {
    val move = remember { ToolbarMove() }
    move.onPlace = onPlace
    val floating = showToolbar && look.floating
    val docked = showToolbar && !look.floating
    val floats = floating && !reading
    // Insets describe the existing toolbar style, not a measurement written back into state.
    // Keeping this out of the measure pass avoids a viewport -> measure -> viewport cycle.
    val depth = if (floats) barMetrics(look.size).thickness + FLOAT_MARGIN else 0.dp
    val density = LocalDensity.current
    val px = with(density) { depth.toPx().toDouble() }
    // A floating strip covers the bottom too, over a bar floating there.
    val stripPx = if (floats && move.strip > 0) move.strip + with(density) { FLOAT_MARGIN.toPx().toDouble() } else 0.0
    val position = look.position
    val fade by animateFloatAsState(if (reading) 0f else 1f, tween(BAR_FADE_MS, easing = Ease), label = "barFade")
    val drift by animateFloatAsState(if (reading) 1f else 0f, tween(BAR_DRIFT_MS, easing = Ease), label = "barDrift")
    // The bar stays only to fade, and takes no touch meanwhile.
    val barUp = !reading || fade > 0f
    val leaving = Modifier.graphicsLayer {
        alpha = fade
        val d = BAR_DRIFT.toPx() * drift
        when (position) {
            ToolbarPosition.TOP -> translationY = -d
            ToolbarPosition.BOTTOM -> translationY = d
            ToolbarPosition.LEFT -> translationX = -d
            ToolbarPosition.RIGHT -> translationX = d
        }
    }
    val cover = when (position) {
        ToolbarPosition.TOP -> PaddingValues(top = depth)
        ToolbarPosition.BOTTOM -> PaddingValues(bottom = depth)
        ToolbarPosition.LEFT -> PaddingValues.Absolute(left = depth)
        ToolbarPosition.RIGHT -> PaddingValues.Absolute(right = depth)
    }
    // Overlays keep clear of the islands along the top as they do of a floating bar.
    val readingCover = PaddingValues(top = ISLAND_MARGIN + barMetrics(look.size).thickness)
    SideEffect {
        onCover(
            if (position == ToolbarPosition.LEFT) px else 0.0,
            if (position == ToolbarPosition.TOP) px else 0.0,
            if (position == ToolbarPosition.RIGHT) px else 0.0,
            (if (position == ToolbarPosition.BOTTOM) px else 0.0) + stripPx,
        )
    }
    val floatingSlot: @Composable BoxScope.() -> Unit = {
        if (barUp) Box(Modifier.align(edgeOf(position)).padding(FLOAT_MARGIN).then(leaving)) {
            bar()
            if (reading) TouchSink()
        }
        // The strip floats along the bottom of what the bar leaves clear.
        if (!reading) Box(Modifier.align(Alignment.BottomCenter).padding(cover).padding(FLOAT_MARGIN).onSizeChanged { move.strip = it.height }) { strip() }
        islands()
    }
    // A docked bar lays nothing over the canvas; only the islands do.
    val dockedSlot: @Composable BoxScope.() -> Unit = { islands() }
    // The provider and every child slot remain present in every layout mode.
    CompositionLocalProvider(LocalToolbarMove provides move, LocalToolbarCover provides if (reading) readingCover else cover) {
        Box(modifier.onGloballyPositioned { move.area = it }) {
            Layout(modifier = Modifier.fillMaxSize(), content = {
                Box(propagateMinConstraints = true) {
                    content(if (floating) floatingSlot else dockedSlot)
                }
                Box(Modifier.zIndex(1f)) {
                    if (docked && barUp) Box(leaving) {
                        bar()
                        if (reading) TouchSink()
                    }
                }
                Box(Modifier.onSizeChanged { if (docked) move.strip = it.height }) { if (docked) strip() }
            }) { measurables, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val toolbar = measurables[1].measure(loose)
                val stripPlaceable = measurables[2].measure(loose)
                // Read-only, a docked bar gives its room to the pages at once and fades out over them.
                val inset = docked && !reading
                val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight
                // Every caller gives a fixed pane. Should one ever measure this unbounded (a scrolling
                // parent, an intrinsic pass), size to the content instead of failing the whole frame.
                val unboundedEditor = if (bounded) null else {
                    val insetX = if (inset && position.vertical) toolbar.width else 0
                    val insetY = (if (inset && !position.vertical) toolbar.height else 0) + stripPlaceable.height
                    measurables[0].measure(Constraints(
                        maxWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth - insetX).coerceAtLeast(0) else Constraints.Infinity,
                        maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - insetY).coerceAtLeast(0) else Constraints.Infinity,
                    ))
                }
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else paneChromeUnboundedExtent(
                    unboundedEditor!!.width, toolbar.width, inset && position.vertical, constraints.minWidth)
                val height = if (constraints.hasBoundedHeight) constraints.maxHeight else paneChromeUnboundedExtent(
                    unboundedEditor!!.height, toolbar.height, inset && !position.vertical, constraints.minHeight) + stripPlaceable.height
                val geometry = paneChromeGeometry(width, height, toolbar.width, toolbar.height, position, inset, stripPlaceable.height)
                val editor = unboundedEditor ?: measurables[0].measure(Constraints.fixed(geometry.contentWidth, geometry.contentHeight))
                layout(width, height) {
                    // Physical left/right, matching ToolbarPosition and the canvas inset coordinates.
                    editor.place(geometry.contentX, geometry.contentY)
                    stripPlaceable.place(0, geometry.stripY)
                    // A yielding bar still draws at its edge, over the pages.
                    toolbar.place(
                        if (docked && position == ToolbarPosition.RIGHT) width - toolbar.width else 0,
                        if (docked && position == ToolbarPosition.BOTTOM) height - toolbar.height else 0,
                    )
                }
            }
            ToolbarLandingMark(move, look.size)
        }
    }
}

private val NoFloatingBar: @Composable BoxScope.() -> Unit = {}

/**
 * One axis of an unbounded pane: the content plus a toolbar docked across that axis, otherwise
 * whichever of content and toolbar is longer, never below the parent's minimum.
 */
internal fun paneChromeUnboundedExtent(content: Int, toolbar: Int, toolbarAlongAxis: Boolean, min: Int): Int =
    maxOf(if (toolbarAlongAxis) content + toolbar else maxOf(content, toolbar), min)

/** Pixel geometry shared by the measure policy and tests; sizes are already parent-constrained. */
internal data class PaneChromeGeometry(
    val contentX: Int,
    val contentY: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val toolbarX: Int,
    val toolbarY: Int,
    /** The top of the strip along the bottom: under the content, over a bar docked there. */
    val stripY: Int = contentY + contentHeight,
)

internal fun paneChromeGeometry(
    width: Int,
    height: Int,
    toolbarWidth: Int,
    toolbarHeight: Int,
    position: ToolbarPosition,
    docked: Boolean,
    stripHeight: Int = 0,
): PaneChromeGeometry {
    val insetX = if (docked && position.vertical) toolbarWidth else 0
    val insetY = if (docked && !position.vertical) toolbarHeight else 0
    val contentY = if (position == ToolbarPosition.TOP) insetY else 0
    val contentHeight = (height - insetY - stripHeight).coerceAtLeast(0)
    return PaneChromeGeometry(
        contentX = if (position == ToolbarPosition.LEFT) insetX else 0,
        contentY = contentY,
        contentWidth = width - insetX,
        contentHeight = contentHeight,
        toolbarX = if (docked && position == ToolbarPosition.RIGHT) width - toolbarWidth else 0,
        toolbarY = if (docked && position == ToolbarPosition.BOTTOM) height - toolbarHeight else 0,
        stripY = contentY + contentHeight,
    )
}
