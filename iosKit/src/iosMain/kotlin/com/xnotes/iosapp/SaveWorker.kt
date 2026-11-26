@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.iosapp

import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/** Runs document writes away from the main thread and reports back on it. */
internal interface SaveWorker {
    fun <T> launch(work: () -> T, done: (T) -> Unit)

    /** Writes on a background queue; the app asks iOS for time so a save outlives going to the background. */
    object Background : SaveWorker {
        override fun <T> launch(work: () -> T, done: (T) -> Unit) {
            val app = UIApplication.sharedApplication
            var task = UIBackgroundTaskInvalid
            task = app.beginBackgroundTaskWithName("Save document") {
                // Out of time: the write keeps going if iOS lets it; there is nothing safer to do here.
                if (task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
                task = UIBackgroundTaskInvalid
            }
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                val result = work()
                dispatch_async(dispatch_get_main_queue()) {
                    try { done(result) } finally {
                        if (task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
                        task = UIBackgroundTaskInvalid
                    }
                }
            }
        }
    }

    /** Writes in place, for tests that check the outcome right after asking to save. */
    object Inline : SaveWorker {
        override fun <T> launch(work: () -> T, done: (T) -> Unit) = done(work())
    }
}
