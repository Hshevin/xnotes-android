package com.xnotes.mindmap

import org.junit.Assert.*
import org.junit.Test

class CollapsedNodeCountTest {
    private fun example()=MindMapDocument(nodes=listOf(
        MapNode(id="deep",parent="child"),
        MapNode(id="child",parent="branch",collapsed=true),
        MapNode(id="freeChild",parent="free"),
        MapNode(id="branch",parent="root",collapsed=true),
        MapNode(id="free",parent=null,free=true,collapsed=true),
        MapNode(id="root",parent=null),
        MapNode(id="leaf",parent="root",collapsed=true),
    )).also {it.validate()}

    @Test fun visibleCollapsedBranchesCountAllHiddenDescendantsAndKeepIndependentTreesSeparate() {
        val doc=example()
        assertEquals(mapOf("branch" to 2,"free" to 1),doc.hiddenNodeCounts())
        assertEquals(mapOf("root" to 4,"free" to 1),doc.update("root") {it.copy(collapsed=true)}.hiddenNodeCounts())
        assertEquals(mapOf("child" to 1,"free" to 1),doc.update("branch") {it.copy(collapsed=false)}.hiddenNodeCounts())
    }
    @Test fun deleteReparentAndReloadDeriveCountsFromHierarchyWithoutChangingTheFileFormat() {
        val doc=example();val bytes=MapCodec.encode(doc)
        assertEquals(doc.hiddenNodeCounts(),MapCodec.decode(bytes).hiddenNodeCounts())
        assertEquals(mapOf("free" to 1),doc.delete("child").hiddenNodeCounts())
        assertEquals(mapOf("child" to 1,"free" to 1),doc.reparent("child","root").hiddenNodeCounts())
        assertArrayEquals(bytes,MapCodec.encode(doc))
    }
    @Test fun sourcesAndOrdinaryRelationsDoNotCountAsHierarchyAndHistoryRestoresTheCount() {
        val source=MapSource(documentId="00000000-0000-0000-0000-000000000001",fileName="source")
        val doc=example().update("branch") {it.copy(sources=listOf(source,source.copy(id="second")))}
            .connect("branch","free",RelationKind.DEPENDS_ON).first
        assertEquals(example().hiddenNodeCounts(),doc.hiddenNodeCounts())
        val history=MapHistory(doc);history.commit(doc.update("branch") {it.copy(collapsed=false)})
        history.undo();assertEquals(doc.hiddenNodeCounts(),history.document.hiddenNodeCounts())
        history.redo();assertEquals(mapOf("child" to 1,"free" to 1),history.document.hiddenNodeCounts())
    }
}
