package com.xnotes.ui

import com.xnotes.R
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.BundleIdentity
import com.xnotes.platform.LegacySessionQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface SourceCopyResult {
    data object Adopted:SourceCopyResult
    data object Retained:SourceCopyResult
    data class WrittenNotAdopted(val uri:String,val documentId:String):SourceCopyResult
    data object Changed:SourceCopyResult
    data object Failed:SourceCopyResult
}

/** One fixed CREATE candidate; a continuation cannot recapture RAM or mint another identity. */
internal class CanvasRegionCopyAttempt(val uri:String,val name:String,val intent:com.xnotes.storage.NativeCreationIntent,
    val capturedRevision:Long,val identity:BundleIdentity) {
    var pending:com.xnotes.storage.NativeCreationPending?=null
    var published:com.xnotes.storage.NativeSourceVersion?=null
}

/** One fixed world rectangle on one actual Canvas owner; it never retains selected objects. */
internal class CanvasRegionReferenceRequest private constructor(
    private val issuer:Any,override val pane:Editor,internal val owner:Editor,internal val model:InfiniteDocument,
    internal val identity:BundleIdentity,internal val path:String?,val region:Rect,
    internal val paneSession:LegacySessionQueue.Owner,internal val ownerSession:LegacySessionQueue.Owner,
):SourceCreationRequest {
    override val id:String=UUID.randomUUID().toString()
    internal val sourceId:String=UUID.randomUUID().toString()
    private val retired=AtomicBoolean()
    override val hasSavedPath get()=path!=null
    override val dirty get()=model.dirty
    override val titleRes get()=R.string.graph_reference_this_region
    override val canSaveInPlace get()=pane.canSaveCanvasRegionReference(this)
    internal var copyAttempt:CanvasRegionCopyAttempt?=null
    override val canSaveCopy get()=copyAttempt==null && pane.canCopyCanvasRegionReference(this)
    override val pendingSaveCopy get()=copyAttempt?.pending
    override suspend fun saveCopyTo(uri:String,name:String?,stillCurrent:()->Boolean)=
        pane.copyCanvasRegionReference(this,uri,name,stillCurrent)
    override suspend fun continueSaveCopy(stillCurrent:()->Boolean)=pane.continueCanvasRegionCopy(this,stillCurrent)
    override fun current()=pane.canvasRegionReferenceCurrent(this)
    override suspend fun prepare()=pane.prepareCanvasRegionReference(this)
    override suspend fun saveInPlace()=pane.saveCanvasRegionReference(this)
    override fun clear()=pane.clearCanvasRegionReference(this)
    internal fun issuedBy(owner:Any)=issuer===owner
    internal fun active()=!retired.get() && paneSession.isCurrent() && ownerSession.isCurrent()
    internal fun retire() {retired.set(true)}
    companion object {
        internal fun capture(issuer:Any,pane:Editor,owner:Editor,model:InfiniteDocument,region:Rect,
            paneSession:LegacySessionQueue.Owner,ownerSession:LegacySessionQueue.Owner)=
            CanvasRegionReferenceRequest(issuer,pane,owner,model,model.identity,model.path,region,paneSession,ownerSession)
    }
}
