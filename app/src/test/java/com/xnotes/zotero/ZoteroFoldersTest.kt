package com.xnotes.zotero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoteroFoldersTest {
    private val me = ZoteroLibrary(false, 1, "ada")
    private val lab = ZoteroLibrary(true, 7, "Lab: Vision")
    private val tree = listOf(
        ZoteroCollection("NLP", "NLP", null),
        ZoteroCollection("LLM", "LLM", "NLP"),
        ZoteroCollection("MT", "机器翻译", "NLP"),
        ZoteroCollection("ZZ", "Zeta", null),
        ZoteroCollection("DOT", ".xnote", null),
    )
    private fun item(vararg collections: String) = ZoteroItem("K", "journalArticle", "T", emptyList(), null, "", 0, null, collections.toList())

    @Test fun openedFromACollectionUsesItsPath() {
        assertEquals(listOf("NLP", "LLM"), zoteroFolderPath(me, tree, item("LLM", "ZZ"), "LLM"))
    }

    @Test fun groupLibrariesGetTheirOwnFolderMyLibraryDoesNot() {
        assertEquals(listOf("Lab Vision", "NLP"), zoteroFolderPath(lab, tree, item("NLP"), null))
        assertEquals(emptyList<String>(), zoteroFolderPath(me, tree, item(), null))
        assertEquals(listOf("Lab Vision"), zoteroFolderPath(lab, tree, item(), null))
    }

    @Test fun fromAllItemsOneCollectionOrTheFirstPathOfSeveral() {
        assertEquals(listOf("NLP", "机器翻译"), zoteroFolderPath(me, tree, item("MT"), null))
        assertEquals(listOf("NLP", "LLM"), zoteroFolderPath(me, tree, item("ZZ", "LLM", "MT"), null))
        assertEquals(listOf("NLP", "LLM"), zoteroFolderPath(me, tree, item("LLM", "ZZ", "MT"), null))
    }

    @Test fun goneCollectionsAreIgnored() {
        assertEquals(listOf("Zeta"), zoteroFolderPath(me, tree, item("GONE", "ZZ"), "GONE"))
        assertEquals(emptyList<String>(), zoteroFolderPath(me, tree, item("GONE"), null))
    }

    @Test fun folderNamesNeverHideOrBreak() {
        assertEquals(listOf("xnote"), zoteroFolderPath(me, tree, item("DOT"), null))
        assertEquals("collection_K", zoteroFolderName("...", "collection_K"))
        assertTrue(zoteroFolderName("类".repeat(100), "x").toByteArray().size <= ZOTERO_FOLDER_NAME_BYTES)
    }

    @Test fun cyclesAndDepthAreBounded() {
        val loop = listOf(ZoteroCollection("A", "a", "B"), ZoteroCollection("B", "b", "A"))
        assertEquals(listOf("b", "a"), zoteroFolderPath(me, loop, item("A"), null))
        val deep = (0 until 12).map { ZoteroCollection("D$it", "d$it", if (it == 0) null else "D${it - 1}") }
        val path = zoteroFolderPath(me, deep, item("D11"), null)
        assertEquals(ZOTERO_MAX_DEPTH, path.size)
        assertEquals("d0", path.first()); assertEquals("d11", path.last())
    }
}
