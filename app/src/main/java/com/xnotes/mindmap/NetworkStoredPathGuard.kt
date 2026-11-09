package com.xnotes.mindmap

import android.content.Context

/** Read-only directory observations for a captured path; never acquires any predecessor. */
internal class NetworkStoredPathGuard(context:Context,private val target:BacklinkLocation,
    sources:List<MapBacklinkRow>,relations:List<MapFileRelationRow>) {
    private val app=context.applicationContext
    private val sources=sources.filter {it.membership==null}.distinctBy {it.key}
    private val relations=relations.filter {it.membership==null}.distinctBy {it.key}
    private val positions=(this.sources.map {it.location.key}+this.relations.map {it.location.key}).toSet()
    private val versions=positions.associateWith {MapDocuments.observationVersion(app,it)}
    private var ownTarget:MapSession?=null
    private var targetVersion:Long?=null
    private var proofs:List<()->Boolean>?=null
    private var retired=false

    fun current():Boolean {
        if(retired) return false
        val observations=MapDocuments.observations(app).associateBy {it.key}
        for((key,version) in versions) {
            val own=ownTarget.takeIf {key==target.key}
            if(own==null) {
                if(MapDocuments.observationVersion(app,key)!=version || key in observations) {retired=true;return false}
                continue
            }
            val observed=observations[key]
            if(observed==null || observed.session!==own || MapDocuments.observationVersion(app,key)!=targetVersion ||
                observed.failed || observed.closing || observed.replacing) {retired=true;return false}
            if(observed.loading) continue
            if(observed.document?.id!=target.graphId || observed.creating) {retired=true;return false}
            if(proofs==null) {
                val captured=mutableListOf<()->Boolean>()
                for(row in sources.filter {it.location.key==target.key}) {
                    val member=observed.captureMembership(row.nodeId,row.sourceId,row.target)
                    if(row.location.graphId!=target.graphId || member==null ||
                        !member.current(own,row.location.graphId,row.nodeId,row.sourceId,row.target)) {retired=true;return false}
                    captured+={member.current(own,row.location.graphId,row.nodeId,row.sourceId,row.target)}
                }
                for(row in relations.filter {it.location.key==target.key}) {
                    val member=observed.captureFileRelation(row.relation.id)
                    if(row.location.graphId!=target.graphId || member==null ||
                        !member.current(own,row.location.graphId,row.relation,row.fromFileId,row.toFileId)) {retired=true;return false}
                    captured+={member.current(own,row.location.graphId,row.relation,row.fromFileId,row.toFileId)}
                }
                proofs=captured
            }
            if(proofs?.all {it()}!=true) {retired=true;return false}
        }
        return true
    }

    /** Called only by the existing action broker immediately after its exclusive first acquisition. */
    fun targetAcquired(session:MapSession):Boolean {
        if(target.key !in positions) return current()
        if(retired || ownTarget!=null) return ownTarget===session && current()
        val observed=MapDocuments.observations(app).singleOrNull {it.key==target.key && it.session===session} ?: return false
        ownTarget=observed.session;targetVersion=MapDocuments.observationVersion(app,target.key)
        return current()
    }
}
