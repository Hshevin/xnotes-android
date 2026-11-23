package com.xnotes.iosapp

import androidx.compose.runtime.*
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.EditLayers
import com.xnotes.core.model.*
import com.xnotes.edit.PageOps
import com.xnotes.map.MapStateCommand
import com.xnotes.map.rootsOnLayer
import com.xnotes.map.withoutSubtrees
import com.xnotes.platform.ios.CgTextMeasurer

internal enum class Panel { PAGES, LAYERS }

internal class DocumentPanels(private val editor: InkEditor) {
    private val doc get() = editor.session.note
    private val canvas get() = editor.session.canvas
    var shown by mutableStateOf<Panel?>(null)
    var page by mutableStateOf(doc?.pages?.firstOrNull())
    var scrollTo: ((Page) -> Unit)? = null
    var thumbnails by mutableStateOf(true)
    val layers get() = page?.layers ?: checkNotNull(canvas).layers

    fun focus(target: Page) {
        if (doc?.pages?.contains(target) != true || !editor.preparePanelChange()) return
        showPage(target)
    }

    private fun showPage(target: Page) { page = target; scrollTo?.invoke(target) }

    fun add(copy: Boolean) {
        val note = doc ?: return
        val index = note.pages.indexOf(page)
        if (index < 0) return
        val added = editor.editPanel { if (copy) PageOps.duplicate(note, index, CgTextMeasurer) else PageOps.addAfter(note, index) }
        if (added) showPage(note.pages[index + 1])
    }

    fun deletePage(target: Page) {
        val note = doc ?: return
        val index = note.pages.indexOf(target)
        if (index < 0 || note.pages.size == 1) return
        if (editor.editPanel { PageOps.delete(note, index) }) showPage(note.pages[index.coerceAtMost(note.pages.lastIndex)])
    }

    fun movePage(from: Page, to: Page) {
        val note = doc ?: return
        if (from === to || from !in note.pages || to !in note.pages) return
        if (editor.editPanel { PageOps.move(note, note.pages.indexOf(from), note.pages.indexOf(to)) }) showPage(from)
    }

    fun selectLayer(id: String) {
        if (layers.activeId == id) return
        editor.editPanel { layers.select(id); null }
    }

    fun edit(change: () -> Unit) {
        val target = page
        if (target != null && doc?.pages?.contains(target) != true) return
        editor.editPanel {
            if (target != null) EditLayers.capture(target.layers, target.items, change) else {
                val model = checkNotNull(canvas)
                val before = model.map.state
                val layersEdit = EditLayers.capture(model.layers, { model.items }, model::replaceAll, change)
                val after = model.map.state
                if (before === after) layersEdit else CompositeCommand(listOf(layersEdit, MapStateCommand(model.map, before, after)))
            }
        }
    }

    fun deleteLayer(id: String) {
        if (layers.entries.size <= 1 || layers.entries.none { it.id == id }) return
        edit {
            page?.items?.removeAll { (it.layerId ?: DrawingLayers.DEFAULT) == id }
            canvas?.let { model ->
                model.removeAll(model.items.filter { (it.layerId ?: DrawingLayers.DEFAULT) == id })
                val state = model.map.state
                val roots = state.rootsOnLayer(id, isDefault = id == DrawingLayers.DEFAULT)
                if (roots.isNotEmpty()) model.map.replace(state.withoutSubtrees(roots))
            }
            layers.delete(id)
        }
    }
}
