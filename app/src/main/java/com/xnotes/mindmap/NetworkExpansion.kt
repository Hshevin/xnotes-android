package com.xnotes.mindmap

import java.util.Collections
import java.util.UUID

internal enum class NetworkOriginKind { SOURCE,OWNER }
/** A local origin and the last-observed route to it; no native owner or extra graph lease. */
internal class NetworkConceptOrigin(val fileId:String,val kind:NetworkOriginKind,val depth:Int,path:List<MapBacklinkRow> = emptyList()) {
    val path:List<MapBacklinkRow> = Collections.unmodifiableList(path.toList())
    init {require(canonical(fileId) && depth in 1..251 && path.size<=500)}
    internal fun accepts(p:NetworkConceptProjection)=when(kind) {
        NetworkOriginKind.SOURCE->p.members.any {it.target.documentId==fileId}
        NetworkOriginKind.OWNER->p.key.graphId==fileId && p.location.graphId==fileId
    }
    private companion object {fun canonical(id:String)=runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)}
}
internal data class NetworkExpansionResult(val concepts:List<NetworkConceptProjection>,val origins:Map<NetworkConceptKey,NetworkConceptOrigin>)

/** File-concept traversal follows real content/ownership edges, activating only explicit expansions. */
internal object NetworkExpansion {
    private data class Route(val depth:Int,val rows:List<MapBacklinkRow>)
    fun select(focusId:String,expanded:Set<String>,projections:List<NetworkConceptProjection>):NetworkExpansionResult =
        selectRoots(setOf(focusId),expanded,projections,includeOwnedRoots=false)
    fun selectRoots(roots:Set<String>,expanded:Set<String>,projections:List<NetworkConceptProjection>,
        includeOwnedRoots:Boolean=true):NetworkExpansionResult {
        require(roots.size<=250 && expanded.size<=250)
        val active=roots.sorted().associateWithTo(linkedMapOf()) {Route(0,emptyList())}
        val queue=ArrayDeque<String>().apply {addAll(active.keys)}
        val found=linkedMapOf<NetworkConceptKey,NetworkConceptProjection>()
        val origins=linkedMapOf<NetworkConceptKey,NetworkConceptOrigin>()
        val ordered=projections.sortedBy {it.key.stableKey}
        fun activate(file:String,route:Route) {
            if(file in expanded && file !in active && route.depth<=250) {active[file]=route;queue.add(file)}
        }
        while(queue.isNotEmpty()) {
            val file=queue.removeFirst();val route=active.getValue(file)
            for(p in ordered) {
                if(p.key in found) continue
                val anchor=p.members.firstOrNull {it.target.documentId==file}
                val kind=when {anchor!=null->NetworkOriginKind.SOURCE
                    (file in expanded || includeOwnedRoots && file in roots) && p.key.graphId==file->NetworkOriginKind.OWNER
                    else->continue}
                if(p.members.isEmpty()) continue
                found[p.key]=p
                origins[p.key]=NetworkConceptOrigin(file,kind,route.depth+1,route.rows)
                // Bad decoded identity rows remain visible to the assembler's existing diagnostics,
                // but cannot become a bridge to otherwise unreachable files.
                if(!valid(p)) continue
                val prefix=route.rows+listOfNotNull(anchor)
                for(member in p.members) activate(member.target.documentId,Route(route.depth+1,prefix+member))
                activate(p.key.graphId,Route(route.depth+1,prefix))
            }
        }
        return NetworkExpansionResult(Collections.unmodifiableList(found.values.toList()),Collections.unmodifiableMap(origins))
    }
    private fun valid(p:NetworkConceptProjection)=
        runCatching {UUID.fromString(p.key.graphId).toString()==p.key.graphId}.getOrDefault(false) &&
            p.location.key==p.key.locationKey && p.location.graphId==p.key.graphId &&
            p.members.map {it.sourceId}.distinct().size==p.members.size && p.members.all {
                it.blocked==null && it.location.key==p.key.locationKey && it.location.graphId==p.key.graphId && it.nodeId==p.key.nodeId
            }
}
