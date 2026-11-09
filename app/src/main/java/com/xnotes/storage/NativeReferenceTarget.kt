package com.xnotes.storage

import com.xnotes.core.model.Document
import com.xnotes.core.model.ReferenceIdentity
import com.xnotes.core.util.DocumentKind

/** A read-only permanent NOTE target. Neither an ordinal nor authority to save or migrate. */
internal data class NativeReferenceTarget(val documentId:String,val pageId:String?=null) {
    init {ReferenceIdentity.requireUuid(documentId);pageId?.let(ReferenceIdentity::requireUuid)}
    fun verify(catalog:NativeSourceSnapshot.ReferenceCatalog) {
        if(catalog.kind!=DocumentKind.NOTE) throw NativeReferenceFailure(NativeReferenceIssue.UNSUPPORTED)
        if(catalog.documentId!=documentId) throw NativeReferenceFailure(NativeReferenceIssue.CHANGED)
        pageId?.let {id->
            val pages=catalog.pages as? NativeSourceSnapshot.ReferenceCatalog.Pages.Available
                ?: throw NativeReferenceFailure(NativeReferenceIssue.UNAVAILABLE)
            if(id !in pages.ids) throw NativeReferenceFailure(NativeReferenceIssue.DELETED)
        }
    }
    fun page(document:Document):com.xnotes.core.model.Page? {
        if(document.identity.documentId!=documentId) throw NativeReferenceFailure(NativeReferenceIssue.CHANGED)
        try {
            if(!document.hasReferenceAnchors && document.pages.any {it.referenceIdentity.id!=null}) throw IllegalArgumentException()
            com.xnotes.core.model.ReferenceAnchors.validateForWrite(document)
        } catch(error:RuntimeException) {
            throw NativeReferenceFailure(NativeReferenceIssue.UNAVAILABLE)
        }
        val id=pageId ?: return null
        if(!document.hasReferenceAnchors) throw NativeReferenceFailure(NativeReferenceIssue.UNAVAILABLE)
        val found=document.pages.filter {it.referenceIdentity.id==id}
        if(found.size>1) throw NativeReferenceFailure(NativeReferenceIssue.UNAVAILABLE)
        return found.singleOrNull() ?: throw NativeReferenceFailure(NativeReferenceIssue.DELETED)
    }
}
internal enum class NativeReferenceIssue { DELETED,UNAVAILABLE,DUPLICATE,BUSY,CHANGED,UNSUPPORTED }
internal class NativeReferenceFailure(val issue:NativeReferenceIssue):IllegalStateException()
