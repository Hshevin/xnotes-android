package com.xnotes.ui

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SplitRecentState(val loaded:Boolean,val read:SplitArrangementRead)
internal sealed interface SplitRecentWrite {
    data object Written:SplitRecentWrite
    data object Superseded:SplitRecentWrite
    data object PreservedInvalid:SplitRecentWrite
    data object Failed:SplitRecentWrite
}

/** Shared ordering for repeatable recent arrangements; no cold-presentation or source authority. */
internal class SplitRecentWriter private constructor(private val filesDir:File,private val fileOps:FileOps) {
    internal class Ticket internal constructor(internal val issuer:SplitRecentWriter,internal val epoch:Long,internal val explicit:Boolean) {
        internal val submitted=AtomicBoolean(false)
    }
    internal interface FileOps {
        fun openRead(path:Path):InputStream
        fun replace(stage:Path,target:Path)
    }
    private object SystemFileOps:FileOps {
        override fun openRead(path:Path):InputStream=Files.newInputStream(path,READ,NOFOLLOW_LINKS)
        override fun replace(stage:Path,target:Path) {Files.move(stage,target,ATOMIC_MOVE,REPLACE_EXISTING)}
    }

    private val registration=Any()
    private var epoch=0L
    private var claimed:Ticket?=null
    private var tail:Job?=null
    private val mutableState=MutableStateFlow(SplitRecentState(false,SplitArrangementRead.Absent))
    val state:StateFlow<SplitRecentState> = mutableState.asStateFlow()
    private val initialResult=CompletableDeferred<Unit>()
    val initialization:Deferred<Unit> get()=initialResult

    private fun initialize() {scope.launch {
        try {
            // A slow startup read cannot overwrite a subsequent checked write's observable state.
            while(!mutableState.value.loaded) {
                val observed=mutableState.value
                val observedEpoch=synchronized(registration) {epoch}
                val read=try {readCurrent()} catch(_:Exception) {SplitArrangementRead.Invalid}
                if(synchronized(registration) {epoch==observedEpoch})
                    mutableState.compareAndSet(observed,SplitRecentState(true,read))
            }
        } finally {initialResult.complete(Unit)}
    }}

    /** Registration never performs IO or invokes a caller callback under its short memory lock. */
    fun reserve(explicit:Boolean=false):Ticket=synchronized(registration) {Ticket(this,++epoch,explicit)}

    fun submit(ticket:Ticket,request:SplitOpenRequest):Deferred<SplitRecentWrite> {
        if(ticket.issuer!==this || !ticket.submitted.compareAndSet(false,true))
            return CompletableDeferred<SplitRecentWrite>(SplitRecentWrite.Superseded)
        val result=CompletableDeferred<SplitRecentWrite>()
        val job=synchronized(registration) {
            val previous=tail
            scope.launch(start=CoroutineStart.LAZY) {
                previous?.join()
                val outcome=try {write(ticket,request)} catch(_:Exception) {SplitRecentWrite.Failed}
                result.complete(outcome)
            }.also {pending->
                tail=pending
                pending.invokeOnCompletion {synchronized(registration) {if(tail===pending) tail=null}}
            }
        }
        // The caller owns only its result. Cancellation never cancels the writer's accepted IO.
        job.start()
        return result
    }

    /** Wait only for the prefix already accepted at this call; future reservations are unrelated. */
    suspend fun awaitAccepted() {
        val accepted=synchronized(registration) {tail}
        accepted?.join()
        initialResult.join()
    }

    private fun current(ticket:Ticket)=synchronized(registration) {ticket.issuer===this && epoch==ticket.epoch}
    private fun claim(ticket:Ticket)=synchronized(registration) {
        if(epoch!=ticket.epoch || claimed!=null) false else {claimed=ticket;true}
    }

    private fun write(ticket:Ticket,request:SplitOpenRequest):SplitRecentWrite {
        var stage:Path?=null
        try {
            if(!current(ticket)) return SplitRecentWrite.Superseded
            val bytes=when(request) {
                is SplitOpenRequest.Mixed->SplitOpenCodec.encodeMixed(request.request)
                is SplitOpenRequest.Native->SplitOpenCodec.encodeNative(request.request)
            }
            val canonical=requireNotNull(SplitOpenCodec.parse(bytes))
            if(!current(ticket)) return SplitRecentWrite.Superseded
            val target=SplitArrangementPaths.recent(filesDir).toPath()
            stage=Files.createTempFile(target.parent,".recent-",".pending")
            FileOutputStream(stage.toFile()).use {stream->stream.write(bytes);stream.fd.sync()}
            // Inspect the actual current file after staging, not a cached startup observation.
            val observed=readCurrent()
            if(!current(ticket)) return SplitRecentWrite.Superseded
            if(observed===SplitArrangementRead.Invalid && !ticket.explicit) {
                mutableState.value=SplitRecentState(true,observed)
                return SplitRecentWrite.PreservedInvalid
            }
            SplitArrangementPaths.recent(filesDir) // Reject a replaced or escaping target before claiming.
            if(!claim(ticket)) return SplitRecentWrite.Superseded
            fileOps.replace(stage,target)
            mutableState.value=SplitRecentState(true,SplitArrangementRead.Ready(canonical))
            return SplitRecentWrite.Written
        } catch(_:Exception) {return SplitRecentWrite.Failed
        } finally {
            synchronized(registration) {if(claimed===ticket) claimed=null}
            stage?.let {try {Files.deleteIfExists(it)} catch(_:Exception) {}}
        }
    }

    private fun readCurrent():SplitArrangementRead {
        val path=SplitArrangementPaths.recent(filesDir).toPath()
        if(Files.notExists(path,NOFOLLOW_LINKS)) return SplitArrangementRead.Absent
        require(Files.isRegularFile(path,NOFOLLOW_LINKS))
        val request=fileOps.openRead(path).use {SplitOpenCodec.read(it,allowLegacyFormat=true)}
        return request?.let {SplitArrangementRead.Ready(it)} ?: SplitArrangementRead.Invalid
    }

    companion object {
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val writers=hashMapOf<String,SplitRecentWriter>()
        fun forFilesDir(filesDir:File,fileOps:FileOps=SystemFileOps):SplitRecentWriter {
            val canonical=filesDir.canonicalFile
            return synchronized(writers) {
                writers[canonical.path] ?: SplitRecentWriter(canonical,fileOps).also {
                    writers[canonical.path]=it;it.initialize()
                }
            }
        }
    }
}
