package com.xnotes.ui

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** The reset fingerprint is opaque, and an unreadable observation never carries one. */
internal class LastPresentationObservation internal constructor(
    val read:LastPresentationRead,
    private val origin:Any?=null,
    private val fingerprint:Any?=null,
    private val rootIdentity:Any?=null,
) {
    internal fun matches(root:Any,directory:Any?,head:Any?)=origin===root && rootIdentity!=null && rootIdentity==directory &&
        fingerprint!=null && fingerprint==head
    internal fun authorizes(root:Any,head:Any?,directory:Any?)=read===LastPresentationRead.Invalid && matches(root,directory,head)
}

/** Fixed private marker only. This component never opens the URIs written inside its payload. */
internal class LastPresentationStore(filesDir:File,private val fileOps:FileOps=SystemFileOps) {
    internal class Ticket internal constructor(internal val root:Any,internal val epoch:Any)
    internal interface FileOps {
        fun openRead(path:Path):InputStream
        fun replace(stage:Path,target:Path)
        fun deleteIfExists(path:Path)
    }
    private object SystemFileOps:FileOps {
        override fun openRead(path:Path)=Files.newInputStream(path,READ,NOFOLLOW_LINKS)
        override fun replace(stage:Path,target:Path) {Files.move(stage,target,ATOMIC_MOVE,REPLACE_EXISTING)}
        override fun deleteIfExists(path:Path) {Files.deleteIfExists(path)}
    }

    private data class Intent(val epoch:Any=Any(),val claim:Any?=null)
    private class Root {
        val gate=Any()
        val intent=AtomicReference(Intent())
    }
    private data class Identity(val key:Any,val created:FileTime)
    private data class Fingerprint(val identity:Identity?,val size:Long,val sha256:String?)
    private data class Head(val read:LastPresentationRead,val fingerprint:Fingerprint?)
    internal abstract class Candidate {
        abstract val written:Boolean
        abstract fun commit(current:()->Boolean):LastPresentationWrite
        abstract fun close()
    }
    internal data class Preparation(val result:LastPresentationPrepare,val candidate:Candidate?=null)

    private val suppliedRoot=filesDir
    private val directory=try {filesDir.canonicalFile.toPath()} catch(_:Exception) {null}
    private val directoryIdentity=directory?.let {identity(it,directory=true)}
    private val shared=directory?.let {roots.computeIfAbsent(it.toString()) {Root()}} ?: Root()
    private val target=directory?.resolve("last-presentation.json")

    fun observe():LastPresentationObservation {
        val head=readHead()
        return LastPresentationObservation(head.read,shared,head.fingerprint,directoryIdentity)
    }
    /** Actual opaque head comparison, not DTO equality or permission to read an original. */
    fun matches(observation:LastPresentationObservation):Boolean=synchronized(shared.gate) {
        rootCurrent() && observation.matches(shared,directoryIdentity,readHead().fingerprint) && rootCurrent()
    }

    /** Reservation never waits for file IO or for a previously accepted publication. */
    fun reserve():Ticket {
        while(true) {
            val before=shared.intent.get()
            val next=Intent(Any(),before.claim)
            if(shared.intent.compareAndSet(before,next)) return Ticket(shared,next.epoch)
        }
    }
    fun begin(ticket:Ticket,value:LastPresentation)=LastPresentationAttempt(this,ticket,value,null)
    fun beginReset(ticket:Ticket,expectedInvalid:LastPresentationObservation)=
        LastPresentationAttempt(this,ticket,LastPresentation.Home,expectedInvalid)

    internal fun prepare(ticket:Ticket,value:LastPresentation,reset:LastPresentationObservation?):Preparation {
        if(!current(ticket)) return Preparation(LastPresentationPrepare.Changed)
        if(!rootCurrent()) return Preparation(LastPresentationPrepare.Failed)
        val bytes=try {LastPresentationCodec.encode(value)} catch(_:IllegalArgumentException) {
            return Preparation(LastPresentationPrepare.Failed)
        }
        val head=readHead()
        if(reset!=null) {
            if(!reset.authorizes(shared,head.fingerprint,directoryIdentity) || head.read!==LastPresentationRead.Invalid)
                return Preparation(LastPresentationPrepare.Changed)
        } else if(head.read===LastPresentationRead.Invalid) return Preparation(LastPresentationPrepare.PreservedInvalid)
        val before=head.fingerprint ?: return Preparation(LastPresentationPrepare.Failed)
        if(!current(ticket)) return Preparation(LastPresentationPrepare.Changed)
        val root=directory ?: return Preparation(LastPresentationPrepare.Failed)
        val stage=root.resolve(".last-presentation-${UUID.randomUUID()}.pending")
        var channel:FileChannel?=null
        var candidate:Staged?=null
        try {
            if(!rootCurrent()) return Preparation(LastPresentationPrepare.Changed)
            channel=FileChannel.open(stage,CREATE_NEW,WRITE,NOFOLLOW_LINKS)
            val owned=identity(stage) ?: throw IOException("Cannot identify marker stage")
            candidate=Staged(ticket,stage,owned,hash(bytes),before,channel)
            val buffer=java.nio.ByteBuffer.wrap(bytes)
            while(buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
            if(!rootCurrent() || !candidate.matchesStage()) {
                candidate.close();return Preparation(LastPresentationPrepare.Changed)
            }
            return Preparation(LastPresentationPrepare.Ready,candidate)
        } catch(error:Exception) {
            candidate?.close()
            if(candidate==null) runCatching {channel?.close()}
            if(error is java.util.concurrent.CancellationException) throw error
            return Preparation(LastPresentationPrepare.Failed)
        }
    }

    private inner class Staged(
        val ticket:Ticket,val stage:Path,val owned:Identity,val sha256:String,
        val before:Fingerprint,val channel:FileChannel,
    ):Candidate() {
        @Volatile override var written=false;private set
        private val cleanupGate=Any()
        fun matchesStage()=fingerprint(stage)?.let {it.identity==owned && it.sha256==sha256}==true
        override fun commit(current:()->Boolean):LastPresentationWrite = synchronized(shared.gate) {
            // synchronized is reentrant. An IO seam must not let B publish inside A's claim.
            if(shared.intent.get().claim!=null || !this@LastPresentationStore.current(ticket))
                return@synchronized LastPresentationWrite.Changed
            if(!rootCurrent() || readHead().fingerprint!=before || !matchesStage() || !current())
                return@synchronized LastPresentationWrite.Changed
            // current() may itself reserve a later ticket. Recheck by CAS at the acceptance point.
            val intent=shared.intent.get()
            if(intent.epoch!==ticket.epoch || intent.claim!=null || !shared.intent.compareAndSet(intent,Intent(intent.epoch,this)))
                return@synchronized LastPresentationWrite.Changed
            try {
                try {
                    fileOps.replace(stage,requireNotNull(target))
                    written=true
                } catch(error:Exception) {
                    // Equal bytes alone cannot distinguish a completed move from the old head.
                    if(actualWritten()) written=true
                    else if(error is java.util.concurrent.CancellationException) throw error
                }
                if(written) LastPresentationWrite.Written else LastPresentationWrite.Failed
            } finally {
                while(true) {
                    val accepted=shared.intent.get()
                    if(accepted.claim!==this) break
                    val epoch=if(accepted.epoch===ticket.epoch) Any() else accepted.epoch
                    if(shared.intent.compareAndSet(accepted,Intent(epoch))) break
                }
            }
        }
        private fun actualWritten()=rootCurrent() && Files.notExists(stage,NOFOLLOW_LINKS) &&
            fingerprint(requireNotNull(target))?.let {it.identity==owned && it.sha256==sha256}==true
        override fun close()=synchronized(cleanupGate) {
            // Changed bytes on our inode remain ours; a replacement inode/link never does.
            try {
                if(rootCurrent() && stage.parent==directory && stage.toFile().canonicalFile.toPath()==stage &&
                    identity(stage)==owned) fileOps.deleteIfExists(stage)
            } catch(_:Exception) { /* Failed cleanup material stays; no global pending-file cleanup. */ }
            finally {runCatching {channel.close()}}
        }
    }

    private fun current(ticket:Ticket)=ticket.root===shared && shared.intent.get().epoch===ticket.epoch
    private fun rootCurrent():Boolean = try {
        directory!=null && directoryIdentity!=null && suppliedRoot.canonicalFile.toPath()==directory &&
            identity(directory,directory=true)==directoryIdentity
    } catch(_:Exception) {false}

    private fun readHead():Head {
        if(!rootCurrent()) return Head(LastPresentationRead.Invalid,null)
        val path=target ?: return Head(LastPresentationRead.Invalid,null)
        try {
            if(Files.notExists(path,NOFOLLOW_LINKS)) return Head(LastPresentationRead.Absent,absent)
            val read=readFile(path) ?: return Head(LastPresentationRead.Invalid,null)
            if(!rootCurrent()) return Head(LastPresentationRead.Invalid,null)
            val value=LastPresentationCodec.parse(read.second)
            return Head(value?.let {LastPresentationRead.Ready(it)} ?: LastPresentationRead.Invalid,read.first)
        } catch(_:Exception) {return Head(LastPresentationRead.Invalid,null)}
    }
    private fun fingerprint(path:Path):Fingerprint? = try {readFile(path)?.first} catch(_:Exception) {null}
    private fun readFile(path:Path):Pair<Fingerprint,ByteArray>? {
        if(!rootCurrent() || path.parent!=directory || path.toFile().canonicalFile.toPath()!=path) return null
        val attributes=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        val id=identity(attributes) ?: return null
        if(!attributes.isRegularFile || attributes.size() !in 0..LastPresentationCodec.MAX_BYTES.toLong()) return null
        val bytes=fileOps.openRead(path).use {input->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {
                val count=input.read(buffer)
                if(count<0) break
                if(count==0) {
                    val next=input.read();if(next<0) break
                    if(output.size()>=LastPresentationCodec.MAX_BYTES) throw IOException("Marker exceeds budget")
                    output.write(next)
                } else {
                    if(count>LastPresentationCodec.MAX_BYTES-output.size()) throw IOException("Marker exceeds budget")
                    output.write(buffer,0,count)
                }
            }
            output.toByteArray()
        }
        val after=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        if(!after.isRegularFile || identity(after)!=id || after.size()!=attributes.size() || bytes.size.toLong()!=after.size() ||
            attributes.lastModifiedTime()!=after.lastModifiedTime() || !rootCurrent()) return null
        return Fingerprint(id,bytes.size.toLong(),hash(bytes)) to bytes
    }

    private companion object {
        val roots=ConcurrentHashMap<String,Root>()
        val absent=Fingerprint(null,0,null)
        fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        fun identity(attributes:BasicFileAttributes):Identity?=attributes.fileKey()?.let {Identity(it,attributes.creationTime())}
        fun identity(path:Path,directory:Boolean=false):Identity? = try {
            val attributes=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            if(if(directory) attributes.isDirectory else attributes.isRegularFile) identity(attributes) else null
        } catch(_:Exception) {null}
    }
}
