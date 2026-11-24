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
    val copy = icon(roundRect(8.0, 8.0, 14.0, 14.0, 2.0), COPY_BACK_SHEET)
    val paste = icon("M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", "M9 2h6a1 1 0 0 1 1 1v1a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1Z")
    val duplicate = icon(roundRect(8.0, 8.0, 14.0, 14.0, 2.0), COPY_BACK_SHEET, "M15 12v6", "M12 15h6")
    val bold = icon("M6 12h9a4 4 0 0 1 0 8H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h7a4 4 0 0 1 0 8")
    val italic = icon("M19 4h-9", "M14 20H5", "M15 4 9 20")
    val listBullet = icon("M3 12h.01", "M3 18h.01", "M3 6h.01", "M8 12h13", "M8 18h13", "M8 6h13")
    val sliders = icon("M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1 14h6", "M9 8h6", "M17 16h6")
}
