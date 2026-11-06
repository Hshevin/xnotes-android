package com.xnotes.core.model

import com.xnotes.core.infinite.InfiniteDocument
import java.util.UUID

/** Identity is separate from geometry. Temporary drawing objects allocate no UUID until persisted. */
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

/** Both target count and uniqueness count occurrences, including aliases in different page lists. */
object ReferenceAnchors {
    const val MAX_TARGETS=200_000
    internal fun validateForWrite(doc:Document) {
        if(!doc.hasReferenceAnchors) return
        require(doc.pages.isNotEmpty()) {"Anchored note must contain a page"}
        val ids=hashSetOf<String>()
        fun accept(id:String?) {require(ids.add(requireNotNull(id) {"Snapshot is missing a reference identity"}));require(ids.size<=MAX_TARGETS)}
        for(page in doc.pages) {
            accept(page.referenceIdentity.id)
            for(item in page.items) {
                require(item is Stroke || item is ImageItem || item is AudioItem || item is TextItem || item is ShapeItem || item is ConnectorItem || item is TapeItem) {"Unsupported anchored item"}
                accept(page.referenceIdForWrite(item))
            }
        }
    }
    internal fun validateForWrite(doc:InfiniteDocument) {
        if(!doc.hasReferenceAnchors) return
        val ids=hashSetOf<String>()
        for(item in doc.items) {
            require(item is Stroke || item is ImageItem || item is AudioItem || item is ShapeItem || item is ConnectorItem || item is TapeItem) {"Unsupported anchored canvas item"}
            require(ids.add(requireNotNull(doc.referenceIdForWrite(item)) {"Snapshot is missing a reference identity"}));require(ids.size<=MAX_TARGETS)
        }
    }

    internal fun prepare(pages:List<Page>,assign:Boolean) {
        val seen=hashSetOf<String>()
        fun accept(identity:ReferenceIdentity) {
            val id=if(assign) identity.assign() else requireNotNull(identity.id) {"Missing reference identity"}
            require(seen.add(id)) {"Duplicate reference identity or membership"}
            require(seen.size<=MAX_TARGETS) {"Reference target limit exceeded"}
        }
        for(page in pages) {accept(page.referenceIdentity);for(item in page.items) accept(item.referenceIdentity)}
    }
    internal fun prepareItems(items:List<CanvasItem>,assign:Boolean) {
        val seen=hashSetOf<String>()
        for(item in items) {
            val id=if(assign) item.referenceIdentity.assign() else requireNotNull(item.referenceIdentity.id) {"Missing reference identity"}
            require(seen.add(id)) {"Duplicate reference identity or membership"}
            require(seen.size<=MAX_TARGETS) {"Reference target limit exceeded"}
        }
    }
}

/** Only for a genuinely new model. Upgrading an existing file must first use the lossless bundle path. */
internal fun Document.enableReferenceAnchors() {ReferenceAnchors.prepare(pages,true);hasReferenceAnchors=true}
internal fun InfiniteDocument.enableReferenceAnchors() {ReferenceAnchors.prepareItems(items,true);hasReferenceAnchors=true}

/** Called on the model owner before publishing an asynchronous save snapshot, never by an IO writer. */
internal fun Document.prepareReferenceAnchors() {if(hasReferenceAnchors) ReferenceAnchors.prepare(pages,true)}
internal fun InfiniteDocument.prepareReferenceAnchors() {if(hasReferenceAnchors) ReferenceAnchors.prepareItems(items,true)}
