package com.xnotes.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

/** Runtime ordering for legacy files, never a native save or body-reuse proof. */
internal object LegacySessionQueue {
    sealed interface Outcome<out T> {
        data class Success<T>(val value:T):Outcome<T>
        data class Failure(val error:Throwable):Outcome<Nothing>
    }
    class Fence internal constructor(internal val root:Root,internal val owner:Owner,internal val sequence:Long)
    class Accepted<T> internal constructor(val result:Deferred<Outcome<T>>,internal val job:Job,
        private val settled:Deferred<Throwable?>,private val fence:Fence) {
        suspend fun awaitSettled():Outcome<T> {val failure=settled.await();return if(failure==null) result.await() else Outcome.Failure(failure)}
        fun settledFence():Fence {check(settled.isCompleted);return fence}
    }
    class Owner internal constructor(private val root:Root) {
        private var lastAccepted:Job?=null
        private val retiredSignal=CompletableDeferred<Unit>()
        val onRetired:kotlinx.coroutines.selects.SelectClause1<Unit> get()=retiredSignal.onAwait
        internal fun retire() {retiredSignal.complete(Unit)}
        internal fun rememberAccepted(job:Job) {lastAccepted=job}
        fun isCurrent():Boolean=synchronized(root) {root.owner===this}

        /** Accepted IO owns its lifetime even if the caller or this owner later retires. */
        fun <T> submit(block:suspend ()->T):Deferred<Outcome<T>>?=submitTracked(block)?.result
        fun <T> submitTracked(block:suspend ()->T):Accepted<T>? {
            val accepted=synchronized(root) {
                if(root.owner!==this) null else root.enqueue(this,block).also {lastAccepted=it.job}
            } ?: return null
            accepted.job.start()
            return accepted
        }
        /** Queue -> NativeRoot -> Cache, with no application callbacks or model mutation locked. */
        fun consumeForRestore(fence:Fence,observation:LegacyAbsentObservation):Boolean=synchronized(root) {
            fence.root===root && fence.owner===this && root.owner===this && root.acceptedSequence==fence.sequence &&
                root.settledSequence>=fence.sequence && observation.access.consumeForRestore(observation)
        }
        /** A settled body receipt can be reused for view-only checkpoints without claiming it. */
        fun matchesForCheckpoint(fence:Fence,observation:LegacyAbsentObservation):Boolean=synchronized(root) {
            fence.root===root && fence.owner===this && root.owner===this && root.acceptedSequence==fence.sequence &&
                root.settledSequence>=fence.sequence && observation.access.isCurrent(observation)
        }
        suspend fun awaitAccepted() {val accepted=synchronized(root) {lastAccepted};accepted?.join()}
    }
    internal class Root {
        var owner:Owner?=null
        var acceptedSequence=0L
        var settledSequence=0L
        private var tail:Job?=null

        /** Registration only; the lazy body and all completion signals run outside this monitor. */
        fun <T> enqueue(owner:Owner,block:suspend ()->T):Accepted<T> {
            check(acceptedSequence<Long.MAX_VALUE)
            val sequence=++acceptedSequence;val previous=tail
            val result=CompletableDeferred<Outcome<T>>();val settled=CompletableDeferred<Throwable?>()
            val job=scope.launch(start=CoroutineStart.LAZY) {
                val outcome=try {previous?.join();Outcome.Success(block())}
                    catch(error:Throwable) {Outcome.Failure(error)}
                result.complete(outcome)
            }
            tail=job
            owner.rememberAccepted(job)
            job.invokeOnCompletion {error->
                synchronized(this) {
                    settledSequence=maxOf(settledSequence,sequence)
                    if(tail===job) tail=null
                }
                // A structured child can fail after block returned. That is still a settled job.
                if(!result.isCompleted) result.complete(Outcome.Failure(error ?: IllegalStateException("Legacy operation did not produce a result")))
                settled.complete(error)
            }
            return Accepted(result,job,settled,Fence(this,owner,sequence))
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val roots=hashMapOf<String,WeakReference<Root>>()

    fun attach(directory:File):Owner {
        val key=directory.canonicalPath
        val root=synchronized(roots) {
            roots.entries.removeAll {it.value.get()==null}
            roots[key]?.get() ?: Root().also {roots[key]=WeakReference(it)}
        }
        val (owner,previous,fence)=synchronized(root) {
            val previous=root.owner;val owner=Owner(root);root.owner=owner
            Triple(owner,previous,root.enqueue(owner) {Unit})
        }
        fence.job.start()
        previous?.retire()
        return owner
    }
}
