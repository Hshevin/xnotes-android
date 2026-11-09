package com.xnotes.mindmap

import java.util.Collections
import java.util.UUID

/** Native formats last observed by the identity registry. PDF annotations are paged notes. */
internal enum class NetworkFileKind { NOTE,CANVAS,MAP }
internal class NetworkFolder(val uri:String,title:String) {
    val title=title.take(BacklinkBudget.LABEL_CHARS)
    init {require(uri.isNotBlank() && uri.length<=8192)}
    override fun equals(other:Any?)=other is NetworkFolder && uri==other.uri && title==other.title
    override fun hashCode()=31*uri.hashCode()+title.hashCode()
}
/** null means unrestricted; an empty selection intentionally matches nothing. */
internal class NetworkFilter(fileIds:Set<String>?=null,kinds:Set<NetworkFileKind>?=null,val folder:NetworkFolder?=null) {
    val fileIds:Set<String>?=fileIds?.let {
        require(it.size<=MAX_FILES && it.all {id->runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)})
        Collections.unmodifiableSet(LinkedHashSet(it))
    }
    val kinds:Set<NetworkFileKind>?=kinds?.let {Collections.unmodifiableSet(LinkedHashSet(it))}
    val active get()=fileIds!=null || kinds!=null || folder!=null
    override fun equals(other:Any?)=other is NetworkFilter && fileIds==other.fileIds && kinds==other.kinds && folder==other.folder
    override fun hashCode()=31*(31*(fileIds?.hashCode() ?: 0)+(kinds?.hashCode() ?: 0))+(folder?.hashCode() ?: 0)
    companion object {const val MAX_FILES=10000}
}
internal enum class NetworkFolderMatch { INSIDE,OUTSIDE,UNKNOWN }
internal data class NetworkFileFact(val kind:NetworkFileKind?,val folder:NetworkFolderMatch=NetworkFolderMatch.UNKNOWN,
    val problem:BacklinkProblem?=null)
internal data class NetworkFileFacts(val files:Map<String,NetworkFileFact>,val issues:List<BacklinkIssue> = emptyList())
internal data class NetworkFolderListing(val folders:List<NetworkFolder>,val problem:BacklinkProblem?=null,val limited:Boolean=false)
internal data class NetworkFilterResult(val concepts:List<NetworkConceptProjection>,val matched:Set<String>,val unknown:Set<String>,
    val filteredConcepts:Int)

/** Conditions apply to the same real source; membership payloads remain untouched. */
internal object NetworkFilterSelector {
    private enum class Match { YES,NO,UNKNOWN }
    private fun match(id:String,filter:NetworkFilter,facts:NetworkFileFacts):Match {
        if(filter.fileIds?.contains(id)==false || filter.kinds?.isEmpty()==true) return Match.NO
        if(filter.kinds==null && filter.folder==null) return Match.YES
        val fact=facts.files[id]
        if(fact?.problem in setOf(BacklinkProblem.IDENTITY,BacklinkProblem.DUPLICATE,BacklinkProblem.LIMIT,
                BacklinkProblem.CORRUPT,BacklinkProblem.RECOVERY_CONFLICT)) return Match.UNKNOWN
        // A known negative wins even when a different field could not be determined.
        if(filter.kinds!=null && fact?.kind!=null && fact.kind !in filter.kinds) return Match.NO
        if(fact?.problem!=null) return Match.UNKNOWN
        if(filter.folder!=null && fact?.folder==NetworkFolderMatch.OUTSIDE) return Match.NO
        if(filter.kinds!=null && fact?.kind==null || filter.folder!=null && fact?.folder!=NetworkFolderMatch.INSIDE) return Match.UNKNOWN
        return Match.YES
    }
    fun select(projections:List<NetworkConceptProjection>,filter:NetworkFilter,
        facts:NetworkFileFacts=NetworkFileFacts(emptyMap())):NetworkFilterResult {
        val ids=projections.flatMapTo(linkedSetOf()) {it.members.map {row->row.target.documentId}}
        val matches=ids.associateWith {match(it,filter,facts)}
        val kept=projections.filter {p->p.members.any {matches[it.target.documentId]==Match.YES}}
        return NetworkFilterResult(kept,Collections.unmodifiableSet(matches.filterValues {it==Match.YES}.keys.toSet()),
            Collections.unmodifiableSet(matches.filterValues {it==Match.UNKNOWN}.keys.toSet()),projections.size-kept.size)
    }
}
