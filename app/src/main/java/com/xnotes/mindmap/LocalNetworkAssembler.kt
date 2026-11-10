package com.xnotes.mindmap

/** Global limits count exact render edges, including ownership, and never split a concept's evidence. */
internal object LocalNetworkAssembler {
    fun assemble(query:NetworkQuery,projections:List<NetworkConceptProjection>,issues:List<BacklinkIssue> = emptyList(),
        checkedMaps:Int=0,complete:Boolean=true,omittedIssues:Int=0,excludedMaps:Int=0,limited:Boolean=false,
        budget:LocalNetworkBudget=LocalNetworkBudget(),issuer:Any,generation:Long,
        origins:Map<NetworkConceptKey,NetworkConceptOrigin> = emptyMap(),
        rootFiles:List<LocalNetworkFile>?=null,extraFiles:List<LocalNetworkFile> = emptyList(),
        fileRelations:List<MapFileRelationRow> = emptyList(),relationOrigins:Map<FileRelationKey,NetworkFileRelationOrigin> = emptyMap()):LocalFileNetworkReport {
        val problems=issues.toMutableList()
        var partial=!complete;var hitLimit=limited;var members=0;var edges=0
        val files=linkedMapOf<String,LinkedHashSet<String>>()
        val accepted=mutableListOf<LocalNetworkConcept>()
        val acceptedRelations=mutableListOf<MapFileRelationRow>()
        val renderRelations=hashSetOf<NetworkFileRelationKey>()
        val acceptedConceptKeys=hashSetOf<NetworkConceptKey>()
        val acceptedRelationKeys=hashSetOf<FileRelationKey>()
        fun prefixAccepted(path:List<MapBacklinkRow>,relations:List<MapFileRelationRow>)=
            path.all {NetworkConceptKey(it.location.key,it.location.graphId,it.nodeId) in acceptedConceptKeys} &&
                relations.all {it.key in acceptedRelationKeys}
        val repeatedRelations=fileRelations.groupingBy {it.key}.eachCount().filterValues {it>1}.keys
        val repeated=projections.groupingBy {it.key}.eachCount().filterValues {it>1}.keys
        val duplicates=(projections.map {it.key.graphId to it.key.locationKey}+fileRelations.map {it.location.graphId to it.location.key})
            .groupBy {it.first}.filterValues {all->all.map {it.second}.distinct().size>1}.keys
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
        data class Candidate(val concept:NetworkConceptProjection?,val relation:MapFileRelationRow?,val depth:Int,val key:String)
        val candidates=projections.map {Candidate(it,null,origins[it.key]?.depth ?: 1,it.key.stableKey)}+
            fileRelations.map {Candidate(null,it,relationOrigins[it.key]?.depth ?: 1,it.key.stableKey)}
        for(candidate in candidates.sortedWith(compareBy({it.depth},{it.key}))) {
            val relation=candidate.relation
            if(relation!=null) {
                val origin=relationOrigins[relation.key]
                if(relation.key in repeatedRelations || !canonicalNetworkId(relation.location.graphId) ||
                    relation.fileIds.size!=2 || relation.fileIds.any {!canonicalNetworkId(it)} ||
                    (origin?.accepts(relation) ?: (query.hasLocalFocus && query.focusId in relation.fileIds)).not()) {
                    problem(BacklinkProblem.CHANGED,relation.location.title);continue
                }
                if(origin!=null && !prefixAccepted(origin.path,origin.relationPath)) {
                    hitLimit=true;problem(BacklinkProblem.LIMIT);continue
                }
                val row=if(relation.location.graphId in duplicates) relation.copy(blocked=BacklinkProblem.DUPLICATE) else relation
                val key=NetworkFileRelationKey.of(row)
                val newFiles=row.fileIds.filter {it !in files}
                val edge=if(key in renderRelations) 0 else 1
                if(files.size+newFiles.size+accepted.size>budget.maxSceneNodes || edges+edge>budget.maxSceneEdges ||
                    members+1>budget.evidenceLimit) {hitLimit=true;problem(BacklinkProblem.LIMIT);continue}
                row.blocked?.let {problem(it,row.location.title)}
                if(row.conflict) problem(BacklinkProblem.RECOVERY_CONFLICT,row.location.title)
                acceptedRelations+=row;acceptedRelationKeys+=row.key;renderRelations+=key;members++;edges+=edge
                for((id,title) in listOf(row.fromFileId to row.fromTitle,row.toFileId to row.toTitle)) {
                    val names=files.getOrPut(id) {linkedSetOf()}
                    if(names.size<8 && title.isNotBlank()) names+=title.take(BacklinkBudget.LABEL_CHARS)
                }
                continue
            }
            val p=requireNotNull(candidate.concept)
            val origin=origins[p.key]
            if(p.key in repeated || p.key.locationKey!=p.location.key || p.key.graphId!=p.location.graphId ||
                p.members.isEmpty() || (origin?.accepts(p) ?: (query.hasLocalFocus && p.members.any {it.target.documentId==query.focusId})).not() ||
                p.members.map {it.sourceId}.distinct().size!=p.members.size || p.members.any {
                    it.location.key!=p.key.locationKey || it.location.graphId!=p.key.graphId || it.nodeId!=p.key.nodeId
                }) {problem(BacklinkProblem.CHANGED,p.location.title);continue}
            if(origin!=null && !prefixAccepted(origin.path,origin.relationPath)) {
                hitLimit=true;problem(BacklinkProblem.LIMIT);continue
            }
            // A readable graph with an unusable identity cannot become an invented file identity.
            if(runCatching {java.util.UUID.fromString(p.key.graphId).toString()==p.key.graphId}.getOrDefault(false).not()) {
                problem(BacklinkProblem.IDENTITY,p.location.title);continue
            }
            val sourceFiles=p.members.mapTo(linkedSetOf()) {it.target.documentId}
            val newFiles=(sourceFiles+p.key.graphId).filter {it !in files}
            val addedEdges=sourceFiles.size+1 // Distinct content edges plus the concept's ownership edge.
            if(files.size+newFiles.size+accepted.size+1>budget.maxSceneNodes || edges+addedEdges>budget.maxSceneEdges ||
                members+p.members.size>budget.evidenceLimit) {
                hitLimit=true;problem(BacklinkProblem.LIMIT);continue
            }
            if(p.key.graphId in duplicates) problem(BacklinkProblem.DUPLICATE,p.location.title)
            val rows=p.members.map {row->if(p.key.graphId in duplicates) row.copy(blocked=BacklinkProblem.DUPLICATE) else row}
            rows.mapNotNull {it.blocked}.distinct().forEach {problem(it,p.location.title)}
            accepted+=LocalNetworkConcept(p.key,p.location,p.title.take(BacklinkBudget.LABEL_CHARS),p.kind,
                rows.map {LocalNetworkEvidence(it,issuer,generation)},origin)
            acceptedConceptKeys+=p.key
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
            extra,excludedMaps,hitLimit,fileRelations=acceptedRelations)
    }
}
