package com.xnotes.core.pal

import com.xnotes.core.model.Rgba

/** Display only: document colours and PDF export retain their original values. */
object NightInk {
    fun enabled(dark: Boolean, paper: Rgba, pdf: Boolean = false): Boolean =
        dark && !pdf && paper.r == 0 && paper.g == 0 && paper.b == 0

    /** The swatch previews the ink without changing the stored colour or its selection identity. */
    fun displayColor(color: Rgba, enabled: Boolean): Rgba = if (enabled) NightInk.color(color) else color

    fun color(color: Rgba): Rgba =
        if (color.r == 0 && color.g == 0 && color.b == 0) Rgba(255, 255, 255, color.a) else color
}

class NightInkRenderer(private val target: Renderer) : Renderer by target {
    override fun inkColor(color: Rgba): Rgba = NightInk.color(color)
}
