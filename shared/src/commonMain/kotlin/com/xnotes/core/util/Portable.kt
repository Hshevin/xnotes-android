package com.xnotes.core.util

import kotlin.math.PI
import kotlin.math.roundToLong

internal fun radians(value: Double) = value * (PI / 180.0)
internal fun degrees(value: Double) = value * (180.0 / PI)
internal fun nearestLong(value: Double) = if (value.isNaN()) 0L else value.roundToLong()
internal fun nextRevision(value: Long): Long {
    if (value == Long.MAX_VALUE) throw ArithmeticException("long overflow")
    return value + 1
}

internal fun codePointText(code: Int): String = if (code in 0..0xffff) code.toChar().toString() else {
    require(code in 0x10000..0x10ffff)
    charArrayOf(highUnit(code), lowUnit(code)).concatToString()
}
internal fun highUnit(code: Int): Char = (0xd800 + ((code - 0x10000) ushr 10)).toChar()
internal fun lowUnit(code: Int): Char = (0xdc00 + (code and 1023)).toChar()
internal fun StringBuilder.appendCodePoint(code: Int): StringBuilder = if (code in 0..0xffff) append(code.toChar()) else append(codePointText(code))
internal fun String.codePointAt(index: Int): Int {
    val high = this[index]
    val low = getOrNull(index + 1)
    return if (high.isHighSurrogate() && low != null && low.isLowSurrogate()) 0x10000 + ((high.code - 0xd800) shl 10) + low.code - 0xdc00 else high.code
}
internal fun String.codePointBefore(index: Int): Int = if (index >= 2 && this[index - 1].isLowSurrogate() && this[index - 2].isHighSurrogate()) codePointAt(index - 2) else this[index - 1].code

internal expect class CoreLock() { fun lock(); fun unlock() }
internal inline fun <R> CoreLock.locked(block: () -> R): R { lock(); try { return block() } finally { unlock() } }
internal expect class CoreWeakReference<T : Any>(value: T) { fun get(): T? }
internal class LockedMap<K, V> {
    private val lock = CoreLock()
    private val map = HashMap<K, V>()
    operator fun get(key: K): V? = lock.locked { map[key] }
    operator fun set(key: K, value: V) = lock.locked { map[key] = value }
    fun clear() = lock.locked { map.clear() }
    fun getOrPut(key: K, create: () -> V): V = lock.locked { map.getOrPut(key, create) }
}

internal expect fun <K : Any, V> identityMap(capacity: Int = 16): MutableMap<K, V>
internal fun <T : Any> identitySetOf(items: Collection<T>): MutableSet<T> = object : AbstractMutableSet<T>() {
    private val map = identityMap<T, Unit>(items.size).apply { for (item in items) put(item, Unit) }
    override val size get() = map.size
    override fun iterator() = map.keys.iterator()
    override fun contains(element: T) = map.containsKey(element)
    override fun add(element: T) = map.put(element, Unit) == null
}
