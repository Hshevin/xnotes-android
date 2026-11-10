package com.xnotes.ui

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One caller's file ownership across cancellable IO returns; no model or source proof is held. */
internal class SplitPublicationAttempt(private val store:SplitArrangementStore) {
    private val started=AtomicBoolean(false)
    @Volatile private var prepared:PreparedSplitPublication?=null
    @Volatile private var published:File?=null
    @Volatile private var closed=false

    val publishedFile:File? get()=published ?: prepared?.publishedFile

    suspend fun prepare(request:MixedSplitRequest,name:String):Boolean {
        if(closed || !started.compareAndSet(false,true)) return false
        return withContext(Dispatchers.IO) {
            if(closed) return@withContext false
            val candidate=store.prepareNamed(request,name)
            // Hold ownership before dispatcher return can deliver prompt cancellation.
            prepared=candidate
            if(closed) {candidate?.close();false} else candidate!=null
        }
    }

    fun commit():File? {
        if(closed) return null
        return prepared?.commit()?.also {published=it}
    }

    suspend fun close()=withContext(NonCancellable+Dispatchers.IO) {
        closed=true
        prepared?.close()
    }
}
