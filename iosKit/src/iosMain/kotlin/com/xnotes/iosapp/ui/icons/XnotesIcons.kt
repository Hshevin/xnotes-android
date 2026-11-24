package com.xnotes.iosapp.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The line-icon set (spec 11 §3), built as Compose [ImageVector]s straight from
 * the reference SVG path bodies (Feather/Lucide). Each is a 24x24 viewBox, 2px
 * round-capped stroke, no fill; the stroke colour is a placeholder recoloured by
 * the `Icon` tint at use.
 *
 * [icon] joins its path strings into one path before parsing, so every string after
 * the first must open with an ABSOLUTE moveto. A relative "m" there starts from the
 * previous subpath's current point, not the origin, and the stroke lands off-canvas.
 */
internal object XnotesIcons {

    private fun icon(vararg pathData: String): ImageVector {
        val nodes = PathParser().parsePathString(pathData.joinToString(" ")).toNodes()
        return ImageVector.Builder(
            name = "xn",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = nodes,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double): String =
        "M $x $y h $w v $h h ${-w} Z"

    private fun roundRect(x: Double, y: Double, w: Double, h: Double, r: Double): String =
        "M ${x + r} $y h ${w - 2 * r} a $r $r 0 0 1 $r $r v ${h - 2 * r} " +
            "a $r $r 0 0 1 ${-r} $r h ${-(w - 2 * r)} a $r $r 0 0 1 ${-r} ${-r} " +
            "v ${-(h - 2 * r)} a $r $r 0 0 1 $r ${-r} Z"

    private fun circle(cx: Double, cy: Double, r: Double): String =
        "M ${cx - r} $cy a $r $r 0 1 0 ${2 * r} 0 a $r $r 0 1 0 ${-2 * r} 0"

    private const val COPY_BACK_SHEET = "M4 16a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2"

    val prev = icon("M15 18l-6-6 6-6")
    val sidebar = icon(rect(3.0, 3.0, 18.0, 18.0), "M9 3v18")
    val layers = icon(
        "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z",
        "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12",
        "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17",
    )
    val eraser = icon(
        "m7 21-4.3-4.3a1.7 1.7 0 0 1 0-2.4l9.6-9.6a1.7 1.7 0 0 1 2.4 0l5.6 5.6a1.7 1.7 0 0 1 0 2.4L13 21Z",
        "M22 21H7",
    )
    val select = icon("M3 3l7.07 16.97 2.51-7.39 7.39-2.51Z", "M13 13l6 6")
    val lasso = icon(
        "M21.4 8.3Q22.9 10.4 21.7 12.8", "M19.7 14.9Q17.6 16.1 15.5 17.3",
        "M13 18.8Q10.8 19.9 8.4 19.6", "M5.7 18.5Q3.7 16.9 3.6 14.4",
        "M3.6 11.4Q3.7 9 4.9 6.9", "M7.1 4.9Q9.3 3.9 11.7 4.3",
        "M14.6 4.8Q17 5.4 19.1 6.4",
    )
    val text = icon(
        "M17 21h-1a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4h1",
        "M7 21h1a4 4 0 0 0 4-4v-1",
        "M7 3h1a4 4 0 0 1 4 4v1",
    )
    val undo = icon("M9 14 4 9l5-5", "M20 20v-7a4 4 0 0 0-4-4H4")
    val redo = icon("M15 14l5-5-5-5", "M4 20v-7a4 4 0 0 1 4-4h12")
    val trash = icon("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val cut = icon(circle(6.0, 6.0, 3.0), circle(6.0, 18.0, 3.0), "M20 4 8.12 15.88", "M14.47 14.48 20 20", "M8.12 8.12 12 12")
    val copy = icon(roundRect(8.0, 8.0, 14.0, 14.0, 2.0), COPY_BACK_SHEET)
    val paste = icon("M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", "M9 2h6a1 1 0 0 1 1 1v1a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1Z")
    val duplicate = icon(roundRect(8.0, 8.0, 14.0, 14.0, 2.0), COPY_BACK_SHEET, "M15 12v6", "M12 15h6")
    val bold = icon("M6 12h9a4 4 0 0 1 0 8H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h7a4 4 0 0 1 0 8")
    val italic = icon("M19 4h-9", "M14 20H5", "M15 4 9 20")
    val listBullet = icon("M3 12h.01", "M3 18h.01", "M3 6h.01", "M8 12h13", "M8 18h13", "M8 6h13")
    val sliders = icon("M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1 14h6", "M9 8h6", "M17 16h6")

    val home = icon("M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z", "M9 22V12h6v10")
    val folder = icon("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2Z")
    val split = icon(rect(3.0, 3.0, 18.0, 18.0), "M12 3v18")
    val ai = icon(
        "M9.94 15.5A2 2 0 0 0 8.5 14.06l-6.14-1.58a.5.5 0 0 1 0-.96L8.5 9.94A2 2 0 0 0 9.94 8.5l1.58-6.14a.5.5 0 0 1 .96 0L14.06 8.5A2 2 0 0 0 15.5 9.94l6.14 1.58a.5.5 0 0 1 0 .96L15.5 14.06a2 2 0 0 0-1.44 1.44l-1.58 6.14a.5.5 0 0 1-.96 0Z",
        "M20 3v4", "M22 5h-4", "M4 17v2", "M5 18H3",
    )
    val restore = icon("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val clock = icon(circle(12.0, 12.0, 10.0), "M12 6v6l4 2")
    val info = icon(circle(12.0, 12.0, 10.0), "M12 16v-4", "M12 8h.01")
    val menu = icon("M4 6h16", "M4 12h16", "M4 18h16")
    val search = icon(circle(11.0, 11.0, 8.0), "M21 21l-4.35-4.35")
    val layoutGrid = icon(
        roundRect(3.0, 3.0, 7.0, 7.0, 1.0), roundRect(14.0, 3.0, 7.0, 7.0, 1.0),
        roundRect(14.0, 14.0, 7.0, 7.0, 1.0), roundRect(3.0, 14.0, 7.0, 7.0, 1.0),
    )
    val list = icon("M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01")
    val more = icon(circle(12.0, 5.0, 1.0), circle(12.0, 12.0, 1.0), circle(12.0, 19.0, 1.0))
    val edit = icon("M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4Z")
    val canvas = icon(rect(3.0, 3.0, 18.0, 18.0), "M3 9h18", "M3 15h18", "M9 3v18", "M15 3v18")
    val importDoc = icon("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3")
    val newFolder = icon("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2Z", "M12 11v6", "M9 14h6")
    val arrowDown = icon("M12 5v14", "M19 12l-7 7-7-7")
    val arrowUp = icon("M12 19V5", "M5 12l7-7 7 7")
    val chevronDown = icon("M6 9l6 6 6-6")
    val sort = icon("M21 16l-4 4-4-4", "M17 20V4", "M3 8l4-4 4 4", "M7 4v16")
    val file = icon("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z", "M14 2v6h6")
    val pdf = icon(
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4",
        "M10 9H8", "M16 13H8", "M16 17H8",
    )
    val pages = icon(
        "M20 7h-3a2 2 0 0 1-2-2V2",
        "M9 18a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h7l4 4v10a2 2 0 0 1-2 2Z",
        "M3 7.6v12.8A1.6 1.6 0 0 0 4.6 22h9.8",
    )
    val close = icon("M18 6 6 18", "M6 6l12 12")
    val next = icon("M9 18l6-6-6-6")
    val gallery = icon(roundRect(3.0, 3.0, 8.0, 14.0, 1.0), roundRect(13.0, 3.0, 8.0, 14.0, 1.0), "M2 21h20")
    val columns = icon(roundRect(3.0, 3.0, 18.0, 18.0, 2.0), "M9 3v18", "M15 3v18")
    val timeline = icon(
        roundRect(3.0, 4.0, 18.0, 18.0, 2.0), "M16 2v4", "M8 2v4", "M3 10h18",
        "M8 14h.01", "M12 14h.01", "M16 14h.01", "M8 18h.01", "M12 18h.01",
    )
    val filter = icon("M3 6h18", "M7 12h10", "M10 18h4")
}
