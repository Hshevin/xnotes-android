package com.xnotes.storage

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** In-process retirement of location/alias/kind/page-contract observations. No source-write permit. */
internal class DocumentRegistryIdentityEpoch private constructor() {
    private class State(var version:Long=0,var busy:Int=0)
    private val gate=Any()
    private val identities=mutableMapOf<String,State>()
    internal class Ticket private constructor(private val valid:()->Boolean) {
        fun current()=valid()
        companion object {internal fun issue(valid:()->Boolean)=Ticket(valid)}
    }
    fun observe(id:String):Ticket=synchronized(gate) {
        val state=identities.getOrPut(id) {State()}
        check(state.busy==0) {"Registry identity mutation in progress"}
        val version=state.version
        Ticket.issue {synchronized(gate) {state.busy==0 && state.version==version}}
    }
    /** Starts before the first structural SQL; the caller closes only after commit or rollback. */
    fun begin(ids:Set<String>):AutoCloseable {
        val changed=synchronized(gate) {
            val found=ids.map {identities.getOrPut(it) {State()}}
            check(found.all {it.version<Long.MAX_VALUE && it.busy<Int.MAX_VALUE})
            found.forEach {it.version++;it.busy++};found
        }
        var closed=false
        return AutoCloseable {synchronized(gate) {
            if(!closed) {closed=true;changed.forEach {check(it.busy>0);it.busy--}}
        }}
    }
    companion object {
        private val databases=ConcurrentHashMap<String,DocumentRegistryIdentityEpoch>()
        fun forDatabase(path:String)=databases.computeIfAbsent(File(path).canonicalPath) {DocumentRegistryIdentityEpoch()}
    }
}
