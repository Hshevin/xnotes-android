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
        if(!doc.hasReferenceAnchors) return
        require(doc.pages.isNotEmpty()) {"Referenced note must contain a page"}
        prepare(doc.pages,false)
    }
    internal fun prepare(pages:List<Page>,assign:Boolean) {
        val seen=hashSetOf<String>()
        for(page in pages) {
            val id=if(assign) page.referenceIdentity.assign() else requireNotNull(page.referenceIdentity.id) {"Missing page reference identity"}
            require(seen.add(id)) {"Duplicate page reference identity or membership"}
            require(seen.size<=MAX_TARGETS) {"Page reference limit exceeded"}
        }
    }
}

/** Existing files require a byte-preserving upgrade before enabling page identities. */
internal fun Document.enableReferenceAnchors() {ReferenceAnchors.prepare(pages,true);hasReferenceAnchors=true}

/** Model owner prepares new pages before freezing a save; the IO writer never assigns live IDs. */
internal fun Document.prepareReferenceAnchors() {if(hasReferenceAnchors) ReferenceAnchors.prepare(pages,true)}
