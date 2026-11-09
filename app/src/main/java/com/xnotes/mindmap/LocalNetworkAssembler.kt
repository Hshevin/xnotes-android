package com.xnotes.mindmap

/** Global limits count exact render edges, including ownership, and never split a concept's evidence. */
internal object LocalNetworkAssembler {
    fun assemble(query:NetworkQuery,projections:List<NetworkConceptProjection>,issues:List<BacklinkIssue> = emptyList(),
        checkedMaps:Int=0,complete:Boolean=true,omittedIssues:Int=0,excludedMaps:Int=0,limited:Boolean=false,
        budget:LocalNetworkBudget=LocalNetworkBudget(),issuer:Any,generation:Long,
        origins:Map<NetworkConceptKey,NetworkConceptOrigin> = emptyMap(),
        rootFiles:List<LocalNetworkFile>?=null,extraFiles:List<LocalNetworkFile> = emptyList()):LocalFileNetworkReport {
        val problems=issues.toMutableList()
        var partial=!complete;var hitLimit=limited;var members=0;var edges=0
        val files=linkedMapOf<String,LinkedHashSet<String>>()
        val accepted=mutableListOf<LocalNetworkConcept>()
        val repeated=projections.groupingBy {it.key}.eachCount().filterValues {it>1}.keys
        val duplicates=projections.groupBy {it.key.graphId}.filterValues {all->all.map {it.key.locationKey}.distinct().size>1}.keys
        fun problem(kind:BacklinkProblem,name:String?=null) {partial=true;problems+=BacklinkIssue(kind,name?.take(BacklinkBudget.LABEL_CHARS))}
        fun addFile(file:LocalNetworkFile):Boolean {
            if(!runCatching {java.util.UUID.fromString(file.documentId).toString()==file.documentId}.getOrDefault(false)) {
                problem(BacklinkProblem.IDENTITY);return true
            }
            if(file.documentId !in files && files.size+accepted.size>=budget.maxSceneNodes) {
                hitLimit=true;problem(BacklinkProblem.LIMIT);return false
            }
            val names=files.getOrPut(file.documentId) {linkedSetOf()}
            file.observedNames.asSequence().filter {it.isNotBlank()}.take(8).forEach {
                if(names.size<8) names+=it.take(BacklinkBudget.LABEL_CHARS)
            }
            return true
        }
        val roots=rootFiles ?: if(query.hasLocalFocus) listOf(LocalNetworkFile(query.focusId,listOf(query.title))) else emptyList()
        for(file in roots) if(!addFile(file)) break
        for(p in projections.sortedWith(compareBy({origins[it.key]?.depth ?: 1},{it.key.stableKey}))) {
            val origin=origins[p.key]
            if(p.key in repeated || p.key.locationKey!=p.location.key || p.key.graphId!=p.location.graphId ||
                p.members.isEmpty() || (origin?.accepts(p) ?: (query.hasLocalFocus && p.members.any {it.target.documentId==query.focusId})).not() ||
                p.members.map {it.sourceId}.distinct().size!=p.members.size || p.members.any {
                    it.location.key!=p.key.locationKey || it.location.graphId!=p.key.graphId || it.nodeId!=p.key.nodeId
                }) {problem(BacklinkProblem.CHANGED,p.location.title);continue}
            // A readable graph with an unusable identity cannot become an invented file identity.
            if(runCatching {java.util.UUID.fromString(p.key.graphId).toString()==p.key.graphId}.getOrDefault(false).not()) {
                problem(BacklinkProblem.IDENTITY,p.location.title);continue
            }
            val sourceFiles=p.members.mapTo(linkedSetOf()) {it.target.documentId}
            val newFiles=(sourceFiles+p.key.graphId).filter {it !in files}
            val addedEdges=sourceFiles.size+1 // Distinct content edges plus the concept's ownership edge.
            if(files.size+newFiles.size+accepted.size+1>budget.maxSceneNodes || edges+addedEdges>budget.maxSceneEdges ||
                members+p.members.size>budget.evidenceLimit) {
                hitLimit=true;problem(BacklinkProblem.LIMIT);break
            }
            if(p.key.graphId in duplicates) problem(BacklinkProblem.DUPLICATE,p.location.title)
            val rows=p.members.map {row->if(p.key.graphId in duplicates) row.copy(blocked=BacklinkProblem.DUPLICATE) else row}
            rows.mapNotNull {it.blocked}.distinct().forEach {problem(it,p.location.title)}
            accepted+=LocalNetworkConcept(p.key,p.location,p.title.take(BacklinkBudget.LABEL_CHARS),p.kind,
                rows.map {LocalNetworkEvidence(it,issuer,generation)},origin)
            files.getOrPut(p.key.graphId) {linkedSetOf()}.add(p.location.title.take(BacklinkBudget.LABEL_CHARS))
            for(row in rows) {
                val names=files.getOrPut(row.target.documentId) {linkedSetOf()}
                if(names.size<8 && row.sourceFileName.isNotBlank()) names+=row.sourceFileName.take(BacklinkBudget.LABEL_CHARS)
            }
            members+=rows.size;edges+=addedEdges
        }
        // Known but unreferenced files must not consume the budget before actual relation evidence.
        for(file in extraFiles) if(!addFile(file)) break
        if(hitLimit && problems.none {it.problem==BacklinkProblem.LIMIT}) problem(BacklinkProblem.LIMIT)
        val distinct=problems.distinct()
        val extra=omittedIssues+(distinct.size-budget.read.maxIssues).coerceAtLeast(0)
        return LocalFileNetworkReport(query.focusId,files.map {LocalNetworkFile(it.key,it.value.toList())},accepted,
            distinct.take(budget.read.maxIssues),checkedMaps,!partial && !hitLimit && distinct.isEmpty() && extra==0,
            extra,excludedMaps,hitLimit)
    }
}
