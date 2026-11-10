package com.xnotes.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Orders one cold native presentation restore across its panes: validate the description, prepare every
 * private cache, verify them as a group, claim them all before the first adoption, then connect borrowed
 * views and finally the layout. Each pane still owns its model install, private cache and input checks;
 * this only sequences them through [Host] and never sees an Editor.
 */
internal class NativeColdRestoreCoordinator(private val host:Host) {
    /** The few pane operations one restore needs; every adapter binds its own pane. */
    interface Host {
        /** Allocates the panes [slots] name; called only after the description passed the contract. */
        fun ensurePanes(slots:List<ColdNativeSlot>)
        /** A proof that the panes are still idle and unchanged since this call, valid before any preparation. */
        fun captureInitialProof():()->Boolean
        /** Preparing advances each slot's adoption request, so the adoption proof is captured only afterwards. */
        fun captureAdoptionProof():()->Boolean
        suspend fun prepareNote(slot:ColdNativeSlot.Note):Participant?
        suspend fun prepareCanvas(slot:ColdNativeSlot.Canvas):Participant?
        fun attachShared(slot:ColdNativeSlot.Shared)
        fun applyLayout(layout:ColdNativeLayout)
    }

    /** One pane's prepared private cache; its own verify and claim check the real owner, model, jobs and input. */
    interface Participant {
        suspend fun verify():Boolean
        fun claim():Boolean
        fun apply()
        /** Releases temporary resources and keeps an unadopted cache for a later attempt. */
        suspend fun close()
    }

    suspend fun prepare(value:LastPresentation.Native,stillCurrent:()->Boolean):PreparedColdNativePresentation? {
        // The persisted contract, checked before any pane is allocated or private cache is read.
        if(!stillCurrent() || !LastPresentationContract.accepts(value)) return null
        host.ensurePanes(value.slots)
        val initial=host.captureInitialProof()
        val participants=ArrayList<Participant>()
        var handedOff=false
        var failure:Throwable?=null
        try {
            if(!stillCurrent() || !initial()) return null
            for(slot in value.slots) {
                participants+=when(slot) {
                    is ColdNativeSlot.Note->host.prepareNote(slot) ?: return null
                    is ColdNativeSlot.Canvas->host.prepareCanvas(slot) ?: return null
                    else->continue // Home and borrowed views never read a private cache.
                }
                // Preparing is read-only but advances the slot's adoption request, so rebind below.
                if(!stillCurrent()) return null
            }
            val adoption=host.captureAdoptionProof()
            val cap=PreparedColdNativePresentation(participants,value,{stillCurrent() && adoption()},host)
            handedOff=true;return cap
        } catch(error:Throwable) {
            failure=error;throw error
        } finally {if(!handedOff) closeAll(participants,failure)}
    }
}

/** A prepared restore that adopts at most once; closing without adoption leaves every cache recoverable. */
internal class PreparedColdNativePresentation internal constructor(
    private val participants:List<NativeColdRestoreCoordinator.Participant>,
    private val value:LastPresentation.Native,
    private val current:()->Boolean,
    private val host:NativeColdRestoreCoordinator.Host,
) {
    private var claimed=false
    private var adopted=false
    private var closed=false
    suspend fun verify():Boolean=!closed && !claimed && current() && participants.all {it.verify()} && current()
    fun claimForAdoption():Boolean {
        if(closed || claimed || !current() || !participants.all {it.claim()}) return false
        claimed=true;return true
    }
    fun applyAdoption() {
        check(!closed && claimed && !adopted);adopted=true
        // Every proof has been consumed before the first model or view changes.
        for(participant in participants) participant.apply()
        for(slot in value.slots.filterIsInstance<ColdNativeSlot.Shared>()) host.attachShared(slot)
        host.applyLayout(value.layout)
    }
    suspend fun close() {if(!closed) {closed=true;closeAll(participants)}}
}

private suspend fun closeAll(participants:List<NativeColdRestoreCoordinator.Participant>,original:Throwable?=null)=withContext(NonCancellable) {
    var failure=original
    for(participant in participants) {
        try {participant.close()} catch(error:Throwable) {
            val first=failure
            if(first==null) failure=error else if(first!==error) first.addSuppressed(error)
        }
    }
    // Release every candidate, but never replace the error that caused preparation to unwind.
    if(original==null) failure?.let {throw it}
    Unit
}
