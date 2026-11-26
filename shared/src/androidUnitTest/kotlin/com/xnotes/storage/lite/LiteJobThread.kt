package com.xnotes.storage.lite

import java.util.concurrent.FutureTask

internal actual fun <T> runOffThread(work: () -> T): T {
    val task = FutureTask(work)
    Thread(task).start()
    return task.get()
}
