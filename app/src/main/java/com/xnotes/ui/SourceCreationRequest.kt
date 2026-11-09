package com.xnotes.ui

import com.xnotes.mindmap.MapSourceOffer

/** Source-specific preparation; graph choice, import history and save retries share one flow. */
internal interface SourceCreationRequest {
    val id:String
    val pane:Editor
    val hasSavedPath:Boolean
    val dirty:Boolean
    val titleRes:Int
    val contextNoticeRes:Int? get()=null
    val canSaveInPlace:Boolean get()=false
    val canSaveCopy:Boolean get()=false
    val pendingSaveCopy:com.xnotes.storage.NativeCreationPending? get()=null
    suspend fun saveCopyTo(uri:String,name:String?,stillCurrent:()->Boolean):SourceCopyResult=SourceCopyResult.Failed
    suspend fun continueSaveCopy(stillCurrent:()->Boolean):SourceCopyResult=SourceCopyResult.Failed
    fun current():Boolean
    suspend fun prepare():PageReferencePreparation
    suspend fun saveInPlace():PageReferencePreparation=PageReferencePreparation.Failed
    suspend fun enablePages(offer:MapSourceOffer):PageReferenceEnableResult=PageReferenceEnableResult.Failed
    suspend fun continueEnablePages():PageReferenceEnableResult=PageReferenceEnableResult.Failed
    suspend fun reopen():Boolean=false
    fun clear()
}
