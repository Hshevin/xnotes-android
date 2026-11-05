package com.xnotes.core.model

/** User layers, bottom first. Empty names are displayed using a localized default label. */
data class DrawingLayer(val id: String, val name: String = "", val visible: Boolean = true, val locked: Boolean = false)

class DrawingLayers {
    @Volatile var entries: List<DrawingLayer> = listOf(DrawingLayer(DEFAULT))
        private set
    @Volatile var activeId: String = DEFAULT
        private set

    fun restore(entries: List<DrawingLayer>, active: String) {
        require(entries.isNotEmpty() && entries.map { it.id }.toSet().size == entries.size)
        require(entries.all { it.id.isNotBlank() } && entries.any { it.id == active })
        this.entries = entries.toList()
        activeId = active
    }

    fun select(id: String) { require(entries.any { it.id == id }); activeId = id }
    fun create(name: String): DrawingLayer = DrawingLayer(java.util.UUID.randomUUID().toString(), name).also {
        entries = entries + it
        activeId = it.id
    }
    fun update(id: String, edit: (DrawingLayer) -> DrawingLayer) {
        require(entries.any { it.id == id })
        entries = entries.map { if (it.id == id) edit(it).also { next -> require(next.id == id) } else it }
    }
    fun move(id: String, delta: Int) {
        val at = entries.indexOfFirst { it.id == id }
        require(at >= 0)
        val next = entries.toMutableList()
        next.add((at + delta).coerceIn(0, next.lastIndex), next.removeAt(at))
        entries = next
    }
    /** Drop before a row in the top-first sidebar; null means below every row. */
    fun moveBefore(id: String, beforeId: String?): Boolean {
        if (id == beforeId || entries.none { it.id == id } ||
            beforeId != null && entries.none { it.id == beforeId }) return false
        val topFirst = entries.asReversed().toMutableList()
        val moving = topFirst.first { it.id == id }
        topFirst.remove(moving)
        val at = beforeId?.let { target -> topFirst.indexOfFirst { it.id == target } } ?: topFirst.size
        topFirst.add(at, moving)
        val next = topFirst.asReversed().toList()
        if (next == entries) return false
        entries = next
        return true
    }

    fun delete(id: String) {
        require(entries.size > 1 && entries.any { it.id == id })
        entries = entries.filterNot { it.id == id }
        if (activeId == id) activeId = entries.last().id
    }
    val writable: Boolean get() = entries.firstOrNull { it.id == activeId }?.let { it.visible && !it.locked } == true
    fun visible(item: CanvasItem): Boolean = entries.firstOrNull { it.id == (item.layerId ?: DEFAULT) }?.visible ?: false
    fun editable(item: CanvasItem): Boolean = !item.locked && (item.layerId ?: DEFAULT) == activeId && writable
    fun ordered(items: List<CanvasItem>): List<CanvasItem> {
        if (entries.size == 1) return if (entries.single().visible) items else emptyList()
        val groups = items.groupBy { it.layerId ?: DEFAULT }
        return entries.filter { it.visible }.flatMap { groups[it.id].orEmpty() }
    }
    fun adopt(item: CanvasItem) {
        if (item.layerId == null || entries.none { it.id == (item.layerId ?: DEFAULT) }) item.layerId = activeId
    }
    fun copy() = DrawingLayers().also { it.restore(entries, activeId) }
    companion object { const val DEFAULT = "default" }
}

/** Assign newly inserted objects to the current layer; history keeps an object's existing ID. */
class LayerItems(private val layers: DrawingLayers, initial: List<CanvasItem> = emptyList()) : AbstractMutableList<CanvasItem>() {
    private val backing = initial.toMutableList().also { list -> list.forEach { if (it.layerId == null) it.layerId = DrawingLayers.DEFAULT } }
    override val size: Int get() = backing.size
    override fun get(index: Int): CanvasItem = backing[index]
    override fun add(index: Int, element: CanvasItem) { layers.adopt(element); backing.add(index, element); modCount++ }
    override fun clear() {
        if (backing.isEmpty()) return
        backing.clear()
        modCount++
    }
    override fun addAll(index: Int, elements: Collection<CanvasItem>): Boolean {
        if (elements.isEmpty()) return false
        elements.forEach(layers::adopt)
        backing.addAll(index, elements)
        modCount++
        return true
    }
    override fun set(index: Int, element: CanvasItem): CanvasItem { layers.adopt(element); return backing.set(index, element) }
    override fun removeAt(index: Int): CanvasItem = backing.removeAt(index).also { modCount++ }
}
