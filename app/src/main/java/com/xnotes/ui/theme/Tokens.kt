package com.xnotes.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The only text sizes chrome may use. Paper content (typed notes, templates, PDF) keeps its own
 * document sizes; this scale is for menus, bars, panels and dialogs.
 */
object TextSize {
    /** Glyphs inside icons and badges. */
    val micro = 10.sp
    val caption = 11.sp
    val label = 12.sp
    val body = 13.sp
    val bodyLarge = 14.sp
    /** Section titles and emphasised rows. */
    val title = 16.sp
    /** Dialog and screen titles. */
    val heading = 18.sp
    val headline = 20.sp
    val display = 24.sp

    /**
     * How much larger a formula is set than the text around it: Latin Modern's x-height is about
     * 0.43 em against the sans's 0.53, so at the same size a formula reads a size smaller.
     */
    const val MathScale = 1.2f
}

/**
 * The only gaps chrome may use for padding, `spacedBy` and spacers: a 4 dp grid with 2 and 6 as
 * fine steps for dense controls. Component sizes (buttons, swatches, thumbnails) are not gaps and
 * stay with their components; 1 dp is reserved for hairline offsets.
 */
object Space {
    val s2 = 2.dp
    val s4 = 4.dp
    val s6 = 6.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s20 = 20.dp
    val s24 = 24.dp
    val s32 = 32.dp
    val s40 = 40.dp
    val s48 = 48.dp
}

/** Smallest touch target for any chrome control, whatever the toolbar size. */
val MinTouchTarget = 48.dp

/** Docked sidebar with a content preview and 48 dp layer controls. */
val LayerSidebarWidth = 320.dp
val LayerThumbnailSize = 48.dp

/** Scrollable outline and parent chooser in the independent mind map editor. */
val MindMapNodeListHeight = 400.dp

val MindMapPenSliderWidth = 240.dp

/** Below this pane height (a top/bottom split) the map folds its graph actions into one row and drops the hint. */
val MindMapCompactPaneHeight = 640.dp

/** Longest node name beside the enlarged ink editor's Back button. */
val MindMapInkTitleWidth = 200.dp

/** Last source image shown within the graph's existing scrollable source dialog. */
val MindMapSourcePreviewHeight = 144.dp

/** World units in the native graph surface equal dp at its normal viewing scale. */
val FileNetworkCardWidth = 240.dp
val FileNetworkCardHeight = 96.dp
val FileNetworkColumnStep = 360.dp
val FileNetworkRowStep = 144.dp

/** Temporary, paper-contrast navigation outline; it never becomes document content. */
object ReferenceLocatorStyle {
    const val DurationMillis = 1800L
    val StrokeWidth = 2.dp
    val Inset = Space.s4
}

/** Home sidebar tool panes (original library, file network, recovery) follow the Home/AI pane header dimensions. */
val HomeToolHeaderHeight = 56.dp
/** Original library header follows the Home/AI pane header dimensions. */
val OriginalPdfLibraryHeaderHeight = HomeToolHeaderHeight
val OriginalPdfLibrarySearchWidth = 300.dp
