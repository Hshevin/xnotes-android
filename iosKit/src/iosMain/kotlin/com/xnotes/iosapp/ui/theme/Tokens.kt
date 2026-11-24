package com.xnotes.iosapp.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object TextSize {
    val caption = 11.sp
    val label = 12.sp
    val body = 13.sp
    val bodyLarge = 14.sp
    val title = 16.sp
    val heading = 18.sp
    val display = 24.sp
}

internal object Space {
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
    val sidebar = 288.dp
    val thumbnail = 56.dp
}

internal val MinTouchTarget = 48.dp

internal object ToolbarSize {
    val button = 52.dp
    val circle = 38.dp
    val icon = 22.dp
    val maxFilename = 160.dp
    /** An ink swatch as Android's regular bar draws it, in a slot wide enough to hit. */
    val swatch = 28.dp
    val swatchSlot = 40.dp
    val swatchRing = 2.dp
}

/** The selection's floating bar and the tool popups, after Android's menus. */
internal object MenuSize {
    val button = 46.dp
    val gap = 10.dp
    val edge = 8.dp
    val corner = 12.dp
    val hairline = 1.dp
    val popupWidth = 250.dp
    val switchWidth = 44.dp
    val switchHeight = 26.dp
    val track = 4.dp
    val knob = 20.dp
}

/** The home screen's sizes, after Android's Backstage and explorer. */
internal object HomeSize {
    val sidebar = 264.dp
    val rail = 80.dp
    val sheetMargin = 8.dp
    val rowInset = 8.dp
    val row = 44.dp
    val sheetCorner = 16.dp
    val rowCorner = 8.dp
    val gutter = 16.dp
    val header = 56.dp
    val chipRow = 48.dp
    val chip = 32.dp
    val chipCorner = 8.dp
    val tileCorner = 12.dp
    val folderChip = 60.dp
    val headerButton = 40.dp
    val switcherCell = 42.dp
    val navIcon = 20.dp
    val railPill = 56.dp
    val railPillHeight = 32.dp
    val groupHeader = 30.dp
    val listHeader = 36.dp
    val listRow = 56.dp
    val listThumb = 40.dp
    val listKind = 120.dp
    val listPages = 70.dp
    val listSize = 90.dp
    val listWhen = 170.dp
    val fab = 56.dp
    val fabMargin = 24.dp
    val menuRow = 50.dp
    val menuWidth = 240.dp
    val dialogWidth = 360.dp
    val bottomClearance = 88.dp
    val emptyArt = 96.dp
    val hairline = 1.dp
    /** Grid columns are picked from the screen width so folding the sidebar never reflows the grid. */
    const val TILE_WIDTH = 240f
}
