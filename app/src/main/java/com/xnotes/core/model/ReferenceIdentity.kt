package com.xnotes.core.model

import java.util.UUID

/** Identity is separate from geometry. Only pages receive a persistent reference UUID. */
class ReferenceIdentity {
    @Volatile private var value:String?=null
    val id:String? get()=value

    @Synchronized internal fun assign():String = value ?: UUID.randomUUID().toString().also {value=it}
    /** Restore once before exposing the model; a live identity can never be replaced or cleared. */
    @Synchronized internal fun restore(id:String) {
        requireUuid(id)
        check(value==null || value==id) {"Cannot replace an established reference identity"}
        value=id
    }
    companion object {
        internal fun requireUuid(id:String) {require(UUID.fromString(id).toString()==id) {"Invalid reference UUID"}}
    }
}

/** Count and validate pages only; objects and canvases have no reference identities. */
object ReferenceAnchors {
    const val MAX_TARGETS=200_000
    internal fun validateForWrite(doc:Document) {
        doc.pageReferenceScope?.takeIf {it.parent!=null}?.let {scope->
            check(doc.identity.documentId==scope.documentId) {"Document identity differs from its copy scope"}
            if(doc.hasReferenceAnchors) require(doc.pages.all {it.referenceBinding.scope===scope}) {"Pages must be prepared before writing a copied document"}
        }
        if(!doc.hasReferenceAnchors) return
        require(doc.pages.isNotEmpty()) {"Referenced note must contain a page"}
        validatePages(doc.pages)
    }
    private fun validatePages(pages:List<Page>) {
        val seen=hashSetOf<String>()
        for(page in pages) {
            val id=requireNotNull(page.referenceIdentity.id) {"Missing page reference identity"}
            require(seen.add(id)) {"Duplicate page reference identity or membership"}
            require(seen.size<=MAX_TARGETS) {"Page reference limit exceeded"}
        }
    }
}

/** Existing files require a byte-preserving upgrade before enabling page identities. */
internal fun Document.enableReferenceAnchors() {prepareScopedReferenceAnchors();hasReferenceAnchors=true}

/** Model owner prepares new pages before freezing a save; the IO writer never assigns live IDs. */
internal fun Document.prepareReferenceAnchors() {
    pageReferenceScope?.takeIf {it.parent!=null}?.let {check(identity.documentId==it.documentId) {"Document identity differs from its copy scope"}}
    if(hasReferenceAnchors) prepareScopedReferenceAnchors()
    else if(pageReferenceScope==null) pageReferenceScope=PageReferenceScope.root(identity.documentId)
}
