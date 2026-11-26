package com.xnotes.core.util

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.identityHashCode
import platform.Foundation.NSRecursiveLock

internal actual class CoreLock {
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
@OptIn(ExperimentalNativeApi::class)
internal actual class CoreWeakReference<T : Any> actual constructor(value: T) {
    private val reference = kotlin.native.ref.WeakReference(value)
    actual fun get() = reference.get()
}

@OptIn(ExperimentalNativeApi::class)
private class IdentityKey<T : Any>(val value: T) {
    override fun equals(other: Any?): Boolean = other is IdentityKey<*> && value === other.value
    override fun hashCode(): Int = value.identityHashCode()
}

internal actual fun <K : Any, V> identityMap(capacity: Int): MutableMap<K, V> = object : AbstractMutableMap<K, V>() {
    private val map = HashMap<IdentityKey<K>, V>(capacity)
    override fun get(key: K) = map[IdentityKey(key)]
    override fun containsKey(key: K) = map.containsKey(IdentityKey(key))
    override fun put(key: K, value: V) = map.put(IdentityKey(key), value)
    override fun remove(key: K) = map.remove(IdentityKey(key))
    override fun clear() = map.clear()
    override val entries = object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
        override val size get() = map.size
        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
            val source = map.entries.iterator()
            return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                override fun hasNext() = source.hasNext()
                override fun remove() = source.remove()
                override fun next(): MutableMap.MutableEntry<K, V> {
                    val original = source.next()
                    return object : MutableMap.MutableEntry<K, V> {
                        override val key get() = original.key.value
                        override val value get() = original.value
                        override fun setValue(newValue: V) = original.setValue(newValue)
                    }
                }
            }
        }
    }
}
