package com.xnotes.zotero

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ZoteroStoreTest {
    private fun dir(): File = Files.createTempDirectory("zotero").toFile()

    @Test fun configRoundTrips() {
        val plain = ZoteroConfig("key", 42, "ada")
        assertEquals(plain, ZoteroConfigCodec.decode(ZoteroConfigCodec.encode(plain)))
        val dav = plain.copy(webdav = ZoteroWebDav("https://dav.example/", "u", "p:ss"))
        assertEquals(dav, ZoteroConfigCodec.decode(ZoteroConfigCodec.encode(dav)))
    }

    @Test fun configRejectsForeignOrFutureText() {
        for (bad in listOf("", "[]", "{}", """{"version":2,"apiKey":"k","userId":1,"username":"a"}""", """{"version":1,"apiKey":5,"userId":1,"username":"a"}""")) {
            assertThrows(bad, Exception::class.java) { ZoteroConfigCodec.decode(bad) }
        }
    }

    @Test fun linksPersistReplaceAndRemove() {
        val file = File(dir(), "links.json")
        val store = ZoteroLinkStore(file)
        assertNull(store.find("u1", "A"))
        store.put(ZoteroLink("u1", "P", "A", "doc-1", "aa"))
        store.put(ZoteroLink("u1", "P", "B", "doc-2", null))
        store.put(ZoteroLink("g7", "Q", "A", "doc-3", null))
        store.put(ZoteroLink("u1", "P", "A", "doc-9", "bb"))
        val reopened = ZoteroLinkStore(file)
        assertEquals(ZoteroLink("u1", "P", "A", "doc-9", "bb"), reopened.find("u1", "A"))
        assertEquals("doc-3", reopened.find("g7", "A")!!.documentId)
        assertEquals(setOf("P"), reopened.linkedItems("u1"))
        reopened.remove("u1", "A"); reopened.remove("u1", "B")
        assertEquals(emptySet<String>(), ZoteroLinkStore(file).linkedItems("u1"))
        assertEquals(setOf("Q"), ZoteroLinkStore(file).linkedItems("g7"))
        assertEquals(listOf("links.json"), file.parentFile.list()!!.toList())
    }

    @Test fun corruptLinksAreKeptAsideAndStoreStartsEmpty() {
        val d = dir()
        val file = File(d, "links.json").apply { writeText("{not json") }
        val store = ZoteroLinkStore(file)
        assertNull(store.find("u1", "A"))
        val aside = d.listFiles()!!.single { it.name.startsWith("links.json.corrupt-") }
        assertEquals("{not json", aside.readText())
        store.put(ZoteroLink("u1", "P", "A", "doc", null))
        assertEquals("doc", ZoteroLinkStore(file).find("u1", "A")!!.documentId)
    }

    @Test fun noteNameIsSafe() {
        fun item(title: String) = ZoteroItem("K", "journalArticle", title, emptyList(), null, "", 0, null)
        assertEquals("A B C", zoteroNoteName(item("  A/B:\u0007C  "), "x"))
        assertEquals("x", zoteroNoteName(item("???"), "x"))
        assertEquals(120, zoteroNoteName(item("y".repeat(300)), "x").length)
    }
}
