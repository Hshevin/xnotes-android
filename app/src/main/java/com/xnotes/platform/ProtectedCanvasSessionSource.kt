package com.xnotes.platform

import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.CanvasCodec
import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.storage.PreparedProtectedCanvas
import com.xnotes.storage.ProtectedCanvasIdentityMismatch
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface ProtectedCanvasSessionLoad {
    data object Absent:ProtectedCanvasSessionLoad
    data object Cleared:ProtectedCanvasSessionLoad
    data object ProtectedRecovery:ProtectedCanvasSessionLoad
    data object Mismatched:ProtectedCanvasSessionLoad
    class Invalid(val cause:Exception):ProtectedCanvasSessionLoad
    class Ready(val prepared:PreparedCanvasSession):ProtectedCanvasSessionLoad
}
internal class PreparedCanvasSession internal constructor(
    val body:PreparedProtectedCanvas,val metadata:CanvasSessionStore.Metadata,
    private val directory:File,private val owner:LegacySessionQueue.Owner,private val access:LegacyAccess,
    private val bodyHash:String,private val metadataHash:String,private val rejectNoteBody:Boolean,
):AutoCloseable {
    @Volatile private var closed=false
    class Verified internal constructor(private val owner:LegacySessionQueue.Owner,
        private val fence:LegacySessionQueue.Fence,private val absent:LegacyAbsentObservation,
        private val prepared:PreparedCanvasSession) {
        private val used=AtomicBoolean(false)
        /** The caller checks its actual Editor/model/input on Main immediately before consuming. */
        fun consume():Boolean=used.compareAndSet(false,true) && !prepared.closed && owner.consumeForRestore(fence,absent)
    }
    suspend fun verify(checkActive:()->Unit={}):Verified? {
        val caller=currentCoroutineContext()
        val check={caller.ensureActive();checkActive();check(!closed);if(!owner.isCurrent()) throw CancellationException("Canvas session owner retired")}
        check()
        val operation=owner.submitTracked {
            check()
            val observation=(access.observeAbsent() as? LegacyAbsentResult.Granted)?.observation ?: return@submitTracked null
            if(rejectNoteBody && canvasEntryExists(File(directory,"document.xnote"))) return@submitTracked null
            val original=File(directory,"canvas.xcanvas")
            requireCanvasRegular(original)
            if(BundleMigration.hash(original) {check();false}!=bodyHash) return@submitTracked null
            val bytes=readCanvasMetadata(File(directory,"canvas-session.json"),check)
            if(canvasMetadataHash(bytes)!=metadataHash) return@submitTracked null
            check();observation
        } ?: return null
        val outcome=operation.awaitSettled()
        check()
        return when(outcome) {
            is LegacySessionQueue.Outcome.Success->outcome.value?.let {Verified(owner,operation.settledFence(),it,this)}
            is LegacySessionQueue.Outcome.Failure->{if(outcome.error is CancellationException) throw outcome.error;null}
        }
    }
    override fun close() {if(!closed) {closed=true;body.close()}}
}
internal class ProtectedCanvasSessionSource(
    private val directory:File,private val owner:LegacySessionQueue.Owner,private val native:NativeWorkingSession,
    private val codec:CanvasCodec,private val assets:File,private val temporary:File,
) {
    private class Capture(val metadata:CanvasSessionStore.Metadata,val metadataHash:String,val bodyHash:String)
    suspend fun prepare(hint:MapNativeRestoreHint?=null,checkActive:()->Unit={}):ProtectedCanvasSessionLoad {
        val caller=currentCoroutineContext()
        val raw=AtomicReference<BundleSnapshot?>();val prepared=AtomicReference<PreparedCanvasSession?>()
        var accepted:LegacySessionQueue.Accepted<Any>?=null
        val check={caller.ensureActive();checkActive();if(!owner.isCurrent()) throw CancellationException("Canvas session owner retired")}
        suspend fun cleanup(includePrepared:Boolean)=withContext(NonCancellable+Dispatchers.IO) {
            accepted?.awaitSettled()
            try {if(includePrepared) prepared.getAndSet(null)?.close()} finally {raw.getAndSet(null)?.close()}
        }
        try {
            val result=withContext(Dispatchers.IO) {
                check()
                when(val state=native.loadState(check)) {
                    NativeWorkingSession.LoadState.Cleared->ProtectedCanvasSessionLoad.Cleared
                    is NativeWorkingSession.LoadState.Active->{state.read.close();ProtectedCanvasSessionLoad.ProtectedRecovery}
                    NativeWorkingSession.LoadState.Absent->{
                        val access=NativeWorkingSession.legacyAccess(directory)
                        val operation=owner.submitTracked<Any> {
                            check()
                            if(access.observeAbsent() !is LegacyAbsentResult.Granted) return@submitTracked ProtectedCanvasSessionLoad.ProtectedRecovery
                            if(hint!=null && (hint.kind!=DocumentKind.CANVAS || canvasEntryExists(File(directory,"document.xnote"))))
                                return@submitTracked ProtectedCanvasSessionLoad.Mismatched
                            val body=File(directory,"canvas.xcanvas")
                            if(!canvasEntryExists(body)) {
                                if(canvasEntryExists(File(directory,"canvas-session.json"))) throw WorkingSessionCache.Invalid()
                                return@submitTracked ProtectedCanvasSessionLoad.Absent
                            }
                            requireCanvasRegular(body)
                            val metadataBytes=readCanvasMetadata(File(directory,"canvas-session.json"),check)
                            val metadata=ProtectedCanvasMetadata.parse(metadataBytes)
                            if(hint!=null && hint.path!=metadata.path) return@submitTracked ProtectedCanvasSessionLoad.Mismatched
                            check(temporary.isDirectory || temporary.mkdirs())
                            val snapshot=BundleSnapshot.take(body,temporary) {check();false}
                            raw.set(snapshot);check()
                            Capture(metadata,canvasMetadataHash(metadataBytes),snapshot.sourceHash)
                        } ?: throw CancellationException("Canvas session owner retired")
                        accepted=operation
                        val capture=when(val outcome=operation.awaitSettled()) {
                            is LegacySessionQueue.Outcome.Success->outcome.value
                            is LegacySessionQueue.Outcome.Failure->throw outcome.error
                        }
                        check()
                        if(capture is ProtectedCanvasSessionLoad) capture else {
                            capture as Capture
                            val body=PreparedProtectedCanvas.readChecked(requireNotNull(raw.getAndSet(null)),codec,assets,hint?.documentId,check)
                            val value=PreparedCanvasSession(body,capture.metadata,directory,owner,access,capture.bodyHash,capture.metadataHash,hint!=null)
                            prepared.set(value)
                            val document=body.document
                            document.path=capture.metadata.path;document.displayName=capture.metadata.displayName;document.dirty=capture.metadata.dirty
                            ProtectedCanvasSessionLoad.Ready(value)
                        }
                    }
                }
            }
            cleanup(result !is ProtectedCanvasSessionLoad.Ready)
            check()
            if(result is ProtectedCanvasSessionLoad.Ready) check(prepared.compareAndSet(result.prepared,null))
            return result
        } catch(error:CancellationException) {throw error}
        catch(_:ProtectedCanvasIdentityMismatch) {return ProtectedCanvasSessionLoad.Mismatched}
        catch(error:Exception) {return ProtectedCanvasSessionLoad.Invalid(error)}
        finally {if(prepared.get()!=null || raw.get()!=null || accepted?.result?.isCompleted==false) cleanup(true)}
    }
}

private fun canvasEntryExists(file:File):Boolean=try {
    Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS);true
} catch(_:NoSuchFileException) {false}
private fun requireCanvasRegular(file:File) {
    require(Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).isRegularFile)
}
private fun readCanvasMetadata(file:File,checkActive:()->Unit):ByteArray {
    requireCanvasRegular(file)
    require(Files.size(file.toPath())<=ProtectedCanvasMetadata.MAX_BYTES)
    return Files.newInputStream(file.toPath(),READ,NOFOLLOW_LINKS).use {input->
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
        while(true) {
            checkActive()
            val count=input.read(buffer)
            if(count<0) break
            require(count<=ProtectedCanvasMetadata.MAX_BYTES-output.size())
            output.write(buffer,0,count)
        }
        checkActive();output.toByteArray()
    }
}
private fun canvasMetadataHash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
