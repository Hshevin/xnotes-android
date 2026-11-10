package com.xnotes.platform

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** An interrupted cleanup still owns its resources until every accepted worker has returned. */
internal fun awaitClosedExecutor(executor:ExecutorService?) {
    if(executor==null) return
    var interrupted=false
    try {
        while(true) {
            try {if(executor.awaitTermination(Long.MAX_VALUE,TimeUnit.NANOSECONDS)) return}
            catch(_:InterruptedException) {interrupted=true}
        }
    } finally {if(interrupted) Thread.currentThread().interrupt()}
}
