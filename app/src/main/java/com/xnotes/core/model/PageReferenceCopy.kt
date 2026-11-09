package com.xnotes.core.model

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Runtime lineage, shared by snapshots and retired pages. No object identity or geometry lives here. */
internal class PageReferenceScope private constructor(
    val documentId:String,
    val parent:PageReferenceScope?,
    private val depth:Int,
) {
    fun child(documentId:String):PageReferenceScope {
        ReferenceIdentity.requireUuid(documentId)
        var cursor:PageReferenceScope?=this
        while(cursor!=null) {
            require(cursor.documentId!=documentId) {"Copy target is already in this lineage"}
            cursor=cursor.parent
        }
        require(depth<MAX_DEPTH) {"Page copy lineage limit exceeded"}
        return PageReferenceScope(documentId,this,depth+1)
    }

    fun contains(scope:PageReferenceScope):Boolean {
        var cursor:PageReferenceScope?=this
        while(cursor!=null) {if(cursor===scope) return true;cursor=cursor.parent}
        return false
    }

    /** Deterministic for an explicitly identified ancestor, never inferred from content or page order. */
    fun translate(id:String,from:PageReferenceScope):String {
        ReferenceIdentity.requireUuid(id)
        val steps=ArrayList<PageReferenceScope>()
        var cursor:PageReferenceScope?=this
        while(cursor!==from) {
            require(cursor!=null) {"Page belongs to another copy lineage"}
            steps.add(cursor);cursor=cursor.parent
        }
        var result=id
        for(step in steps.asReversed()) {
            result=uuidV5(UUID.fromString(step.documentId),"xnotes-page-copy-v1:${step.parent!!.documentId}:$result")
        }
        return result
    }

    companion object {
        // Bounds both runtime work and the lineage descriptor that session persistence will carry.
        private const val MAX_DEPTH=1024
        fun root(documentId:String):PageReferenceScope {
            ReferenceIdentity.requireUuid(documentId)
            return PageReferenceScope(documentId,null,0)
        }
        /** RFC 9562 §5.5; independent implementation, checked against Appendix A.4's test vector. */
        internal fun uuidV5(namespace:UUID,name:String):String {
            val hash=MessageDigest.getInstance("SHA-1")
            hash.update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array())
            val bytes=hash.digest(name.toByteArray(Charsets.UTF_8))
            bytes[6]=((bytes[6].toInt() and 0x0f) or 0x50).toByte()
            bytes[8]=((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val buffer=ByteBuffer.wrap(bytes)
            return UUID(buffer.long,buffer.long).toString()
        }
    }
}

/** A single volatile publication keeps the reference container and its provenance together. */
internal data class PageReferenceBinding(val identity:ReferenceIdentity,val scope:PageReferenceScope?)

private data class ReferenceUpdate(val page:Page,val binding:PageReferenceBinding,val initialId:String?) {
    fun apply() {
        initialId?.let(binding.identity::restore)
        page.referenceBinding=binding
    }
}

/** Calculate the entire change before publishing any identity or binding. Called only by the model owner. */
private fun referenceUpdates(
    pages:List<Page>,
    target:PageReferenceScope,
    allowMissing:Boolean,
    unscoped:PageReferenceScope?,
    sourceLimit:PageReferenceScope=target,
):List<ReferenceUpdate> {
    require(pages.isNotEmpty() && pages.size<=ReferenceAnchors.MAX_TARGETS) {"Invalid referenced page count"}
    val members=java.util.IdentityHashMap<Page,Boolean>()
    val containers=java.util.IdentityHashMap<ReferenceIdentity,Boolean>()
    val oldIds=hashSetOf<String>()
    val nextIds=hashSetOf<String>()
    val changes=ArrayList<ReferenceUpdate>(pages.size)
    for(page in pages) {
        require(members.put(page,true)==null) {"Duplicate page membership"}
        val before=page.referenceBinding
        require(containers.put(before.identity,true)==null) {"Duplicate page reference container"}
        val old=before.identity.id
        if(old!=null) {ReferenceIdentity.requireUuid(old);require(oldIds.add(old)) {"Duplicate page reference identity"}}
        val from=before.scope ?: if(old==null) target else requireNotNull(unscoped) {"Page identity has no copy provenance"}
        require(sourceLimit.contains(from) || (old==null && from===target)) {"Page belongs to another copy lineage"}
        val next=if(old==null) {
            require(allowMissing) {"Missing page reference identity"}
            UUID.randomUUID().toString()
        } else target.translate(old,from)
        require(nextIds.add(next)) {"Page reference collision"}
        val identity=if(old==null || old==next) before.identity else ReferenceIdentity().also {it.restore(next)}
        changes.add(ReferenceUpdate(page,PageReferenceBinding(identity,target),if(old==null) next else null))
    }
    // A copy must not reuse another page's original ID, even across different source scopes.
    for(change in changes) {
        val old=change.page.referenceIdentity.id
        val next=change.initialId ?: change.binding.identity.id!!
        require(next==old || next !in oldIds) {"Copied page identity overlaps its source"}
    }
    return changes
}

/** Bind a fully validated read without minting IDs or changing an established identity container. */
internal fun Document.bindLoadedReferenceScope() {
    check(hasReferenceAnchors)
    val scope=PageReferenceScope.root(identity.documentId)
    val updates=referenceUpdates(pages,scope,false,scope)
    updates.forEach {it.apply()}
    pageReferenceScope=scope
}

/** Prepare IDs on the owner before snapshot capture, including ancestor pages put back by undo. */
internal fun Document.prepareScopedReferenceAnchors(scope:PageReferenceScope=pageReferenceScope ?: PageReferenceScope.root(identity.documentId)) {
    if(scope.parent!=null) check(identity.documentId==scope.documentId) {"Document identity differs from its copy scope"}
    val updates=referenceUpdates(pages,scope,true,if(scope.parent==null) scope else null)
    updates.forEach {it.apply()}
    pageReferenceScope=scope
}

/**
 * Two-phase model copy. [project] only consumes writer snapshots from this owner. Call [adopt]
 * on the owner thread only after the destination has been verified and durably published.
 * This API does not publish a file, switch a URI, change dirty state, or add a history command.
 */
internal class PageReferenceCopy private constructor(
    private val owner:Document,
    private val source:PageReferenceScope,
    private val sourceIdentity:com.xnotes.format.BundleIdentity,
    private val anchored:Boolean,
    val scope:PageReferenceScope,
) {
    private val ownerToken=owner.snapshotOwnerToken

    fun project(snapshot:Document):Document {
        require(snapshot.isWriteSnapshot && snapshot.snapshotOwnerToken===ownerToken) {"Copy requires this owner's writer snapshot"}
        require(snapshot.hasReferenceAnchors==anchored) {"Reference mode changed during copy"}
        val from=requireNotNull(snapshot.pageReferenceScope) {"Snapshot has no copy scope"}
        require(snapshot.identity.documentId==from.documentId && scope.contains(from)) {"Snapshot belongs to another copy scope"}
        if(anchored) require(snapshot.pages.all {it.referenceBinding.scope===from}) {"Snapshot contains pages outside its frozen scope"}
        val projected=snapshot.copyWriteSnapshot()
        if(anchored) {
            val updates=referenceUpdates(projected.pages,scope,false,null)
            updates.forEach {it.apply()}
        }
        projected.identity=snapshot.identity.copy(documentId=scope.documentId)
        projected.pageReferenceScope=scope
        return projected
    }

    fun adopt(document:Document) {
        require(document===owner && !document.isWriteSnapshot) {"Copy adoption requires the original owner"}
        if(document.pageReferenceScope===scope && document.identity==sourceIdentity.copy(documentId=scope.documentId)) {
            require(document.hasReferenceAnchors==anchored) {"Reference mode changed during copy"}
            if(anchored) document.prepareScopedReferenceAnchors()
            return
        }
        require(document.identity==sourceIdentity && document.pageReferenceScope===source && document.hasReferenceAnchors==anchored) {"Copy context is stale"}
        val updates=if(anchored) referenceUpdates(document.pages,scope,true,null,source) else emptyList()
        updates.forEach {it.apply()}
        document.identity=sourceIdentity.copy(documentId=scope.documentId)
        document.pageReferenceScope=scope
    }

    companion object {
        fun prepare(document:Document,targetDocumentId:String=UUID.randomUUID().toString()):PageReferenceCopy {
            require(!document.isWriteSnapshot) {"Copy context must be prepared by the model owner"}
            // Validate the target and the established scope before preparing any new page IDs.
            val source=document.pageReferenceScope ?: PageReferenceScope.root(document.identity.documentId)
            require(source.documentId==document.identity.documentId) {"Document identity differs from its copy scope"}
            val target=source.child(targetDocumentId)
            if(document.hasReferenceAnchors) document.prepareScopedReferenceAnchors(source)
            document.pageReferenceScope=source
            return PageReferenceCopy(document,source,document.identity,document.hasReferenceAnchors,target)
        }
    }
}
