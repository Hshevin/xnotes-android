package com.xnotes.format

import com.xnotes.core.model.Rgba

internal open class Node(val nodeName: String, val nodeValue: String? = null, val nodeType: Int = 1) {
    val localName get() = nodeName.substringAfter(':')
    val childNodes = mutableListOf<Node>()
    var parentNode: Node? = null
        private set
    var nextSibling: Node? = null
        private set
    val firstChild get() = childNodes.firstOrNull()
    val textContent: String get() = buildString { collectText(this) }
    fun add(child: Node) {
        child.parentNode = this
        childNodes.lastOrNull()?.nextSibling = child
        childNodes.add(child)
    }
    private fun collectText(text: StringBuilder) {
        if (nodeValue != null) text.append(nodeValue) else for (child in childNodes) child.collectText(text)
    }
    companion object { const val TEXT_NODE = 3; const val CDATA_SECTION_NODE = 4 }
}

internal class Attribute(val nodeName: String, val namespace: String?, val nodeValue: String) {
    val localName get() = nodeName.substringAfter(':')
}

internal class Element(name: String, val attributes: List<Attribute>) : Node(name) {
    fun getAttribute(name: String) = attributes.firstOrNull { it.nodeName == name }?.nodeValue.orEmpty()
    fun getAttributeNS(namespace: String, name: String) = attributes.firstOrNull { it.namespace == namespace && it.localName == name }?.nodeValue.orEmpty()
}

internal expect fun parseXml(bytes: ByteArray, refuseDoctype: Boolean = false): Element?
internal expect fun fixedDecimal(value: Double, places: Int): String
internal fun rgbHex(color: Rgba) = "#" + listOf(color.r, color.g, color.b).joinToString("") { it.toString(16).padStart(2, '0') }
