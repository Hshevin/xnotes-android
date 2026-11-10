package com.xnotes.ui

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.util.DocumentKind
import com.xnotes.storage.NativeReferenceIssue
import com.xnotes.storage.NativeReferenceTarget
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ReferenceRegionReveal { NONE,SHOWN,OUTSIDE }
internal sealed interface NativeReferencePreparation {
    data class Ready(val request:NativeReferenceRequest):NativeReferencePreparation
    data class Failed(val issue:NativeReferenceIssue):NativeReferencePreparation
}
internal sealed interface NativeReferenceResult {
    data class Opened(val pane:Editor,val region:ReferenceRegionReveal):NativeReferenceResult
    data class Failed(val issue:NativeReferenceIssue):NativeReferenceResult
}
/** A single navigation choice. No file holder, model writer or replaceable source target. */
internal class NativeReferenceRequest private constructor(private val issuer:Any,internal val workspace:Editor,
    val target:NativeReferenceTarget,val kind:DocumentKind,val region:Rect?,val uri:String?,internal val displayName:String?,val keptOwner:Editor?,
    guardOwners:List<Editor>,internal val model:Document?,internal val page:Page?,internal val canvasModel:InfiniteDocument?,
    private val valid:()->Boolean,private val active:()->Boolean,internal val pagedDestination:Editor?) {
    val id:String=UUID.randomUUID().toString()
    val guardOwners:List<Editor> = java.util.Collections.unmodifiableList(ArrayList(guardOwners))
    private val cancelled=AtomicBoolean()
    fun current()=!cancelled.get() && valid()
    fun cancel() {cancelled.set(true)}
    internal fun ioCurrent()=!cancelled.get() && active()
    internal fun issuedBy(owner:Any)=issuer===owner
    companion object {
        internal fun observe(issuer:Any,workspace:Editor,target:NativeReferenceTarget,kind:DocumentKind,region:Rect?,uri:String?,name:String?,owner:Editor?,
            guards:List<Editor>,model:Document?,page:Page?,canvasModel:InfiniteDocument?,valid:()->Boolean,active:()->Boolean,
            pagedDestination:Editor?=null)=
            NativeReferenceRequest(issuer,workspace,target,kind,region,uri,name,owner,guards,model,page,canvasModel,valid,active,pagedDestination)
    }
}
