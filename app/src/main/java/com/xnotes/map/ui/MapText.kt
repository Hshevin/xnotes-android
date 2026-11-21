package com.xnotes.map.ui

import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.vector.VectorContour
import com.xnotes.map.Size
import com.xnotes.map.TitleMeasure
import kotlin.math.ceil

/**
 * Node titles as the canvas sets them: one face, sized in canvas units, wrapped at the node's
 * widest title. Measuring and drawing go through the same [measurer] at the same wrap width, so the
 * glyphs land exactly in the box [com.xnotes.map.NodeMetrics] made room for.
 */
class MapTitles(private val measurer: TextMeasurer) : TitleMeasure {

    override fun measure(text: String, textSize: Double, bold: Boolean, maxWidth: Double): Size {
        val font = font(textSize, bold)
        if (text.isEmpty()) return Size(0.0, measurer.lineHeight(font))
        val width = wrapWidth(text, font, maxWidth)
        return Size(width, measurer.measure(text, font, width).h)
    }

    /** The title's glyph outlines from its box's top-left, wrapped as [measure] wrapped it. */
    fun outline(text: String, textSize: Double, bold: Boolean, maxWidth: Double): List<VectorContour> {
        if (text.isBlank()) return emptyList()
        val key = Key(text, textSize, bold, maxWidth)
        outlines[key]?.let { return it }
        val font = font(textSize, bold)
        return measurer.outline(text, font, wrapWidth(text, font, maxWidth)).also { outlines[key] = it }
    }

    /** Short text that is not a title (a badge count, a chip), unwrapped. */
    fun width(text: String, textSize: Double, bold: Boolean = false): Double =
        text.split('\n').maxOf { measurer.advances(it, font(textSize, bold)).sum() }

    /** [text] cut short with an ellipsis so it sets within [maxWidth] on one line. */
    fun fit(text: String, textSize: Double, maxWidth: Double): String {
        val line = text.replace('\n', ' ')
        val adv = measurer.advances(line, font(textSize, false))
        if (adv.sum() <= maxWidth) return line
        val budget = maxWidth - measurer.advances(ELLIPSIS, font(textSize, false)).sum()
        var used = 0.0
        var n = 0
        while (n < adv.size && used + adv[n] <= budget) used += adv[n++]
        return line.substring(0, n).trimEnd() + ELLIPSIS
    }

    fun lineHeight(textSize: Double, bold: Boolean = false): Double = measurer.lineHeight(font(textSize, bold))

    /** [text] on one line, for badges and chips. */
    fun outlineLine(text: String, textSize: Double, bold: Boolean = false): List<VectorContour> =
        outline(text, textSize, bold, Double.MAX_VALUE / 4)

    /**
     * The narrowest width that sets [text] as wide as it naturally is, capped at [maxWidth]. A pixel
     * of slack keeps rounding in the platform's wrap from breaking a line that just fits.
     */
    private fun wrapWidth(text: String, font: FontSpec, maxWidth: Double): Double {
        val natural = text.split('\n').maxOf { measurer.advances(it, font).sum() }
        return minOf(ceil(natural) + 1.0, maxWidth)
    }

    private data class Key(val text: String, val size: Double, val bold: Boolean, val width: Double)

    private val outlines = object : LinkedHashMap<Key, List<VectorContour>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<VectorContour>>?) = size > CACHE
    }

    companion object {
        /** Content px per typographic point, as the canvas's text measurer sets type. */
        const val PX_PER_POINT = 150.0 / 72.0
        private const val CACHE = 512
        private const val ELLIPSIS = "…"

        val FACE: FontFace = FontFace.SANS

        fun font(textSize: Double, bold: Boolean) = FontSpec(textSize / PX_PER_POINT, FACE, bold = bold)
    }
}
