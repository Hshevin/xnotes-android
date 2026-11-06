package com.xnotes.mindmap

import org.json.JSONObject
import org.json.JSONArray
import com.xnotes.core.geometry.Rect

internal object MapGraphJson {
    fun sources(node: MapNode) = JSONArray().also { a -> node.sources.forEach { s ->
        a.put(JSONObject().put("id",s.id).put("documentId",s.documentId).put("fileName",s.fileName)
            .put("scope",s.scope.name).put("pageId",s.pageId ?: JSONObject.NULL)
            .put("objectIds",JSONArray(s.objectIds)).put("note",s.note).put("status",s.status.name)
            .put("previewKey",s.previewKey ?: JSONObject.NULL).put("sourceVersion",s.sourceVersion ?: JSONObject.NULL)
            .put("includeAnnotations",s.includeAnnotations).put("region",s.region?.let { JSONArray(listOf(it.x,it.y,it.w,it.h)) } ?: JSONObject.NULL))
    } }
    fun readSources(n: JSONObject): List<MapSource> {
        val a=n.getJSONArray("sources"); require(a.length()<=128)
        return (0 until a.length()).map { i -> val s=a.getJSONObject(i)
            val ids=s.getJSONArray("objectIds");require(ids.length()<=10000)
            val r=if(s.isNull("region")) null else s.getJSONArray("region").let { require(it.length()==4);Rect(num(it.get(0)),num(it.get(1)),num(it.get(2)),num(it.get(3))) }
            MapSource(id=str(s,"id"),documentId=str(s,"documentId"),fileName=str(s,"fileName"),scope=SourceScope.valueOf(str(s,"scope")),
                pageId=nullable(s,"pageId"),objectIds=(0 until ids.length()).map { strValue(ids.get(it)) },region=r,
                note=str(s,"note"),status=SourceStatus.valueOf(str(s,"status")),previewKey=nullable(s,"previewKey"),
                sourceVersion=nullable(s,"sourceVersion"),includeAnnotations=bool(s,"includeAnnotations"))
        }
    }
    fun relations(doc: MindMapDocument) = JSONArray().also { a -> doc.relations.forEach { r ->
        a.put(JSONObject().put("id",r.id).put("from",r.from).put("to",r.to).put("kind",r.kind.name).put("label",r.label))
    } }
    fun readRelations(j: JSONObject): List<MapRelation> {
        val a=j.getJSONArray("relations");require(a.length()<=4000)
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { MapRelation(str(it,"id"),str(it,"from"),str(it,"to"),RelationKind.valueOf(str(it,"kind")),str(it,"label")) } }
    }
    fun str(j:JSONObject,key:String) = strValue(j.get(key))
    private fun strValue(v:Any):String {require(v is String);return v}
    fun nullable(j:JSONObject,key:String):String? = if(j.get(key) === JSONObject.NULL) null else str(j,key)
    fun bool(j:JSONObject,key:String):Boolean = j.get(key).let {require(it is Boolean);it}
    private fun num(v:Any):Double {require(v is Number);return v.toDouble().also {require(it.isFinite())}}
}
