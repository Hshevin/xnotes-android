package com.xnotes.mindmap

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OriginalPdfNetworkSceneTest {
    private val original=UUID.randomUUID().toString()
    private val note=UUID.randomUUID().toString()
    private fun report(status:SourceStatus?=null)=LocalFileNetworkReport(original,listOf(
        LocalNetworkFile(original,listOf("Same title"),kind=NetworkFileKind.PDF_ORIGINAL,availability=status,metadataChecked=true),
        LocalNetworkFile(note,listOf("Same title"),kind=NetworkFileKind.NOTE)),emptyList(),emptyList(),0,true,
        browseMode=NetworkBrowseMode.LIBRARY)
    private val labels=LocalNetworkLabels("File","Concept","Ink","Reference","%d refs","Belongs",fileKinds=mapOf(
        NetworkFileKind.NOTE to "Annotated note",NetworkFileKind.PDF_ORIGINAL to "Original PDF"),
        availability=mapOf(SourceStatus.TRASHED to "Deleted",SourceStatus.UNAVAILABLE to "Unavailable"))
    @Test fun originalAndItsAnnotationNoteHaveDistinctVisibleKindsWithoutChangingTheirIdentities() {
        val scene=LocalNetworkSceneBuilder().build(report(),labels)
        fun node(id:String)=scene.document.node(scene.nodes.entries.single {it.value==NetworkSceneNodeKey.File(id)}.key)
        assertEquals("Original PDF\nSame title",node(original).text)
        assertEquals("Annotated note\nSame title",node(note).text)
        assertEquals(setOf(original,note),scene.nodes.values.filterIsInstance<NetworkSceneNodeKey.File>().map {it.documentId}.toSet())
        assertTrue(scene.document.relations.isEmpty())
    }
    @Test fun changingAnOriginalAvailabilityOnlyChangesItsCaptionAndKeepsBothStablePositions() {
        val builder=LocalNetworkSceneBuilder()
        val before=builder.build(report(),labels)
        val deleted=builder.build(report(SourceStatus.TRASHED),labels)
        val missing=builder.build(report(SourceStatus.UNAVAILABLE),labels)
        for((render,key) in before.nodes) {
            assertEquals(key,deleted.nodes[render]);assertEquals(key,missing.nodes[render])
            val a=before.document.node(render);val b=deleted.document.node(render);val c=missing.document.node(render)
            assertEquals(a.x,b.x,0.0);assertEquals(a.y,b.y,0.0)
            assertEquals(a.x,c.x,0.0);assertEquals(a.y,c.y,0.0)
            if(key==NetworkSceneNodeKey.File(original)) {
                assertEquals("Original PDF\nSame title\nDeleted",b.text)
                assertEquals("Original PDF\nSame title\nUnavailable",c.text)
            } else {assertEquals(a,b);assertEquals(a,c)}
        }
        assertEquals(before.document.id,deleted.document.id)
    }
}
