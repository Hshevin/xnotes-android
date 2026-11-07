package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageSourceFormatTest {
    private val owner="00000000-0000-0000-0000-000000000001"
    private val page="00000000-0000-0000-0000-000000000002"
    private fun document(source:MapSource) = MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Concept",sources=listOf(source),primarySource=source.id)))
    private fun source(json:JSONObject)=json.getJSONArray("nodes").getJSONObject(0).getJSONArray("sources").getJSONObject(0)
    private fun json()=JSONObject(String(MapCodec.encode(document(MapSource(documentId=owner)))))
    @Test fun pageRegionIsOnlyAnOptionalHintAndRoundTripsWithoutObjectIdentityFields() {
        val s=MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=page,region=Rect(-10.0,20.0,300.0,400.0),note="position hint")
        val doc=document(s)
        val bytes=MapCodec.encode(doc)
        assertEquals(doc,MapCodec.decode(bytes))
        val encoded=source(JSONObject(String(bytes)))
        assertFalse(encoded.has("objectIds"));assertFalse(encoded.has("includeAnnotations"))
        assertEquals(page,encoded.getString("pageId"))
        val changed=doc.update("root") {it.copy(sources=listOf(s.copy(region=null)))}
        assertEquals(s.id,changed.root.sources.single().id)
        assertEquals(page,changed.root.sources.single().pageId)
        assertEquals("Concept",changed.root.text)
        assertEquals(s.id,changed.root.primarySource)
    }
    @Test fun readerAcceptsNewPageFormatAndLegacyEmptyObjectFields() {
        val samples=listOf(MapSource(documentId=owner),MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=page),
            MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=page,region=Rect(10.0,20.0,30.0,40.0)))
        for(sample in samples) for(objectsPresent in listOf(false,true)) for(annotationPresent in listOf(false,true)) {
            val original=document(sample);val j=JSONObject(String(MapCodec.encode(original)));val s=source(j)
            if(objectsPresent) s.put("objectIds",org.json.JSONArray()) else s.remove("objectIds")
            if(annotationPresent) s.put("includeAnnotations",false) else s.remove("includeAnnotations")
            assertEquals(original,MapCodec.decode(j.toString().toByteArray()))
        }
    }
    @Test fun oldObjectOrFixedRegionScopesAndAmbiguousAnnotationScopesAreRejected() {
        for(scope in listOf("OBJECTS","REGION","PDF_REGION")) {
            val j=json();source(j).put("scope",scope)
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(j.toString().toByteArray())}
        }
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("objectIds",org.json.JSONArray().put("old-object"))},
            {it.put("includeAnnotations",true)}
        )) {
            val j=json();change(source(j))
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(j.toString().toByteArray())}
        }
    }
    @Test fun pageIdentityMustBeCanonicalAndDocumentReferencesCannotCarryRegionHints() {
        for(id in listOf("page-one","0-0-0-0-2","A0000000-0000-0000-0000-000000000002"))
            assertThrows(IllegalArgumentException::class.java) {document(MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=id)).validate()}
        assertThrows(IllegalArgumentException::class.java) {document(MapSource(documentId=owner,region=Rect(0.0,0.0,10.0,10.0))).validate()}
        assertThrows(IllegalArgumentException::class.java) {document(MapSource(documentId=owner,pageId=page)).validate()}
    }
    @Test fun explicitNullAndWrongLegacyFieldTypesAreRejected() {
        for(key in listOf("objectIds","includeAnnotations")) for(value in listOf(JSONObject.NULL,"false",0,JSONObject())) {
            val j=json();source(j).put(key,value)
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(j.toString().toByteArray())}
        }
    }
    @Test fun multiplePageSourcesPreserveOrderDisplayMetadataAndPrimaryPreview() {
        val a=MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=page,note="first",fileName="Source filename",status=SourceStatus.STALE,previewKey="old_preview",sourceVersion="digest")
        val b=a.copy(id="second",pageId="00000000-0000-0000-0000-000000000003",note="second",region=Rect(1.0,2.0,3.0,4.0))
        val doc=document(a).update("root") {it.copy(sources=listOf(b,a),primarySource=b.id)}
        assertEquals(doc,MapCodec.decode(MapCodec.encode(doc)))
        val moved=doc.update("root") {it.copy(sources=it.sources.map {s->if(s.id==b.id) s.copy(region=null) else s})}
        assertEquals(b.id,moved.root.primarySource);assertEquals("Concept",moved.root.text)
        assertEquals(listOf(b.id,a.id),moved.root.sources.map {it.id})
    }
}
