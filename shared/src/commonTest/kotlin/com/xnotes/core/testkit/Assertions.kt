package com.xnotes.core.testkit

import com.xnotes.core.pal.AssetFile

expect fun asset(path: String): AssetFile
expect fun identityHash(value: Any?): Int
expect fun isJavaWhitespace(codePoint: Int): Boolean

fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)
fun assertEquals(message: String, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)
fun assertEquals(expected: Long, actual: Long) = kotlin.test.assertEquals(expected, actual)
fun assertEquals(message: String, expected: Long, actual: Long) = kotlin.test.assertEquals(expected, actual, message)
fun assertEquals(expected: Double, actual: Double, delta: Double) = kotlin.test.assertEquals(expected, actual, delta)
fun assertEquals(expected: Float, actual: Float, delta: Float) = kotlin.test.assertEquals(expected, actual, delta)
fun assertEquals(message: String, expected: Double, actual: Double, delta: Double) = kotlin.test.assertEquals(expected, actual, delta, message)
fun assertEquals(message: String, expected: Float, actual: Float, delta: Float) = kotlin.test.assertEquals(expected, actual, delta, message)
fun assertNotEquals(first: Any?, second: Any?) = kotlin.test.assertNotEquals(first, second)
fun assertNotEquals(message: String, first: Any?, second: Any?) = kotlin.test.assertNotEquals(first, second, message)
fun assertTrue(value: Boolean) = kotlin.test.assertTrue(value)
fun assertTrue(message: String, value: Boolean) = kotlin.test.assertTrue(value, message)
fun assertFalse(value: Boolean) = kotlin.test.assertFalse(value)
fun assertFalse(message: String, value: Boolean) = kotlin.test.assertFalse(value, message)
fun assertNull(value: Any?) = kotlin.test.assertNull(value)
fun assertNull(message: String, value: Any?) = kotlin.test.assertNull(value, message)
fun assertNotNull(value: Any?) { kotlin.test.assertNotNull(value) }
fun assertNotNull(message: String, value: Any?) { kotlin.test.assertNotNull(value, message) }
fun assertSame(expected: Any?, actual: Any?) = kotlin.test.assertSame(expected, actual)
fun assertSame(message: String, expected: Any?, actual: Any?) = kotlin.test.assertSame(expected, actual, message)
fun assertNotSame(expected: Any?, actual: Any?) = kotlin.test.assertNotSame(expected, actual)
fun assertNotSame(message: String, expected: Any?, actual: Any?) = kotlin.test.assertNotSame(expected, actual, message)
fun fail(message: String): Nothing = kotlin.test.fail(message)

fun assertArrayEquals(expected: ByteArray, actual: ByteArray) = kotlin.test.assertContentEquals(expected, actual)
fun assertArrayEquals(expected: IntArray, actual: IntArray) = kotlin.test.assertContentEquals(expected, actual)
fun assertArrayEquals(expected: DoubleArray, actual: DoubleArray, delta: Double) {
    assertEquals(expected.size, actual.size)
    expected.indices.forEach { assertEquals("index $it", expected[it], actual[it], delta) }
}
fun assertArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float) {
    assertEquals(expected.size, actual.size)
    expected.indices.forEach { assertEquals("index $it", expected[it], actual[it], delta) }
}
