package com.xnotes.iosapp

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.platform.ios.paperRect

internal class PagePlacement(doc: Document, val pages: List<Page> = doc.pages.toList()) {
    val frames = pages.map { it.paperRect(doc) }
    init { require(frames.all { it.w.isFinite() && it.h.isFinite() && it.w > 0 && it.h > 0 }) }
    val gap = Space.s24.value.toDouble()
    val width = frames.maxOf { it.w } + 2 * gap
    val places = buildList {
        var top = gap
        for (frame in frames) {
            add(Rect((width - frame.w) / 2, top, frame.w, frame.h))
            top += frame.h + gap
        }
    }
    val height = places.last().bottom + gap
}
