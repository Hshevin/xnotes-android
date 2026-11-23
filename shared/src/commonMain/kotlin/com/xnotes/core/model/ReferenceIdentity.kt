package com.xnotes.core.model

import com.xnotes.core.util.CoreLock
import com.xnotes.core.util.locked
import kotlin.concurrent.Volatile


/** Identity is separate from geometry. Only pages receive a persistent reference UUID. */
class ReferenceIdentity {
    private val lock = CoreLock()
    @Volatile private var value:String?=null
    val id:String? get()=value

    internal fun assign():String = lock.locked { value ?: com.xnotes.kio.Uuids.random().also {value=it} }
    /** Restore once before exposing the model; a live identity can never be replaced or cleared. */
    fun restore(id:String) = lock.locked {
        requireUuid(id)
        check(value==null || value==id) {"Cannot replace an established reference identity"}
        value=id
    }
    companion object {
        fun requireUuid(id:String) {require(com.xnotes.kio.Uuids.isValid(id) && id==id.lowercase()) {"Invalid reference UUID"}}
    }
}

/** Count and validate pages only; objects and canvases have no reference identities. */
object ReferenceAnchors {
    const val MAX_TARGETS=200_000
    fun validateForWrite(doc:Document) {
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
fun Document.enableReferenceAnchors() {prepareScopedReferenceAnchors();hasReferenceAnchors=true}

/** Model owner prepares new pages before freezing a save; the IO writer never assigns live IDs. */
fun Document.prepareReferenceAnchors() {
    pageReferenceScope?.takeIf {it.parent!=null}?.let {check(identity.documentId==it.documentId) {"Document identity differs from its copy scope"}}
    if(hasReferenceAnchors) prepareScopedReferenceAnchors()
    else bindRootReferenceScope()
}

/**
 * An unanchored model's null scope means its own root. Bind that same root before a
 * source observation captures it, so the first writer snapshot cannot replace the observed scope.
 */
fun Document.bindRootReferenceScope() {
    if(pageReferenceScope==null) pageReferenceScope=PageReferenceScope.root(identity.documentId)
}
