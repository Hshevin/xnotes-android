package com.xnotes.storage

import com.xnotes.core.util.DocumentKind
import com.xnotes.platform.WorkingSessionCache
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

internal sealed interface RecoveryCopyResult {
    data class Saved(val uri:String,val name:String):RecoveryCopyResult
    data class Retained(val pending:NativeCreationPending,val message:String):RecoveryCopyResult
    data class Failed(val message:String,val cause:Exception):RecoveryCopyResult
}

/** One explicit copy action. The caller owns the preview until this request's IO has finished. */
internal class RecoveryCopyRequest private constructor(
    private val storage:NotebookStorage,private val prepared:PreparedRecoveryPreview,
    private val intent:NativeCreationIntent,private val tempDir:File,
    val kind:DocumentKind,val displayName:String?,
) {
    private var claimed=false
    @Synchronized private fun claim():Boolean {
        if(claimed) return false
        claimed=true;return true
    }

    /** IO-only. Writes the held raw body, never the displayed model or its historical source path. */
    fun publish(uri:String,name:String,checkActive:()->Unit={}):RecoveryCopyResult {
        if(!claim()) return failed(IllegalStateException("Recovery copy request was already used"))
        var candidate:File?=null
        try {
            prepared.ensureOpen();checkActive()
            val permit=storage.prepareNewTarget(intent,uri,checkActive)
            val copy=prepared.prepareCopy(intent.documentId,tempDir,checkActive)
            candidate=copy.file
            prepared.ensureOpen();checkActive()
            storage.publishCreated(uri,copy.file,permit,name,checkActive)
            return RecoveryCopyResult.Saved(uri,name)
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(error:Exception) {
            val pending=try {storage.retainedCreation(intent,uri,name,checkActive)}
                catch(cancelled:CancellationException) {throw cancelled}
                catch(recognition:Exception) {if(recognition!==error) error.addSuppressed(recognition);null}
            return if(pending!=null) RecoveryCopyResult.Retained(pending,message(error)) else failed(error)
        } finally {candidate?.delete()}
    }

    companion object {
        /** Main-only, no IO. Fix this request before handing control to a destination picker. */
        fun create(storage:NotebookStorage,prepared:PreparedRecoveryPreview,tempDir:File):RecoveryCopyRequest {
            prepared.ensureOpen()
            return RecoveryCopyRequest(storage,prepared,storage.beginCreation(prepared.kind),tempDir,prepared.kind,prepared.displayName)
        }

        /** IO-only runtime retry. An exception cannot manufacture a replacement pending token. */
        fun complete(storage:NotebookStorage,pending:NativeCreationPending,checkActive:()->Unit={}):RecoveryCopyResult = try {
            checkActive();storage.completeCreation(pending,checkActive)
            RecoveryCopyResult.Saved(pending.uri,pending.name)
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(error:Exception) {failed(error)}

        /** IO-only, after the user explicitly selects the target of a creation interrupted earlier. */
        fun completeSelected(storage:NotebookStorage,uri:String,name:String,checkActive:()->Unit={}):RecoveryCopyResult = try {
            checkActive();storage.completeCreation(uri,name,checkActive)
            RecoveryCopyResult.Saved(uri,name)
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(error:Exception) {failed(error)}

        private fun failed(error:Exception)=RecoveryCopyResult.Failed(message(error),error)
        private fun message(error:Exception):String=StorageText.t(when(error) {
            is ReferenceContractViolation->error.reason
            is MigrationPublication.RecoveryRequired->error.reason ?: StorageMsg.MIGRATION_RECOVERY_INVALID
            is WorkingSessionCache.Invalid->StorageMsg.MIGRATION_RECOVERY_INVALID
            is PdfResources.Unavailable->StorageMsg.PDF_RESOURCE_CORRUPT
            is SecurityException,is IOException->StorageMsg.NOTE_NOT_WRITABLE
            else->StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID
        })
    }
}
