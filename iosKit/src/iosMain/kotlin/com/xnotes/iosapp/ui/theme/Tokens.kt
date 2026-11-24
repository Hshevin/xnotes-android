package com.xnotes.iosapp.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object TextSize {
    val bodyLarge = 14.sp
    val title = 16.sp
    val display = 24.sp
}

internal object Space {
    val s2 = 2.dp
    val s4 = 4.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s24 = 24.dp
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
