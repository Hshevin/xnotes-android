package com.xnotes.iosapp

import androidx.compose.runtime.*
import com.xnotes.core.history.Command
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
        if (editor.active || doc?.pages?.contains(target) != true) return
        page = target; scrollTo?.invoke(target)
    }

    fun add(copy: Boolean) {
        val note = doc ?: return
        val index = note.pages.indexOf(page)
        if (editor.active || index < 0) return
        editor.recordPanelEdit(if (copy) PageOps.duplicate(note, index, CgTextMeasurer) else PageOps.addAfter(note, index))
        focus(note.pages[index + 1])
    }

    fun deletePage(target: Page) {
        val note = doc ?: return
        val index = note.pages.indexOf(target)
        if (editor.active || index < 0 || note.pages.size == 1) return
        editor.recordPanelEdit(PageOps.delete(note, index))
        focus(note.pages[index.coerceAtMost(note.pages.lastIndex)])
    }

    fun movePage(from: Page, to: Page) {
        val note = doc ?: return
        if (editor.active || from === to || from !in note.pages || to !in note.pages) return
        editor.recordPanelEdit(PageOps.move(note, note.pages.indexOf(from), note.pages.indexOf(to)))
        focus(from)
    }

    fun selectLayer(id: String) {
        if (editor.active || layers.activeId == id) return
        layers.select(id); editor.recordPanelEdit(null)
    }

    fun edit(change: () -> Unit) {
        if (editor.active) return
        val target = page
        val command: Command = if (target != null) {
            if (doc?.pages?.contains(target) != true) return
            EditLayers.capture(target.layers, target.items, change)
        } else {
            val model = checkNotNull(canvas)
            val before = model.map.state
            val layersEdit = EditLayers.capture(model.layers, { model.items }, model::replaceAll, change)
            val after = model.map.state
            if (before === after) layersEdit else CompositeCommand(listOf(layersEdit, MapStateCommand(model.map, before, after)))
        }
        editor.recordPanelEdit(command)
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
