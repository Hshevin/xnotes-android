package com.xnotes.ui

import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageReferenceScope
import com.xnotes.mindmap.MapSource
import com.xnotes.mindmap.MapSourceOffer
import com.xnotes.platform.LegacySessionQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A UI request for one actual page, never an ordinal or authority to publish a model. */
internal class PageReferenceRequest private constructor(
    private val issuer:Any,val pane:Editor,internal val owner:Editor,
    internal val model:Document,internal val page:Page,internal val documentId:String,
    internal val path:String?,internal val scope:PageReferenceScope?,internal val initialPageId:String?,
    internal val paneSession:LegacySessionQueue.Owner,internal val ownerSession:LegacySessionQueue.Owner,
) {
    val id:String=UUID.randomUUID().toString()
    internal val sourceId:String=UUID.randomUUID().toString()
    private val retired=AtomicBoolean()
    internal fun issuedBy(owner:Any)=issuer===owner
    internal fun retire() {retired.set(true)}
    internal fun active()=!retired.get() && paneSession.isCurrent() && ownerSession.isCurrent()
    companion object {
        internal fun capture(issuer:Any,pane:Editor,owner:Editor,model:Document,page:Page,path:String?,
                             paneSession:LegacySessionQueue.Owner,ownerSession:LegacySessionQueue.Owner)=
            PageReferenceRequest(issuer,pane,owner,model,page,model.identity.documentId,path,
                model.pageReferenceScope,page.referenceIdentity.id,paneSession,ownerSession)
    }
}

internal sealed interface PageReferencePreparation {
    data class Ready(val source:MapSource):PageReferencePreparation
    data object SaveRequired:PageReferencePreparation
    data class EnableRequired(val offer:MapSourceOffer):PageReferencePreparation
    data object ReopenRequired:PageReferencePreparation
    data object Changed:PageReferencePreparation
    data object Failed:PageReferencePreparation
}

internal sealed interface PageReferenceEnableResult {
    data object Enabled:PageReferenceEnableResult
    data object Retained:PageReferenceEnableResult
    data object Changed:PageReferenceEnableResult
    data object Failed:PageReferenceEnableResult
}
