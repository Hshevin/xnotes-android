package com.xnotes.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

/** Runtime ordering for the legacy session files, never a native save or body-reuse proof. */
internal object LegacySessionQueue {
    sealed interface Outcome<out T> {
        data class Success<T>(val value:T):Outcome<T>
        data class Failure(val error:Throwable):Outcome<Nothing>
    }

    class Owner internal constructor(private val root:Root) {
        private var lastAccepted:Job?=null
        private val retiredSignal=CompletableDeferred<Unit>()
        val onRetired:kotlinx.coroutines.selects.SelectClause1<Unit> get()=retiredSignal.onAwait
        internal fun retire() {retiredSignal.complete(Unit)}

        /** Latest announced owner; already accepted commands still execute after retirement. */
        fun isCurrent():Boolean=synchronized(root) {root.owner===this}

        /**
         * Returns null only when this owner has retired. Accepted work owns its lifetime: cancelling
         * the result or its awaiter does not cancel IO or let the next command overtake it.
         * Settle input before submitting; a command must not wait for an input transaction to end.
         */
        fun <T> submit(block:suspend ()->T):Deferred<Outcome<T>>?=synchronized(root) {
            if(root.owner!==this) null else {
                val (result,job)=root.enqueue(block)
                lastAccepted=job
                result
            }
        }

        /** Wait for this owner's accepted prefix before reclaiming its encoder assets, even retired. */
        suspend fun awaitAccepted() {
            val accepted=synchronized(root) {lastAccepted}
            accepted?.join()
        }
    }

    internal class Root {
        var owner:Owner?=null
        private var tail:Job?=null

        /** Called only under this root's short registration lock; callbacks execute outside it. */
        fun <T> enqueue(block:suspend ()->T):Pair<Deferred<Outcome<T>>,Job> {
            val previous=tail
            val result=CompletableDeferred<Outcome<T>>()
            val job=scope.launch {
                val outcome=try {previous?.join();Outcome.Success(block())}
                    catch(error:Throwable) {Outcome.Failure(error)}
                result.complete(outcome)
            }
            tail=job
            // The handler holds this root while work is pending, including structured child jobs.
            job.invokeOnCompletion {synchronized(this) {if(tail===job) tail=null}}
            return result to job
        }
    }

    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val roots=hashMapOf<String,WeakReference<Root>>()

    /** Announce an actual restore/adoption intent, without touching session contents on disk. */
    fun attach(directory:File):Owner {
        val key=directory.canonicalPath
        val root=synchronized(roots) {
            roots.entries.removeAll {it.value.get()==null}
            roots[key]?.get() ?: Root().also {roots[key]=WeakReference(it)}
        }
        val (owner,previous)=synchronized(root) {
            val previous=root.owner
            val owner=Owner(root)
            root.owner=owner
            root.enqueue {Unit} // Claim fence follows every command accepted before this intent.
            owner to previous
        }
        // Completion may immediately resume a caller, including one that announces another owner.
        // Publish this intent and its fence first; never invoke user continuations under root's lock.
        previous?.retire()
        return owner
    }
}
