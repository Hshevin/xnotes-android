package com.xnotes.storage

import com.xnotes.platform.NativeWorkingSession

/** Capability from an actual durable C+T read, never from JSON, a UUID or a callback's return value. */
internal class NativeHandoffAcknowledgement private constructor(
    private val staged:PreparedNativeOpen.SaveRequest.Receipt.Staged,
    private val native:NotebookStorage.StagedSave,
    private val publication:NativeWorkingSession.HandoffPublication,
) {
    internal fun matches(path:String,id:String,digest:String)=native.journalPath==path && native.handoffId==id && native.recordDigest==digest
    internal fun checkCurrent(checkActive:()->Unit)=publication.checkLive(staged,checkActive)
    internal fun checkStage(candidate:NotebookStorage.StagedSave) {
        if(native!==candidate) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    }
    companion object {
        internal fun verify(staged:PreparedNativeOpen.SaveRequest.Receipt.Staged,publication:NativeWorkingSession.HandoffPublication,
                            checkActive:()->Unit):NativeHandoffAcknowledgement {
            publication.verify(staged,checkActive)
            return NativeHandoffAcknowledgement(staged,staged.nativeWitness(),publication)
        }
    }
}
