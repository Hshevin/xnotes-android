package com.xnotes.mindmap

import java.util.Collections
import java.util.UUID
import kotlin.math.abs

/** Last-observed display values and fixed world coordinates, never source-reading or writing authority. */
internal data class MapFileCardSeed(val documentId:String,val fileName:String,val x:Double,val y:Double,
    val target:MapSourceTarget=MapSourceTarget(documentId,SourceScope.FILE)) {
    init {
        require(UUID.fromString(documentId).toString()==documentId)
        target.validateFileCard(documentId)
        require(fileName.length<=4096 && x.isFinite() && y.isFinite() && abs(x)<=MindMapDocument.MAX_COORD && abs(y)<=MindMapDocument.MAX_COORD)
    }
}
/** A file card names the whole file. The original reader starts at its catalog's permanent first-page anchor. */
internal fun MapSourceTarget.validateFileCard(fileId:String=documentId) {
    validate();require(documentId==fileId)
    require(scope==SourceScope.FILE || scope==SourceScope.PDF_ORIGINAL && originalPdf?.let {
        it.pageIndex==0 && it.viewTurns==0 && it.userRegion==null && region==null
    }==true)
}

/** Shared by model validation, relation evidence and import receipts. Cached display fields are irrelevant. */
internal fun MapNode.canonicalFileCardSource():MapSource? {
    val file=fileCardId ?: return null
    if(kind!=NodeKind.REFERENCE) return null
    val source=sources.singleOrNull() ?: return null
    return source.takeIf {runCatching {it.target.validateFileCard(file)}.isSuccess}
}

internal data class MapFileCardsAdded(val document:MindMapDocument,val nodeIds:Set<String>,val addedIds:Set<String>)

/** A whole batch is validated before it can enter an actor's history. Existing cards retain every field. */
internal fun MindMapDocument.addFileCards(seeds:List<MapFileCardSeed>):MapFileCardsAdded {
    require(seeds.size<=250)
    val existing=nodes.filter {it.fileCardId!=null}.associateBy {it.fileCardId}
    val selected=linkedSetOf<String>();val added=mutableListOf<MapNode>()
    val unique=linkedMapOf<String,MapFileCardSeed>()
    for(seed in seeds) {
        require(unique[seed.documentId]?.target?.let {it==seed.target}!=false)
        unique.putIfAbsent(seed.documentId,seed)
    }
    for(seed in unique.values) {
        val present=existing[seed.documentId]
        if(present!=null) {
            require(present.canonicalFileCardSource()?.target==seed.target)
            selected+=present.id;continue
        }
        val target=seed.target
        val source=MapSource(documentId=seed.documentId,fileName=seed.fileName,scope=target.scope,pageId=target.pageId,
            region=target.region,originalPdf=target.originalPdf)
        val node=MapNode(parent=null,free=true,kind=NodeKind.REFERENCE,text=seed.fileName,x=seed.x,y=seed.y,
            sources=listOf(source),primarySource=source.id,fileCardId=seed.documentId)
        added+=node;selected+=node.id
    }
    val next=if(added.isEmpty()) this else copy(nodes=nodes+added)
    next.validate()
    // Include the full encoded budget, not merely the model's per-field limits.
    if(added.isNotEmpty()) MapCodec.encode(next)
    return MapFileCardsAdded(next,Collections.unmodifiableSet(selected),Collections.unmodifiableSet(added.mapTo(linkedSetOf()) {it.id}))
}

internal class MapFileCardAppliedNode(val lifetime:Any,val documentId:String,val sourceId:String,val target:MapSourceTarget)
internal class MapFileCardsTicket internal constructor(internal val issuer:Any,internal val epoch:Long,
    internal val historyEpoch:Long,internal val documentId:String,seeds:List<MapFileCardSeed>) {
    internal val seeds:List<MapFileCardSeed> = Collections.unmodifiableList(seeds.toList())
    internal var appliedNodes:Map<String,MapFileCardAppliedNode>?=null
}
internal sealed interface MapFileCardsImportResult {
    data class Applied(val nodeIds:Set<String>,val alreadyPresent:Boolean):MapFileCardsImportResult
    data object Busy:MapFileCardsImportResult
    data object Changed:MapFileCardsImportResult
    data object Invalid:MapFileCardsImportResult
}
