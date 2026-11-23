package com.xnotes.format

import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node as DomNode
import org.xml.sax.SAXException

internal actual fun fixedDecimal(value: Double, places: Int) = String.format(Locale.ROOT, "%.${places}f", value)

internal actual fun parseXml(bytes: ByteArray, refuseDoctype: Boolean): Element? = try {
    val parser = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        if (refuseDoctype) runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newDocumentBuilder()
    parser.setEntityResolver { _, _ -> throw SAXException("External XML entity") }
    val document = parser.parse(bytes.inputStream())
    require(!refuseDoctype || document.doctype == null)
    fun convert(node: DomNode): Node? = when (node.nodeType) {
        DomNode.ELEMENT_NODE -> Element(node.nodeName, List(node.attributes.length) { index ->
            node.attributes.item(index).let { Attribute(it.nodeName, it.namespaceURI, it.nodeValue) }
        }).also { element ->
            var child = node.firstChild
            while (child != null) { convert(child)?.let(element::add); child = child.nextSibling }
        }
        DomNode.TEXT_NODE, DomNode.CDATA_SECTION_NODE -> Node(node.nodeName, node.nodeValue, node.nodeType.toInt())
        else -> null
    }
    convert(document.documentElement) as? Element
} catch (_: Exception) { null }
