package com.xnotes.ui

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

internal object CheckpointPacing {
    const val VIEW_SETTLE_MS=180L
    const val CONTENT_SETTLE_MS=1500L
    fun settleMs(contentChanged:Boolean)=if(contentChanged) CONTENT_SETTLE_MS else VIEW_SETTLE_MS
}

internal sealed interface NativeCheckpointResult {
    class Ready(val checkpoint:Editor.PreparedNativePresentationCheckpoint):NativeCheckpointResult
    data object Deferred:NativeCheckpointResult
    data object Superseded:NativeCheckpointResult
    class Failed(val cause:Throwable?):NativeCheckpointResult
}

internal class SaveStats {
    val fileEncodes=AtomicInteger()
    val workingEncodes=AtomicInteger()
    val workingFromSource=AtomicInteger()
    val workingMetadataOnly=AtomicInteger()
    val checkpointStarted=AtomicInteger()
    val checkpointDeferred=AtomicInteger()
    val checkpointSuperseded=AtomicInteger()
    val checkpointFailed=AtomicInteger()
    inline fun <T> timed(label:String,counter:AtomicInteger,work:()->T):T {
        val start=System.nanoTime();val count=counter.incrementAndGet()
        try {return work()} finally {Log.i("XNotesSave","$label #$count ${(System.nanoTime()-start)/1_000_000}ms")}
    }
}
