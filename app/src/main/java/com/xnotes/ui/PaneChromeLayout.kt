package com.xnotes.ui

import androidx.compose.foundation.layout.Box
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
 */
@Composable
internal fun PaneChromeLayout(
    look: ToolbarLook,
    showToolbar: Boolean,
    bar: @Composable () -> Unit,
    onCover: (Double, Double, Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (floatingBar: @Composable BoxScope.() -> Unit) -> Unit,
) {
    val floating = showToolbar && look.floating
    val docked = showToolbar && !look.floating
    // Insets describe the existing toolbar style, not a measurement written back into state.
    // Keeping this out of the measure pass avoids a viewport -> measure -> viewport cycle.
    val depth = if (floating) barMetrics(look.size).thickness + Space.s8 else 0.dp
    val px = with(LocalDensity.current) { depth.toPx().toDouble() }
    val position = look.position
    val cover = when (position) {
        ToolbarPosition.TOP -> PaddingValues(top = depth)
        ToolbarPosition.BOTTOM -> PaddingValues(bottom = depth)
        ToolbarPosition.LEFT -> PaddingValues.Absolute(left = depth)
        ToolbarPosition.RIGHT -> PaddingValues.Absolute(right = depth)
    }
    val edge = when (position) {
        ToolbarPosition.TOP -> Alignment.TopCenter
        ToolbarPosition.BOTTOM -> Alignment.BottomCenter
        ToolbarPosition.LEFT -> AbsoluteAlignment.CenterLeft
        ToolbarPosition.RIGHT -> AbsoluteAlignment.CenterRight
    }
    SideEffect {
        onCover(
            if (position == ToolbarPosition.LEFT) px else 0.0,
            if (position == ToolbarPosition.TOP) px else 0.0,
            if (position == ToolbarPosition.RIGHT) px else 0.0,
            if (position == ToolbarPosition.BOTTOM) px else 0.0,
        )
    }
    // The provider and both child slots remain present in every layout mode.
    CompositionLocalProvider(LocalToolbarCover provides cover) {
        Layout(modifier = modifier, content = {
            Box(propagateMinConstraints = true) {
                content {
                    if (floating) {
                        Box(Modifier.align(edge).padding(Space.s8)) { bar() }
                    }
                }
            }
            Box { if (docked) bar() }
        }) { measurables, constraints ->
            check(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                "PaneChromeLayout requires a bounded editor viewport"
            }
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val toolbar = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val geometry = paneChromeGeometry(width, height, toolbar.width, toolbar.height, position, docked)
            val editor = measurables[0].measure(Constraints.fixed(geometry.contentWidth, geometry.contentHeight))
            layout(width, height) {
                // Physical left/right, matching ToolbarPosition and the canvas inset coordinates.
                editor.place(geometry.contentX, geometry.contentY)
                toolbar.place(geometry.toolbarX, geometry.toolbarY)
            }
        }
    }
}

/** Pixel geometry shared by the measure policy and tests; sizes are already parent-constrained. */
internal data class PaneChromeGeometry(
    val contentX: Int,
    val contentY: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val toolbarX: Int,
    val toolbarY: Int,
)

internal fun paneChromeGeometry(
    width: Int,
    height: Int,
    toolbarWidth: Int,
    toolbarHeight: Int,
    position: ToolbarPosition,
    docked: Boolean,
): PaneChromeGeometry {
    val insetX = if (docked && position.vertical) toolbarWidth else 0
    val insetY = if (docked && !position.vertical) toolbarHeight else 0
    return PaneChromeGeometry(
        contentX = if (position == ToolbarPosition.LEFT) insetX else 0,
        contentY = if (position == ToolbarPosition.TOP) insetY else 0,
        contentWidth = width - insetX,
        contentHeight = height - insetY,
        toolbarX = if (docked && position == ToolbarPosition.RIGHT) width - toolbarWidth else 0,
        toolbarY = if (docked && position == ToolbarPosition.BOTTOM) height - toolbarHeight else 0,
    )
}
