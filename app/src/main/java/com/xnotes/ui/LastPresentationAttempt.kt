package com.xnotes.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One-shot file owner. It holds the stage before an IO-to-caller cancellation can discard it. */
internal class LastPresentationAttempt internal constructor(
    private val store:LastPresentationStore,
    private val ticket:LastPresentationStore.Ticket,
    private val value:LastPresentation,
    private val reset:LastPresentationObservation?,
) {
    private enum class Phase {New,Preparing,Ready,Consumed,Closed}
    private val gate=Any()
    private var phase=Phase.New
    private var candidate:LastPresentationStore.Candidate?=null
    val written:Boolean get()=synchronized(gate) {candidate?.written==true}

    suspend fun prepare():LastPresentationPrepare {
        synchronized(gate) {
            if(phase!=Phase.New) return LastPresentationPrepare.Changed
            phase=Phase.Preparing
        }
        return withContext(Dispatchers.IO) {
            val prepared=store.prepare(ticket,value,reset)
            val held=synchronized(gate) {
                if(phase!=Phase.Preparing) false
                else {
                    candidate=prepared.candidate
                    phase=if(prepared.result==LastPresentationPrepare.Ready) Phase.Ready else Phase.Consumed
                    true
                }
            }
            // close() may have completed while this IO was still creating the candidate.
            if(!held) {prepared.candidate?.close();LastPresentationPrepare.Changed} else prepared.result
        }
    }
    fun commit(current:()->Boolean):LastPresentationWrite = synchronized(gate) {
        if(phase!=Phase.Ready) return@synchronized LastPresentationWrite.Changed
        phase=Phase.Consumed
        candidate?.commit(current) ?: LastPresentationWrite.Failed
    }
    suspend fun close() {
        val owned=synchronized(gate) {phase=Phase.Closed;candidate}
        withContext(NonCancellable+Dispatchers.IO) {owned?.close()}
    }
}
