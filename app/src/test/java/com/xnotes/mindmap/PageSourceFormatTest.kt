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
    @Test fun optionalLastKnownPageHintsRoundTripInVersionTwoWithoutChangingReferenceIdentity() {
        for(hint in listOf(null,1,2,com.xnotes.core.model.ReferenceAnchors.MAX_TARGETS)) {
            val s=MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=page,lastKnownPage=hint,note="User note",sourceVersion="actual SHA")
            val doc=document(s);val j=JSONObject(String(MapCodec.encode(doc)));val encoded=source(j)
            assertEquals(3,j.getInt("version"));assertEquals(hint!=null,encoded.has("lastKnownPage"))
            assertEquals(doc,MapCodec.decode(j.toString().toByteArray()))
            // Earlier v2 readers ignore this optional display field; removing it preserves the target.
            encoded.remove("lastKnownPage")
            val legacy=MapCodec.decode(j.toString().toByteArray());val old=legacy.root.sources.single()
            assertEquals(s.copy(lastKnownPage=null),old);assertEquals(s.id,old.id);assertEquals(page,old.pageId)
            assertEquals(s.note,old.note);assertEquals(s.sourceVersion,old.sourceVersion)
            assertEquals(legacy,MapCodec.decode(MapCodec.encode(legacy)))
            encoded.put("lastKnownPage",JSONObject.NULL)
            assertEquals(legacy,MapCodec.decode(j.toString().toByteArray()))
        }
    }
    @Test fun displayPageHintsRejectInvalidNumbersTypesAndWholeFileLocations() {
        val max=com.xnotes.core.model.ReferenceAnchors.MAX_TARGETS
        for(hint in listOf(0,-1,max+1,Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {document(MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=page,lastKnownPage=hint)).validate()}
        }
        assertThrows(IllegalArgumentException::class.java) {document(MapSource(documentId=owner,lastKnownPage=1)).validate()}
        for(value in listOf<Any>("2",true,2.5,0,-1,max+1,Long.MAX_VALUE)) {
            val s=MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=page)
            val j=JSONObject(String(MapCodec.encode(document(s))));source(j).put("lastKnownPage",value)
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(j.toString().toByteArray())}
        }
        for(literal in listOf("2.0","2e0")) {
            val s=MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=page,lastKnownPage=2)
            val raw=String(MapCodec.encode(document(s))).replace("\"lastKnownPage\":2","\"lastKnownPage\":$literal")
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(raw.toByteArray())}
        }
        val file=json();source(file).put("lastKnownPage",1)
        assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(file.toString().toByteArray())}
    }
}
