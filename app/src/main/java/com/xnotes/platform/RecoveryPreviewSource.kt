package com.xnotes.platform

import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.format.JsonPull
import com.xnotes.storage.PdfResources
import com.xnotes.storage.PreparedRecoveryPreview
import com.xnotes.storage.StorageMsg
import com.xnotes.storage.StorageText
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class RecoveryLegacyArtifact(val kind:DocumentKind,internal val filename:String,internal val metadata:String) {
    NOTE(DocumentKind.NOTE,"document.xnote","session.json"),
    CANVAS(DocumentKind.CANVAS,"canvas.xcanvas","canvas-session.json"),
}
internal sealed interface RecoveryPreviewLoad {
    data class Absent(val legacy:Set<RecoveryLegacyArtifact>):RecoveryPreviewLoad
    data object Cleared:RecoveryPreviewLoad
    data object SourceRecoveryRequired:RecoveryPreviewLoad
    data class Invalid(val message:String,val cause:Exception):RecoveryPreviewLoad
    class Ready(val prepared:PreparedRecoveryPreview):RecoveryPreviewLoad
}

/** Read this physical slot only. Neither a legacy path nor a cached body can grant source writes. */
internal class RecoveryPreviewSource(
    private val directory:File,private val owner:LegacySessionQueue.Owner,private val native:NativeWorkingSession,
    private val resources:PdfResources,private val noteCodec:DocumentCodec,private val canvasCodec:CanvasCodec,
    private val pdfDir:File,private val assetRoot:File,private val temporary:File,
) {
    private sealed interface Capture {
        class Choices(val artifacts:Set<RecoveryLegacyArtifact>):Capture
        class Body(val artifact:RecoveryLegacyArtifact,val name:String?):Capture
    }
    suspend fun prepare(legacy:RecoveryLegacyArtifact?=null,checkActive:()->Unit={}):RecoveryPreviewLoad {
        val caller=currentCoroutineContext()
        val raw=AtomicReference<BundleSnapshot?>()
        val read=AtomicReference<NativeWorkingSession.Read?>()
        val prepared=AtomicReference<PreparedRecoveryPreview?>()
        var accepted:Deferred<LegacySessionQueue.Outcome<Capture>>?=null
        val check={
            caller.ensureActive();checkActive()
            if(!owner.isCurrent()) throw CancellationException("Recovery preview owner retired")
        }
        var primary:Throwable?=null
        suspend fun cleanup(includePrepared:Boolean) {
            withContext(NonCancellable+Dispatchers.IO) {
                accepted?.await()
                var failure:Throwable?=null
                fun release(action:()->Unit) {try {action()} catch(error:Throwable) {if(failure==null) failure=error else failure!!.addSuppressed(error)}}
                if(includePrepared) prepared.getAndSet(null)?.let {release {it.close()}}
                raw.getAndSet(null)?.let {release {it.close()}}
                read.getAndSet(null)?.let {release {it.close()}}
                failure?.let {throw it}
            }
        }
        try {
            val result=withContext(Dispatchers.IO) {
                check()
                when(val state=native.loadState(check)) {
                    NativeWorkingSession.LoadState.Cleared->{
                        if(legacy!=null) throw WorkingSessionCache.Conflict()
                        RecoveryPreviewLoad.Cleared
                    }
                    is NativeWorkingSession.LoadState.Active->{
                        read.set(state.read);check()
                        if(legacy!=null) throw WorkingSessionCache.Conflict()
                        if(state.read.metadata.source!=null || state.read.metadata.handoff!=null) RecoveryPreviewLoad.SourceRecoveryRequired
                        else {
                            // The factory takes this read on both success and failure.
                            val held=requireNotNull(read.getAndSet(null))
                            val ready=PreparedRecoveryPreview.readWorking(native,held,resources,noteCodec,canvasCodec,pdfDir,assetRoot,check)
                            prepared.set(ready);RecoveryPreviewLoad.Ready(ready)
                        }
                    }
                    NativeWorkingSession.LoadState.Absent->{
                        // An accepted FIFO command outlives a cancelled awaiter. Its snapshot lives
                        // in raw until the IO factory takes it, not solely in a Deferred's result.
                        val operation=owner.submit<Capture> {
                            check();requireAbsent(check)
                            if(legacy==null) Capture.Choices(artifacts())
                            else {
                                val file=File(directory,legacy.filename)
                                if(!Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).isRegularFile)
                                    throw WorkingSessionCache.Invalid()
                                check(temporary.isDirectory || temporary.mkdirs()) {"Cannot create recovery snapshot directory"}
                                val snapshot=BundleSnapshot.take(file,temporary) {check();false}
                                raw.set(snapshot)
                                requireAbsent(check);check()
                                Capture.Body(legacy,readDisplayName(legacy,check))
                            }
                        } ?: throw CancellationException("Recovery preview owner retired")
                        accepted=operation
                        val capture=when(val outcome=operation.await()) {
                            is LegacySessionQueue.Outcome.Failure->throw outcome.error
                            is LegacySessionQueue.Outcome.Success->outcome.value
                        }
                        check()
                        when(capture) {
                            is Capture.Choices->RecoveryPreviewLoad.Absent(capture.artifacts)
                            is Capture.Body->{
                                // No input wait, decode, renderer or picker holds the FIFO.
                                val snapshot=requireNotNull(raw.getAndSet(null))
                                val ready=PreparedRecoveryPreview.readLegacy(snapshot,capture.artifact.kind,resources,
                                    noteCodec,canvasCodec,pdfDir,assetRoot,capture.name,check)
                                prepared.set(ready);RecoveryPreviewLoad.Ready(ready)
                            }
                        }
                    }
                }
            }
            // Complete every suspending cleanup BEFORE transferring a ready body. Otherwise a
            // prompt cancellation while returning from finally could lose an already transferred body.
            cleanup(false)
            check()
            if(result is RecoveryPreviewLoad.Ready) check(prepared.compareAndSet(result.prepared,null))
            return result
        } catch(error:CancellationException) {primary=error;throw error}
        catch(error:Exception) {primary=error;return RecoveryPreviewLoad.Invalid(StorageText.t(StorageMsg.MIGRATION_RECOVERY_INVALID),error)}
        finally {
            // No suspension remains after successful transfer. On cancellation the independently
            // accepted FIFO may still own work, so await it before looking for newly produced files.
            if(prepared.get()!=null || raw.get()!=null || read.get()!=null || accepted?.isCompleted==false) try {
                // Never delete files while the independently accepted command can still produce them.
                cleanup(true)
            } catch(error:Throwable) {
                if(error is CancellationException || primary==null) throw error
                primary!!.addSuppressed(error)
            }
        }
    }
    private fun requireAbsent(checkActive:()->Unit) {
        when(val state=native.loadState(checkActive)) {
            NativeWorkingSession.LoadState.Absent->Unit
            NativeWorkingSession.LoadState.Cleared->throw WorkingSessionCache.Conflict()
            is NativeWorkingSession.LoadState.Active->{state.read.close();throw WorkingSessionCache.Conflict()}
        }
    }
    private fun artifacts():Set<RecoveryLegacyArtifact> = buildSet {
        for(artifact in RecoveryLegacyArtifact.entries) {
            try {
                // Even an invalid link/directory is present recovery material, not a missing slot.
                Files.readAttributes(File(directory,artifact.filename).toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
                add(artifact)
            } catch(_:NoSuchFileException) { }
        }
    }
    /** Optional display data, never a source binding or reason to discard a valid body. */
    private fun readDisplayName(artifact:RecoveryLegacyArtifact,checkActive:()->Unit):String? {
        val file=File(directory,artifact.metadata)
        val limit=64*1024
        try {
            checkActive()
            val attributes=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            if(!attributes.isRegularFile || attributes.size()>limit) return null
            val bytes=java.io.ByteArrayOutputStream()
            Files.newInputStream(file.toPath(),java.nio.file.StandardOpenOption.READ,NOFOLLOW_LINKS).use {input->
                val buffer=ByteArray(4096)
                while(bytes.size()<=limit) {
                    checkActive()
                    val count=input.read(buffer,0,minOf(buffer.size,limit+1-bytes.size()))
                    if(count<0) break
                    bytes.write(buffer,0,count)
                }
            }
            checkActive();if(bytes.size()>limit) return null
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            val parser=JsonPull(java.io.StringReader(text),strict=true,tokenLimit=limit)
            parser.beginObject();val seen=hashSetOf<String>();var name:String?=null
            val expected=if(artifact==RecoveryLegacyArtifact.NOTE) "displayName" else "display_name"
            while(parser.hasNext()) {
                checkActive();val key=parser.nextName();require(seen.add(key))
                if(key==expected && parser.peek()==JsonPull.Token.STRING) name=parser.nextString() else parser.skipValue()
            }
            parser.endObject();require(parser.peek()==JsonPull.Token.END_DOCUMENT);checkActive()
            return name?.takeIf {it.length<=4096}
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {return null}
    }
}
