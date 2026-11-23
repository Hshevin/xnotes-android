package com.xnotes.storage

import com.xnotes.core.util.CoreLock
import com.xnotes.core.util.LockedMap
import com.xnotes.core.util.locked

/** In-process retirement of location/alias/kind/page-contract observations. No source-write permit. */
class DocumentRegistryIdentityEpoch private constructor() {
    private class State(var version:Long=0,var busy:Int=0)
    private val gate=CoreLock()
    private val identities=mutableMapOf<String,State>()
    class Ticket private constructor(private val valid:()->Boolean) {
        fun current()=valid()
        companion object {internal fun issue(valid:()->Boolean)=Ticket(valid)}
    }
    fun observe(id:String):Ticket=gate.locked {
        val state=identities.getOrPut(id) {State()}
        check(state.busy==0) {"Registry identity mutation in progress"}
        val version=state.version
        Ticket.issue {gate.locked {state.busy==0 && state.version==version}}
    }
    /** Starts before the first structural SQL; the caller closes only after commit or rollback. */
    fun begin(ids:Set<String>):AutoCloseable {
        val changed=gate.locked {
            val found=ids.map {identities.getOrPut(it) {State()}}
            check(found.all {it.version<Long.MAX_VALUE && it.busy<Int.MAX_VALUE})
            found.forEach {it.version++;it.busy++};found
        }
        var closed=false
        return AutoCloseable {gate.locked {
            if(!closed) {closed=true;changed.forEach {check(it.busy>0);it.busy--}}
        }}
    }
    companion object {
        private val databases=LockedMap<String,DocumentRegistryIdentityEpoch>()
        fun forCanonicalDatabase(path:String)=databases.getOrPut(path) {DocumentRegistryIdentityEpoch()}
    }
}
