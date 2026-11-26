package com.xnotes.format

fun interface JsonChars {
    fun read(buffer: CharArray): Int
}

internal class StringChars(private val text: String) : JsonChars {
    private var offset = 0
    override fun read(buffer: CharArray): Int {
        if (offset == text.length) return -1
        val count = minOf(text.length - offset, buffer.size)
        text.toCharArray(buffer, 0, offset, offset + count)
        offset += count
        return count
    }
}

interface JsonOutput {
    fun write(value: String)
    fun write(codeUnit: Int)
    fun write(chars: CharArray, offset: Int, length: Int)
}

fun JsonWrite(output: StringBuilder): JsonWrite = JsonWrite(object : JsonOutput {
    override fun write(value: String) { output.append(value) }
    override fun write(codeUnit: Int) { output.append(codeUnit.toChar()) }
    override fun write(chars: CharArray, offset: Int, length: Int) { output.appendRange(chars, offset, offset + length) }
})
