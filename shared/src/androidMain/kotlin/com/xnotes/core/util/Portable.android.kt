package com.xnotes.core.util

internal actual typealias CoreLock = java.util.concurrent.locks.ReentrantLock
internal actual class CoreWeakReference<T : Any> actual constructor(value: T) {
    private val reference = java.lang.ref.WeakReference(value)
    actual fun get() = reference.get()
}
internal actual fun <K : Any, V> identityMap(capacity: Int): MutableMap<K, V> = java.util.IdentityHashMap(capacity)
