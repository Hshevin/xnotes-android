package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import com.xnotes.ui.theme.FileNetworkCardHeight
import com.xnotes.ui.theme.FileNetworkCardWidth
import com.xnotes.ui.theme.FileNetworkColumnStep
import com.xnotes.ui.theme.FileNetworkRowStep
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal sealed interface NetworkSceneNodeKey {
    data class File(val documentId:String):NetworkSceneNodeKey
    data class Concept(val key:NetworkConceptKey):NetworkSceneNodeKey
}
internal sealed interface NetworkSceneEdgeKey {
    data class References(val concept:NetworkConceptKey,val fileId:String):NetworkSceneEdgeKey
    data class Belongs(val concept:NetworkConceptKey):NetworkSceneEdgeKey
}
/** Strings only: the retained geometry never owns a Context or a resource-dependent callback. */
internal data class LocalNetworkLabels(val file:String,val concept:String,val ink:String,val reference:String,
    val references:String,val belongs:String)
internal data class LocalNetworkScene(val document:MindMapDocument,val nodes:Map<String,NetworkSceneNodeKey>,
    val edges:Map<String,NetworkSceneEdgeKey>)

/** A flat, read-only adapter for the existing painter. This object never enters a document actor. */
internal class LocalNetworkSceneBuilder(private val maxPositions:Int=16384) {
    init {require(maxPositions in 1..16384)}
    private val positions=linkedMapOf<NetworkSceneNodeKey,Pt>()
    private fun encode(vararg values:String)=values.joinToString("") {"${it.length}:$it"}
    private fun nodeId(key:NetworkSceneNodeKey)=hash(when(key) {
        is NetworkSceneNodeKey.File->encode("file",key.documentId)
        is NetworkSceneNodeKey.Concept->encode("concept",key.key.stableKey)
    })
    private fun edgeId(key:NetworkSceneEdgeKey)=hash(when(key) {
        is NetworkSceneEdgeKey.References->encode("reference",key.concept.stableKey,key.fileId)
        is NetworkSceneEdgeKey.Belongs->encode("belongs",key.concept.stableKey)
    })
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") {"%02x".format(Locale.ROOT,it)}
    fun build(report:LocalFileNetworkReport,labels:LocalNetworkLabels):LocalNetworkScene {
        val ownerIds=report.concepts.mapTo(hashSetOf()) {it.location.graphId}
        val keys=report.files.map {NetworkSceneNodeKey.File(it.documentId)}+
            report.concepts.map {NetworkSceneNodeKey.Concept(it.key)}
        require(keys.distinct().size==keys.size)
        require(keys.size<=maxPositions)
        val visible=keys.toSet()
        // Access order is explicit: surviving visible cards stay, and the oldest hidden keys
        // are the only eviction candidates. Hidden positions continue reserving their slots.
        for(key in keys) positions.remove(key)?.let {positions[key]=it}
        val missing=keys.count {it !in positions}
        while(positions.size+missing>maxPositions) {
            val oldest=positions.keys.firstOrNull {it !in visible} ?: break
            positions.remove(oldest)
        }
        fun column(key:NetworkSceneNodeKey)=when(key) {
            is NetworkSceneNodeKey.Concept->-1
            is NetworkSceneNodeKey.File->when(key.documentId) {report.focusDocumentId->0;in ownerIds->-2;else->1}
        }
        val root=NetworkSceneNodeKey.File(report.focusDocumentId)
        positions[root]=Pt(0.0,0.0)
        val occupied=positions.values.groupBy {it.x}.mapValues {(_,points)->points.mapTo(hashSetOf()) {it.y}}.toMutableMap()
        for(key in keys.sortedBy(::nodeId)) if(key !in positions) {
            val x=column(key)*FileNetworkColumnStep.value.toDouble()
            val used=occupied.getOrPut(x) {hashSetOf()}
            var slot=0
            fun y(at:Int)=(if(at==0) 0 else if(at%2==1) (at+1)/2 else -at/2)*FileNetworkRowStep.value.toDouble()
            while(y(slot) in used) slot++
            positions[key]=Pt(x,y(slot));used+=y(slot)
        }
        val files=report.files.associateBy {it.documentId}
        val concepts=report.concepts.associateBy {it.key}
        val nodes=keys.associateBy(::nodeId)
        val model=nodes.map {(id,key)->
            val point=positions.getValue(key)
            val text=when(key) {
                is NetworkSceneNodeKey.File->labels.file+"\n"+files.getValue(key.documentId).observedNames.firstOrNull().orEmpty().ifBlank {labels.file}
                is NetworkSceneNodeKey.Concept->{val c=concepts.getValue(key.key)
                    labels.concept+"\n"+c.title.ifBlank {when(c.kind) {NodeKind.INK->labels.ink;NodeKind.REFERENCE->labels.reference;else->labels.concept}}}
            }
            MapNode(id=id,parent=null,free=key!=root,text=text,x=point.x,y=point.y,
                width=FileNetworkCardWidth.value.toDouble(),height=FileNetworkCardHeight.value.toDouble())
        }
        val edges=linkedMapOf<String,NetworkSceneEdgeKey>()
        val relations=mutableListOf<MapRelation>()
        for(c in report.concepts.sortedBy {it.key.stableKey}) {
            val from=nodeId(NetworkSceneNodeKey.Concept(c.key))
            for((file,members) in c.members.groupBy {it.fileId}.toSortedMap()) {
                val key=NetworkSceneEdgeKey.References(c.key,file);val id=edgeId(key);edges[id]=key
                relations+=MapRelation(id,from,nodeId(NetworkSceneNodeKey.File(file)),RelationKind.REFERENCED_FROM,
                    String.format(Locale.getDefault(),labels.references,members.size))
            }
            val key=NetworkSceneEdgeKey.Belongs(c.key);val id=edgeId(key);edges[id]=key
            relations+=MapRelation(id,from,nodeId(NetworkSceneNodeKey.File(c.location.graphId)),RelationKind.BELONGS_TO,labels.belongs)
        }
        val document=MindMapDocument(id=UUID.nameUUIDFromBytes(encode("file-network",report.focusDocumentId).toByteArray(Charsets.UTF_8)).toString(),
            nodes=model,relations=relations)
        document.validate()
        return LocalNetworkScene(document,nodes,edges)
    }
}
