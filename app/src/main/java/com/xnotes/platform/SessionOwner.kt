package com.xnotes.platform

import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred

/** A recreated Activity takes over its physical pane. Stale input and IO callbacks then retire. */
internal class SessionOwner private constructor() {
    private val retired = CompletableDeferred<Unit>()
    val onRetired get() = retired.onAwait
    fun isCurrent() = !retired.isCompleted

    companion object {
        private val owners = mutableMapOf<String, WeakReference<SessionOwner>>()
        @Synchronized fun attach(directory: File): SessionOwner {
            val owner = SessionOwner()
            owners.entries.removeAll { it.value.get() == null }
            owners.put(directory.canonicalPath, WeakReference(owner))?.get()?.retired?.complete(Unit)
            return owner
        }
    }
}
