@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.format

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.snprintf

internal actual fun fixedDecimal(value: Double, places: Int): String = memScoped {
    val count = snprintf(null, 0u, "%.${places}f", value)
    require(count >= 0)
    val result = allocArray<ByteVar>(count + 1)
    snprintf(result, (count + 1).convert(), "%.${places}f", value)
    result.toKString()
}

private class TreeReader : NSObject(), NSXMLParserDelegateProtocol {
    var root: Element? = null
    private var current: Element? = null
    private val namespaces = mutableMapOf<String, MutableList<String>>()
    private val text = StringBuilder()

    private fun flushText() {
        if (text.isNotEmpty()) current?.add(Node("#text", text.toString(), Node.TEXT_NODE))
        text.clear()
    }

    @ObjCSignatureOverride
    override fun parser(parser: NSXMLParser, didStartMappingPrefix: String, toURI: String) {
        namespaces.getOrPut(didStartMappingPrefix) { mutableListOf() }.add(toURI)
    }

    @ObjCSignatureOverride
    override fun parser(parser: NSXMLParser, didEndMappingPrefix: String) { namespaces[didEndMappingPrefix]?.removeLast() }

    @ObjCSignatureOverride
    override fun parser(parser: NSXMLParser, didStartElement: String, namespaceURI: String?, qualifiedName: String?, attributes: Map<Any?, *>) {
        flushText()
        val element = Element(qualifiedName ?: didStartElement, attributes.map { (key, value) ->
            val name = key as String
            Attribute(name, if (':' in name) namespaces[name.substringBefore(':')]?.lastOrNull() else null, value as String)
        })
        if (root == null) root = element else current?.add(element)
        current = element
    }

    @ObjCSignatureOverride
    override fun parser(parser: NSXMLParser, didEndElement: String, namespaceURI: String?, qualifiedName: String?) {
        flushText()
        current = current?.parentNode as? Element
    }

    @ObjCSignatureOverride
    override fun parser(parser: NSXMLParser, foundCharacters: String) { text.append(foundCharacters) }

    @ObjCSignatureOverride
    override fun parser(parser: NSXMLParser, foundCDATA: NSData) {
        flushText()
        val data = foundCDATA.bytes?.reinterpret<ByteVar>()?.readBytes(foundCDATA.length.toInt()) ?: byteArrayOf()
        current?.add(Node("#cdata-section", data.decodeToString(), Node.CDATA_SECTION_NODE))
    }
}

private fun permitsXfdfProlog(bytes: ByteArray): Boolean {
    var offset = 0
    fun next(): Int {
        while (offset < bytes.size && bytes[offset] == 0.toByte()) offset++
        return if (offset == bytes.size) -1 else bytes[offset++].toInt() and 255
    }
    fun matches(text: String): Boolean {
        val start = offset
        for (char in text) if (next() != char.code) { offset = start; return false }
        return true
    }
    fun skipUntil(text: String): Boolean {
        while (offset < bytes.size) { if (matches(text)) return true; next() }
        return false
    }
    if (!matches("\u00ef\u00bb\u00bf") && !matches("\u00ff\u00fe")) matches("\u00fe\u00ff")
    while (true) when (next()) {
        9, 10, 13, 32 -> Unit
        '<'.code -> when {
            matches("?") -> if (!skipUntil("?>")) return false
            matches("!--") -> if (!skipUntil("-->")) return false
            else -> return !matches("!")
        }
        else -> return false
    }
}

internal actual fun parseXml(bytes: ByteArray, refuseDoctype: Boolean): Element? {
    if (bytes.isEmpty() || (refuseDoctype && !permitsXfdfProlog(bytes))) return null
    val reader = TreeReader()
    val parser = NSXMLParser(bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) })
    parser.shouldProcessNamespaces = true
    parser.shouldReportNamespacePrefixes = true
    parser.shouldResolveExternalEntities = false
    parser.delegate = reader
    return if (parser.parse()) reader.root else null
}
