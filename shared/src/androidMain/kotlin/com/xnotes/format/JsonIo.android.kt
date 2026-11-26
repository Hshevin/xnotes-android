package com.xnotes.format

import java.io.Reader
import java.io.Writer

fun JsonPull(reader: Reader, strict: Boolean = false, tokenLimit: Int = 1_048_576): JsonPull =
    JsonPull(JsonChars(reader::read), strict, tokenLimit)

fun JsonWrite(writer: Writer): JsonWrite = JsonWrite(object : JsonOutput {
    override fun write(value: String) = writer.write(value)
    override fun write(codeUnit: Int) = writer.write(codeUnit)
    override fun write(chars: CharArray, offset: Int, length: Int) = writer.write(chars, offset, length)
})
