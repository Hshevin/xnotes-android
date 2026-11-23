package com.xnotes.core.testkit

actual fun asset(path: String) = java.io.File(path)
actual fun identityHash(value: Any?) = System.identityHashCode(value)
actual fun isJavaWhitespace(codePoint: Int) = Character.isWhitespace(codePoint)
