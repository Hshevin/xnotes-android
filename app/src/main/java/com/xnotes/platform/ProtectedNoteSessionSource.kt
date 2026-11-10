package com.xnotes.platform

import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.DocumentCodec
import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.storage.PdfResources
import com.xnotes.storage.PreparedProtectedNote
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface ProtectedNoteSessionLoad {
    data object Absent:ProtectedNoteSessionLoad
    data object CanvasAvailable:ProtectedNoteSessionLoad
    data object Cleared:ProtectedNoteSessionLoad
    data object ProtectedRecovery:ProtectedNoteSessionLoad
    data object Mismatched:ProtectedNoteSessionLoad
    class Invalid(val cause:Exception):ProtectedNoteSessionLoad
    class Ready(val prepared:PreparedNoteSession):ProtectedNoteSessionLoad
}

/** Frozen body and hints. Final verification issues a one-use queue/native observation. */
internal class PreparedNoteSession internal constructor(
    val body:PreparedProtectedNote,val metadata:SessionStore.Metadata,
    private val directory:File,private val owner:LegacySessionQueue.Owner,
    private val access:LegacyAccess,private val bodyHash:String,private val metadataHash:String,
):AutoCloseable {
    @Volatile private var closed=false
    class Verified internal constructor(
        private val owner:LegacySessionQueue.Owner,private val fence:LegacySessionQueue.Fence,
        private val absent:LegacyAbsentObservation,
    ) {
        /** Caller checks its actual Editor/model/input on Main immediately before this call. */
        fun consume():Boolean=owner.consumeForRestore(fence,absent)
    }
    suspend fun verify(checkActive:()->Unit={}):Verified? {
        val caller=currentCoroutineContext()
        val check={caller.ensureActive();checkActive();check(!closed);if(!owner.isCurrent()) throw CancellationException("Session owner retired")}
        check()
        val operation=owner.submitTracked {
            check()
            val observation=(access.observeAbsent() as? LegacyAbsentResult.Granted)?.observation ?: return@submitTracked null
            val original=File(directory,"document.xnote")
            requireRegular(original)
            if(BundleMigration.hash(original) {check();false}!=bodyHash) return@submitTracked null
            val bytes=readMetadata(File(directory,"session.json"),check)
            if(hash(bytes)!=metadataHash) return@submitTracked null
            check();observation
        } ?: return null
        val outcome=operation.awaitSettled()
        check()
        return when(outcome) {
            is LegacySessionQueue.Outcome.Success->outcome.value?.let {Verified(owner,operation.settledFence(),it)}
            is LegacySessionQueue.Outcome.Failure->{
                if(outcome.error is CancellationException) throw outcome.error
                null
            }
        }
    }
    override fun close() {if(!closed) {closed=true;body.close()}}
}

/** Read this slot's actual cache. Hints never open or grant writes to the remembered original. */
internal class ProtectedNoteSessionSource(
    private val directory:File,private val owner:LegacySessionQueue.Owner,private val native:NativeWorkingSession,
    private val resources:PdfResources,private val codec:DocumentCodec,
    private val pdfDirectory:File,private val assets:File,private val temporary:File,
) {
    private class Capture(val metadata:SessionStore.Metadata,val metadataHash:String,val bodyHash:String)
    suspend fun prepare(hint:MapNativeRestoreHint?=null,checkActive:()->Unit={}):ProtectedNoteSessionLoad {
        val caller=currentCoroutineContext()
        val raw=AtomicReference<BundleSnapshot?>()
        val prepared=AtomicReference<PreparedNoteSession?>()
        var accepted:LegacySessionQueue.Accepted<Any>?=null
        val check={caller.ensureActive();checkActive();if(!owner.isCurrent()) throw CancellationException("Session owner retired")}
        suspend fun cleanup(includePrepared:Boolean)=withContext(NonCancellable+Dispatchers.IO) {
            accepted?.awaitSettled()
            try {if(includePrepared) prepared.getAndSet(null)?.close()}
            finally {raw.getAndSet(null)?.close()}
        }
        try {
            val result=withContext(Dispatchers.IO) {
                check()
                when(val state=native.loadState(check)) {
                    NativeWorkingSession.LoadState.Cleared->ProtectedNoteSessionLoad.Cleared
                    is NativeWorkingSession.LoadState.Active->{state.read.close();ProtectedNoteSessionLoad.ProtectedRecovery}
                    NativeWorkingSession.LoadState.Absent->{
                        val access=NativeWorkingSession.legacyAccess(directory)
                        val operation=owner.submitTracked<Any> {
                            check()
                            if(access.observeAbsent() !is LegacyAbsentResult.Granted) return@submitTracked ProtectedNoteSessionLoad.ProtectedRecovery
                            if(exists(File(directory,"canvas.xcanvas"))) return@submitTracked ProtectedNoteSessionLoad.CanvasAvailable
                            val body=File(directory,"document.xnote")
                            if(!exists(body)) {
                                if(exists(File(directory,"session.json"))) throw WorkingSessionCache.Invalid()
                                return@submitTracked ProtectedNoteSessionLoad.Absent
                            }
                            requireRegular(body)
                            val metadataBytes=readMetadata(File(directory,"session.json"),check)
                            val metadata=ProtectedNoteMetadata.parse(metadataBytes)
                            check(temporary.isDirectory || temporary.mkdirs())
                            val snapshot=BundleSnapshot.take(body,temporary) {check();false}
                            raw.set(snapshot);check()
                            Capture(metadata,hash(metadataBytes),snapshot.sourceHash)
                        } ?: throw CancellationException("Session owner retired")
                        accepted=operation
                        val capture=when(val outcome=operation.awaitSettled()) {
                            is LegacySessionQueue.Outcome.Success->outcome.value
                            is LegacySessionQueue.Outcome.Failure->throw outcome.error
                        }
                        check()
                        if(capture is ProtectedNoteSessionLoad) {
                            if(hint!=null && capture===ProtectedNoteSessionLoad.CanvasAvailable) ProtectedNoteSessionLoad.Mismatched else capture
                        } else {
                            capture as Capture
                            val body=PreparedProtectedNote.read(requireNotNull(raw.getAndSet(null)),resources,codec,pdfDirectory,assets,check)
                            val value=PreparedNoteSession(body,capture.metadata,directory,owner,access,capture.bodyHash,capture.metadataHash)
                            prepared.set(value)
                            val document=body.document
                            if(hint!=null && (hint.kind!=DocumentKind.NOTE || hint.documentId!=document.identity.documentId || hint.path!=capture.metadata.path))
                                ProtectedNoteSessionLoad.Mismatched
                            else {
                                document.path=capture.metadata.path;document.displayName=capture.metadata.displayName;document.dirty=capture.metadata.dirty
                                ProtectedNoteSessionLoad.Ready(value)
                            }
                        }
                    }
                }
            }
            cleanup(result !is ProtectedNoteSessionLoad.Ready)
            check()
            if(result is ProtectedNoteSessionLoad.Ready) check(prepared.compareAndSet(result.prepared,null))
            return result
        } catch(error:CancellationException) {throw error}
        catch(error:Exception) {return ProtectedNoteSessionLoad.Invalid(error)}
        finally {if(prepared.get()!=null || raw.get()!=null || accepted?.result?.isCompleted==false) cleanup(true)}
    }
}

private fun exists(file:File):Boolean=try {
    Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS);true
} catch(_:NoSuchFileException) {false}

private fun requireRegular(file:File) {
    require(Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).isRegularFile)
}
private fun readMetadata(file:File,checkActive:()->Unit):ByteArray {
    requireRegular(file)
    require(Files.size(file.toPath())<=ProtectedNoteMetadata.MAX_BYTES)
    return Files.newInputStream(file.toPath(),READ,NOFOLLOW_LINKS).use {input->
        val output=java.io.ByteArrayOutputStream()
        val buffer=ByteArray(4096)
        while(true) {
            checkActive()
            val count=input.read(buffer)
            if(count<0) break
            require(count<=ProtectedNoteMetadata.MAX_BYTES-output.size())
            output.write(buffer,0,count)
        }
        checkActive();output.toByteArray()
    }
}
private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
