package com.xnotes.mindmap

import org.json.JSONObject
import org.json.JSONArray
import com.xnotes.core.geometry.Rect

internal object MapGraphJson {
    fun sources(node: MapNode) = JSONArray().also { a -> node.sources.forEach { s ->
        a.put(JSONObject().put("id",s.id).put("documentId",s.documentId).put("fileName",s.fileName)
            .put("scope",s.scope.name).put("pageId",s.pageId ?: JSONObject.NULL)
            .put("note",s.note).put("status",s.status.name)
            .put("previewKey",s.previewKey ?: JSONObject.NULL).put("sourceVersion",s.sourceVersion ?: JSONObject.NULL)
            .put("region",s.region?.let { JSONArray(listOf(it.x,it.y,it.w,it.h)) } ?: JSONObject.NULL)
            .also {json->
                s.lastKnownPage?.let {json.put("lastKnownPage",it)}
                s.observedVersion?.let {json.put("observedVersion",it)}
                s.acceptedPreviewVersion?.let {json.put("acceptedPreviewVersion",it)}
                s.lastPreview?.let {preview->json.put("lastPreview",JSONObject()
                    .put("assetKey",preview.assetKey).put("renderedVersion",preview.renderedVersion)
                    .put("generatedAt",preview.generatedAt).put("target",target(preview.target)))}
            })
    } }
    /** v1 skips sources and v2 skips previews, so the format gate inspects their exact raw paths first. */
    fun rejectCanvasRegions(nodes:JSONArray) {
        for(i in 0 until nodes.length()) {
            val node=nodes.opt(i) as? JSONObject ?: continue
            val sources=node.opt("sources") as? JSONArray ?: continue
            for(k in 0 until sources.length()) {
                val source=sources.opt(k) as? JSONObject ?: continue
                require(source.opt("scope")!=SourceScope.CANVAS_REGION.name)
                val preview=source.opt("lastPreview") as? JSONObject ?: continue
                val target=preview.opt("target") as? JSONObject ?: continue
                require(target.opt("scope")!=SourceScope.CANVAS_REGION.name)
            }
        }
    }
    fun readSources(n: JSONObject, previews:Boolean = false): List<MapSource> {
        val a=n.getJSONArray("sources"); require(a.length()<=128)
        return (0 until a.length()).map { i -> val s=a.getJSONObject(i)
            // Earlier v2 whole-file sources wrote these empty fields. Reject obsolete semantics
            // and wrong types explicitly rather than guessing page targets or coercing defaults.
            if(s.has("objectIds")) require(s.get("objectIds").let {it is JSONArray && it.length()==0})
            if(s.has("includeAnnotations")) require(s.get("includeAnnotations").let {it is Boolean && !it})
            val r=if(s.isNull("region")) null else s.getJSONArray("region").let { require(it.length()==4);Rect(num(it.get(0)),num(it.get(1)),num(it.get(2)),num(it.get(3))) }
            MapSource(id=str(s,"id"),documentId=str(s,"documentId"),fileName=str(s,"fileName"),scope=SourceScope.valueOf(str(s,"scope")),
                pageId=nullable(s,"pageId"),region=r,
                note=str(s,"note"),status=SourceStatus.valueOf(str(s,"status")),previewKey=nullable(s,"previewKey"),
                sourceVersion=nullable(s,"sourceVersion"),lastKnownPage=lastKnownPage(s),
                observedVersion=if(previews) nullable(s,"observedVersion",true,::strValue) else null,
                acceptedPreviewVersion=if(previews) nullable(s,"acceptedPreviewVersion",true,::strValue) else null,
                lastPreview=if(previews) nullable(s,"lastPreview",true) {value->
                    require(value is JSONObject)
                    MapLastPreview(str(value,"assetKey"),readTarget(value.getJSONObject("target")),
                        str(value,"renderedVersion"),long(value.get("generatedAt")))
                } else null)
        }
    }
    private fun target(t:MapSourceTarget) = JSONObject().put("documentId",t.documentId)
        .put("scope",t.scope.name).put("pageId",t.pageId ?: JSONObject.NULL)
        .put("region",t.region?.let {JSONArray(listOf(it.x,it.y,it.w,it.h))} ?: JSONObject.NULL)
    private fun readTarget(j:JSONObject):MapSourceTarget {
        val region=if(j.get("region")===JSONObject.NULL) null else j.getJSONArray("region").let {
            require(it.length()==4);Rect(num(it.get(0)),num(it.get(1)),num(it.get(2)),num(it.get(3)))
        }
        return MapSourceTarget(str(j,"documentId"),SourceScope.valueOf(str(j,"scope")),nullable(j,"pageId"),region)
    }
    private fun long(value:Any):Long {require(value is Int || value is Long);return (value as Number).toLong()}
    fun relations(doc: MindMapDocument) = JSONArray().also { a -> doc.relations.forEach { r ->
        a.put(JSONObject().put("id",r.id).put("from",r.from).put("to",r.to).put("kind",r.kind.name).put("label",r.label))
    } }
    fun readRelations(j: JSONObject): List<MapRelation> {
        val a=j.getJSONArray("relations");require(a.length()<=4000)
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { MapRelation(str(it,"id"),str(it,"from"),str(it,"to"),RelationKind.valueOf(str(it,"kind")),str(it,"label")) } }
    }
    fun str(j:JSONObject,key:String) = strValue(j.get(key))
    private fun strValue(v:Any):String {require(v is String);return v}
    fun nullable(j:JSONObject,key:String):String? = nullable(j,key,false,::strValue)
    private fun <T> nullable(j:JSONObject,key:String,optional:Boolean,read:(Any)->T):T? {
        if(optional && !j.has(key)) return null
        return j.get(key).let {if(it===JSONObject.NULL) null else read(it)}
    }
    fun bool(j:JSONObject,key:String):Boolean = j.get(key).let {require(it is Boolean);it}
    private fun lastKnownPage(j:JSONObject):Int? = nullable(j,"lastKnownPage",true) {value->
        require(value is Int || value is Long)
        val number=(value as Number).toLong()
        require(number in 1L..com.xnotes.core.model.ReferenceAnchors.MAX_TARGETS.toLong())
        number.toInt()
    }
    private fun num(v:Any):Double {require(v is Number);return v.toDouble().also {require(it.isFinite())}}
}
