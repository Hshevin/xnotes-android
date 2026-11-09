package com.xnotes.mindmap

/** An observation may change only cached source fields, never a user's reference or node body. */
internal data class MapSourceDerivedPatch(
    val nodeId:String,val sourceId:String,val target:MapSourceTarget,val status:SourceStatus,
    val fileName:String?,val observedVersion:String?,val lastKnownPage:Int?,
    val preview:MapLastPreview?=null,val asset:MapPreviewAsset?=null,
    val acceptBaseline:Boolean=false,val rawVersion:String?=null,val renderedVersion:String?=null,
) {
    init {
        target.validate()
        require((preview==null)==(asset==null))
        preview?.let {require(it.target==target && it.assetKey==asset!!.key && it.renderedVersion==renderedVersion)}
        if(acceptBaseline) require((preview!=null || status==SourceStatus.EMPTY) && rawVersion!=null && renderedVersion!=null)
    }
}

/** Selection and viewport never enter content history. Each accepted gesture is one snapshot. */
class MapHistory(initial:MindMapDocument) {
    var document=initial;private set
    private val past=ArrayDeque<MindMapDocument>()
    private val future=ArrayDeque<MindMapDocument>()
    private val issuer=Any()
    private var generation=0L
    val canUndo get()=past.isNotEmpty()
    val canRedo get()=future.isNotEmpty()
    fun commit(next:MindMapDocument) {
        if(next==document) return
        next.validate();past.addLast(document)
        if(past.size>80) past.removeFirst()
        document=next;future.clear();generation++
    }
    fun undo() {if(canUndo) {future.addLast(document);document=past.removeLast();generation++}}
    fun redo() {if(canRedo) {past.addLast(document);document=future.removeLast();generation++}}

    internal class Capture internal constructor(internal val issuer:Any,internal val generation:Long,
        internal val current:MindMapDocument,internal val past:List<MindMapDocument>,internal val future:List<MindMapDocument>)
    internal class Prepared internal constructor(internal val capture:Capture,internal val current:MindMapDocument,
        internal val past:List<MindMapDocument>,internal val future:List<MindMapDocument>)
    /** Main only. Immutable frames can then be inspected without holding the live history. */
    internal fun captureDerived()=Capture(issuer,generation,document,past.toList(),future.toList())
    /** Main only. Any user edit/undo/redo while IO was preparing invalidates the whole adoption. */
    internal fun acceptDerived(prepared:Prepared):Boolean {
        if(prepared.capture.issuer!==issuer || prepared.capture.generation!=generation) return false
        document=prepared.current
        past.clear();past.addAll(prepared.past);future.clear();future.addAll(prepared.future)
        generation++;return true
    }
    companion object {
        /** IO only. Validate every affected frame's complete encoded budget before publishing any. */
        internal fun prepareDerived(capture:Capture,patches:List<MapSourceDerivedPatch>,checkActive:()->Unit={}):Prepared {
            fun frame(original:MindMapDocument):MindMapDocument {
                checkActive()
                val byNode=patches.groupBy {it.nodeId}
                val incoming=patches.mapNotNull {it.asset}.associateBy {it.key}
                val next=original.copy(nodes=original.nodes.map {node->
                    val edits=byNode[node.id].orEmpty().associateBy {it.sourceId}
                    if(edits.isEmpty()) node else node.copy(sources=node.sources.map {source->
                        val patch=edits[source.id]?.takeIf {it.target==source.target} ?: return@map source
                        source.copy(status=patch.status,fileName=patch.fileName ?: source.fileName,
                            observedVersion=patch.observedVersion ?: source.observedVersion,
                            lastKnownPage=if(source.scope !in setOf(SourceScope.PAGE,SourceScope.PDF_ORIGINAL)) null else patch.lastKnownPage ?: source.lastKnownPage,
                            sourceVersion=if(patch.acceptBaseline) requireNotNull(patch.rawVersion) else source.sourceVersion,
                            acceptedPreviewVersion=if(patch.acceptBaseline) requireNotNull(patch.renderedVersion)
                                else if(patch.preview!=null) null else source.acceptedPreviewVersion,
                            previewKey=patch.preview?.assetKey ?: source.previewKey,lastPreview=patch.preview ?: source.lastPreview)
                    })
                })
                val reachable=next.nodes.flatMap {it.sources}.mapNotNull {it.lastPreview?.assetKey}.toSet()
                val assets=(original.previewAssets+incoming).filterKeys {it in reachable}
                val complete=next.copy(previewAssets=assets)
                // Deliberately do not retain these bytes for any of the 80 historical frames.
                MapCodec.encode(complete);checkActive()
                return if(complete==original) original else complete
            }
            val current=frame(capture.current)
            val past=capture.past.map(::frame);val future=capture.future.map(::frame)
            checkActive();return Prepared(capture,current,past,future)
        }
    }
}
