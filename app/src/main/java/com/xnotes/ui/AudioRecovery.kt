package com.xnotes.ui

import android.content.Context
import com.xnotes.core.model.AudioItem
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.RecordingBodyEvidence
import com.xnotes.storage.NativeSourceVersion
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A runtime adoption is fixed before a delayed or background audio request begins. */
class RecordingRecoveryTarget internal constructor(
    internal val owner:RecordOwner,
    internal val model:Any,
    internal val ownerToken:Any,
    internal val kind:DocumentKind,
    internal val documentId:String,
    internal val keys:Set<String>,
    internal val directory:File,
    internal val title:String,
    internal val isCurrent:()->Boolean,
    internal val recordings:(Int)->List<AudioItem>,
    internal val holds:(AudioItem)->Boolean,
) {
    internal fun withKeys(keys:Set<String>)=RecordingRecoveryTarget(owner,model,ownerToken,kind,documentId,keys,directory,title,isCurrent,recordings,holds)
}

/** Audio-only recovery; it grants no source-file write or model adoption permission. */
internal object AudioRecovery {
    internal var store:RecordingRecoveryStore?=null
        private set
    fun init(context:Context) {
        val directory=File(context.filesDir,"recordings").apply {mkdirs()}
        if(store?.directory?.canonicalPath!=directory.canonicalPath) store=RecordingRecoveryStore(directory)
    }
    private fun changed() {android.os.Handler(android.os.Looper.getMainLooper()).post {refresh()}}
    fun durableBodyRead(evidence:RecordingBodyEvidence) {if(store?.committed(evidence)?.let {it>0}==true) changed()}
    fun workingBodyCommitted(ownerToken:Any,evidence:RecordingBodyEvidence) {if(store?.committed(evidence,ownerToken)?.let {it>0}==true) changed()}
    fun needsBodyEvidence(ownerToken:Any):Boolean=store?.needsBodyEvidence(ownerToken)==true
    fun needsDurableEvidence(documentId:String):Boolean=store?.needsDurableEvidence(documentId)==true
    class Published private constructor(internal val ownerToken:Any,internal val evidence:RecordingBodyEvidence,
        internal val records:Map<String,RecordingRecoveryStore.Record>,internal val issuedBy:RecordingRecoveryStore?) {
        internal var consumed=false
        companion object {
            internal fun issue(ownerToken:Any,evidence:RecordingBodyEvidence,store:RecordingRecoveryStore?)=
                Published(ownerToken,evidence,store?.matches(ownerToken,evidence).orEmpty(),store)
        }
    }
    fun published(ownerToken:Any,evidence:RecordingBodyEvidence,version:NativeSourceVersion):Published? {
        if(evidence.kind!=version.kind || evidence.documentId!=version.documentId || evidence.bodySha256!=version.sha256) return null
        return Published.issue(ownerToken,evidence,store)
    }
    fun bodyCommitted(publication:Published) {
        if(publication.consumed || publication.issuedBy!==store) return
        publication.consumed=true
        store?.committed(publication.evidence,publication.ownerToken)
        changed()
    }
    fun ackCreated(ownerToken:Any,oldId:String,newId:String,publication:Published) {
        if(publication.consumed || publication.issuedBy!==store || publication.ownerToken!==ownerToken ||
            publication.evidence.documentId!=newId || oldId==newId) return
        publication.consumed=true
        store?.createdCommitted(ownerToken,oldId,newId,publication.evidence,publication.records)
        changed()
    }

    class Dialog(val owner:RecordOwner,val target:RecordingRecoveryTarget?,val candidates:List<RecordingRecoveryStore.Candidate>) {
        internal val token=Any()
    }
    var dialog by mutableStateOf<Dialog?>(null)
        private set
    private var recoveryBusy by mutableStateOf(false)
    private var currentExport by mutableStateOf<Export?>(null)
    var busy:Boolean
        get()=recoveryBusy || currentExport!=null
        internal set(value) {recoveryBusy=value}
    class Export internal constructor(internal val dialogToken:Any,internal val candidate:RecordingRecoveryStore.Candidate) {
        internal var started=false
    }
    @Synchronized fun beginExport(dialog:Dialog,candidate:RecordingRecoveryStore.Candidate):Export? {
        if(this.dialog!==dialog || busy) return null
        return Export(dialog.token,candidate).also {currentExport=it}
    }
    @Synchronized fun startExport(export:Export):Boolean {
        if(currentExport!==export || dialog?.token!==export.dialogToken) return false
        export.started=true;return true
    }
    @Synchronized fun finishExport(export:Export) {if(currentExport===export) currentExport=null}
    internal suspend fun exportBytes(export:Export,copy:()->Unit):Boolean = try {
        withContext(NonCancellable+Dispatchers.IO) {runCatching(copy).isSuccess}
    } finally {finishExport(export)}
    @Synchronized fun detach(dialog:Dialog) {
        if(this.dialog===dialog) this.dialog=null
        currentExport?.takeIf {it.dialogToken===dialog.token && !it.started}?.let {currentExport=null}
    }
    fun candidates():List<RecordingRecoveryStore.Candidate> = store?.list().orEmpty().filter {candidate->
        candidate.raw?.let {AudioRecording.recoveryFileProtected(it)}!=true
    }
    fun open(owner:RecordOwner) {dialog=Dialog(owner,owner.recordingRecoveryTarget(),candidates())}
    fun refresh() {dialog?.let {dialog=Dialog(it.owner,it.target,candidates())}}
    fun close() {if(!busy) dialog=null}
    fun discard(candidate:RecordingRecoveryStore.Candidate) {store?.discard(candidate);refresh()}
}
