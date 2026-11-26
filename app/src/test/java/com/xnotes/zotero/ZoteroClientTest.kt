package com.xnotes.zotero

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ZoteroClientTest {
    private data class Call(val url: String, val headers: Map<String, String>)

    private class Fake(val routes: (String) -> Triple<Int, Map<String, String>, ByteArray>) : ZoteroHttp {
        val calls = mutableListOf<Call>()
        var opened = 0
        var closed = 0
        override fun get(url: String, headers: Map<String, String>): ZoteroResponse {
            calls += Call(url, headers)
            val (status, h, body) = routes(url)
            opened++
            return ZoteroResponse(status, h.mapKeys { it.key.lowercase() }.mapValues { listOf(it.value) }, ByteArrayInputStream(body)) { closed++ }
        }
    }

    private fun ok(json: String, headers: Map<String, String> = emptyMap()) = Triple(200, headers, json.toByteArray())
    private fun status(code: Int, headers: Map<String, String> = emptyMap()) = Triple(code, headers, ByteArray(0))
    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    private val me = ZoteroLibrary(false, 42, "me")
    private val pdf = ZoteroAttachment("ATT1", "Full Text PDF", "paper.pdf", "application/pdf", "imported_url", null)

    @Test fun accountReadsKeyAccessAndSendsKeyOnlyToApi() {
        val fake = Fake { ok("""{"userID":42,"username":"ada","access":{"user":{"library":true,"files":true}}}""") }
        val account = ZoteroClient(fake, "secret").account()
        assertEquals(ZoteroAccount(42, "ada", libraryAccess = true, fileAccess = true), account)
        assertEquals("https://api.zotero.org/keys/current", fake.calls.single().url.substringBefore('?'))
        assertEquals("secret", fake.calls.single().headers["Zotero-API-Key"])
        assertEquals("3", fake.calls.single().headers["Zotero-API-Version"])
        assertEquals(1, fake.closed)
    }

    @Test fun missingFileAccessIsReported() {
        val fake = Fake { ok("""{"userID":42,"username":"ada","access":{"user":{"library":true}}}""") }
        assertFalse(ZoteroClient(fake, "k").account().fileAccess)
    }

    @Test fun rejectedKeyIsBadKey() {
        for (code in listOf(403, 404)) {
            val e = assertThrows(ZoteroException::class.java) { ZoteroClient(Fake { status(code) }, "k").account() }
            assertEquals(ZoteroFailure.BAD_KEY, e.failure)
        }
    }

    @Test fun rateLimitIsReported() {
        val e = assertThrows(ZoteroException::class.java) { ZoteroClient(Fake { status(429, mapOf("Retry-After" to "5")) }, "k").account() }
        assertEquals(ZoteroFailure.RATE_LIMITED, e.failure)
    }

    @Test fun librariesPutOwnFirstThenGroupsByName() {
        val fake = Fake { url ->
            assertTrue(url, url.startsWith("https://api.zotero.org/users/42/groups"))
            ok("""[{"id":9,"data":{"name":"Zeta lab"}},{"id":7,"data":{"name":"Alpha lab"}}]""", mapOf("Total-Results" to "2"))
        }
        val libs = ZoteroClient(fake, "k").libraries(42, "ada")
        assertEquals(listOf(ZoteroLibrary(false, 42, "ada"), ZoteroLibrary(true, 7, "Alpha lab"), ZoteroLibrary(true, 9, "Zeta lab")), libs)
        assertEquals("g7", libs[1].key); assertEquals("groups/7", libs[1].path)
    }

    @Test fun collectionsReadEveryPage() {
        val fake = Fake { url ->
            val start = Regex("[?&]start=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 0
            val body = (start until minOf(start + 100, 130)).joinToString(",", "[", "]") {
                """{"key":"C$it","data":{"name":"n${"%03d".format(it)}","parentCollection":${if (it == 5) "\"C1\"" else "false"}}}"""
            }
            ok(body, mapOf("Total-Results" to "130"))
        }
        val all = ZoteroClient(fake, "k").collections(me)
        assertEquals(130, all.size)
        assertEquals("n000", all.first().name)
        assertNull(all.first().parentKey)
        assertEquals("C1", all.single { it.key == "C5" }.parentKey)
        assertTrue(fake.calls.size >= 2)
    }

    @Test fun itemsAskNewestTopLevelNonNotesAndParseFields() {
        val fake = Fake { ok("""[
            {"key":"P1","meta":{"parsedDate":"2021-03-04","numChildren":2},"data":{"key":"P1","itemType":"journalArticle","title":"Deep ink","dateAdded":"2024-01-02T00:00:00Z",
              "creators":[{"creatorType":"editor","lastName":"Ed"},{"creatorType":"author","firstName":"A","lastName":"Lovelace"},{"creatorType":"author","name":"Babbage Lab"}]}},
            {"key":"S1","meta":{},"data":{"key":"S1","itemType":"attachment","title":"","filename":"loose.pdf","contentType":"application/pdf","linkMode":"imported_file","md5":"ABCDEF","dateAdded":"2024-01-01T00:00:00Z"}}
        ]""", mapOf("Total-Results" to "60")) }
        val page = ZoteroClient(fake, "k").items(me, "COLL", "ink", start = 0)
        val url = fake.calls.single().url
        assertTrue(url, url.startsWith("https://api.zotero.org/users/42/collections/COLL/items/top?"))
        for (p in listOf("sort=dateAdded", "direction=desc", "itemType=-note", "start=0", "limit=50", "q=ink", "qmode=titleCreatorYear")) assertTrue("$p in $url", url.contains(p))
        assertEquals(60, page.total); assertEquals(50, page.nextStart)
        val paper = page.items[0]
        assertEquals("Deep ink", paper.title); assertEquals(listOf("Lovelace", "Babbage Lab"), paper.creators)
        assertEquals("2021", paper.year); assertEquals(2, paper.childCount); assertNull(paper.attachment)
        val loose = page.items[1]
        assertEquals("loose.pdf", loose.title); assertNull(loose.year)
        assertEquals("abcdef", loose.attachment!!.md5); assertTrue(loose.attachment!!.stored)
    }

    @Test fun itemsWithoutCollectionOrQueryUseLibraryTopAndLastPageHasNoNext() {
        val fake = Fake { ok("""[]""", mapOf("Total-Results" to "10")) }
        val page = ZoteroClient(fake, "k").items(ZoteroLibrary(true, 7, "lab"), null, "  ", start = 0)
        val url = fake.calls.single().url
        assertTrue(url, url.startsWith("https://api.zotero.org/groups/7/items/top?"))
        assertFalse(url.contains("q="))
        assertNull(page.nextStart)
    }

    @Test fun creatorsFallBackToAllWhenNoAuthor() {
        val fake = Fake { ok("""[{"key":"P","meta":{},"data":{"key":"P","itemType":"book","title":"T","dateAdded":"","creators":[{"creatorType":"editor","lastName":"Ed"}]}}]""") }
        assertEquals(listOf("Ed"), ZoteroClient(fake, "k").items(me, null, null, 0).items.single().creators)
    }

    @Test fun pdfsAreChildPdfsOrTheItemItself() {
        val fake = Fake { url ->
            assertTrue(url, url.startsWith("https://api.zotero.org/users/42/items/P1/children"))
            ok("""[
              {"key":"N","data":{"key":"N","itemType":"note"}},
              {"key":"A","data":{"key":"A","itemType":"attachment","title":"Snapshot","contentType":"text/html","linkMode":"imported_url"}},
              {"key":"B","data":{"key":"B","itemType":"attachment","title":"PDF","filename":"b.pdf","contentType":"application/pdf","linkMode":"linked_file"}},
              {"key":"C","data":{"key":"C","itemType":"attachment","title":"PDF 2","filename":"c.pdf","contentType":"application/pdf","linkMode":"imported_file","md5":"aa"}}
            ]""")
        }
        val client = ZoteroClient(fake, "k")
        val parent = ZoteroItem("P1", "journalArticle", "T", emptyList(), null, "", 4, null)
        assertEquals(listOf("B", "C"), client.pdfs(me, parent).map { it.key })
        val standalone = ZoteroItem("S", "attachment", "s.pdf", emptyList(), null, "", 0, pdf.copy(key = "S"))
        assertEquals(listOf("S"), client.pdfs(me, standalone).map { it.key })
        assertEquals(1, fake.calls.size)
    }

    @Test fun downloadFollowsRedirectWithoutKeyAndChecksMd5() {
        val bytes = "%PDF-1.7 hello".toByteArray()
        val fake = Fake { url ->
            when {
                url.startsWith("https://api.zotero.org/users/42/items/ATT1/file") -> status(302, mapOf("Location" to "https://files.example/s3/abc"))
                url == "https://files.example/s3/abc" -> Triple(200, emptyMap(), bytes)
                else -> error(url)
            }
        }
        val out = ByteArrayOutputStream()
        ZoteroClient(fake, "secret").download(me, pdf.copy(md5 = md5(bytes)), null, out)
        assertArrayEquals(bytes, out.toByteArray())
        assertEquals("secret", fake.calls[0].headers["Zotero-API-Key"])
        assertNull(fake.calls[1].headers["Zotero-API-Key"])
        assertEquals(fake.opened, fake.closed)
    }

    @Test fun downloadRejectsWrongMd5() {
        val fake = Fake { Triple(200, emptyMap(), "x".toByteArray()) }
        val e = assertThrows(ZoteroException::class.java) { ZoteroClient(fake, "k").download(me, pdf.copy(md5 = "00"), null, ByteArrayOutputStream()) }
        assertEquals(ZoteroFailure.CHECKSUM, e.failure)
    }

    @Test fun downloadRefusesPlainHttpRedirectAndTooManyHops() {
        val toHttp = Fake { url -> if (url.startsWith("https://api")) status(302, mapOf("Location" to "http://files.example/x")) else error("followed $url") }
        assertThrows(ZoteroException::class.java) { ZoteroClient(toHttp, "k").download(me, pdf, null, ByteArrayOutputStream()) }
        var n = 0
        val loop = Fake { status(302, mapOf("Location" to "https://files.example/${n++}")) }
        assertThrows(ZoteroException::class.java) { ZoteroClient(loop, "k").download(me, pdf, null, ByteArrayOutputStream()) }
        assertTrue(loop.calls.size <= 4)
        assertEquals(loop.opened, loop.closed)
    }

    @Test fun missingStoredFileAndLinkedFile() {
        val e = assertThrows(ZoteroException::class.java) { ZoteroClient(Fake { status(404) }, "k").download(me, pdf, null, ByteArrayOutputStream()) }
        assertEquals(ZoteroFailure.FILE_MISSING, e.failure)
        val none = Fake { error("must not connect") }
        val linked = assertThrows(ZoteroException::class.java) { ZoteroClient(none, "k").download(me, pdf.copy(linkMode = "linked_file"), null, ByteArrayOutputStream()) }
        assertEquals(ZoteroFailure.NOT_STORED, linked.failure)
    }

    @Test fun webdavReadsZipWithBasicAuthAndNoApiKey() {
        val bytes = "%PDF-1.4 dav".toByteArray()
        val zip = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("dir/")); z.closeEntry(); z.putNextEntry(ZipEntry("paper.pdf")); z.write(bytes); z.closeEntry() } }.toByteArray()
        val fake = Fake { url -> assertEquals("https://dav.example/remote/zotero/ATT1.zip", url); Triple(200, emptyMap(), zip) }
        val out = ByteArrayOutputStream()
        ZoteroClient(fake, "secret").download(me, pdf.copy(md5 = md5(bytes)), ZoteroWebDav("https://dav.example/remote/", "u", "p"), out)
        assertArrayEquals(bytes, out.toByteArray())
        val headers = fake.calls.single().headers
        assertNull(headers["Zotero-API-Key"])
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("u:p".toByteArray()), headers["Authorization"])
    }

    @Test fun webdavFailures() {
        val dav = ZoteroWebDav("https://dav.example", "u", "p")
        for ((code, failure) in listOf(401 to ZoteroFailure.WEBDAV_AUTH, 403 to ZoteroFailure.WEBDAV_AUTH, 404 to ZoteroFailure.WEBDAV_MISSING)) {
            val fake = Fake { url -> assertEquals("https://dav.example/zotero/ATT1.zip", url); status(code) }
            assertEquals(failure, assertThrows(ZoteroException::class.java) { ZoteroClient(fake, "k").download(me, pdf, dav, ByteArrayOutputStream()) }.failure)
        }
        val emptyZip = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
        assertEquals(ZoteroFailure.WEBDAV_MISSING, assertThrows(ZoteroException::class.java) {
            ZoteroClient(Fake { Triple(200, emptyMap(), emptyZip) }, "k").download(me, pdf, dav, ByteArrayOutputStream())
        }.failure)
    }

    @Test fun cancelledDownloadStops() {
        val fake = Fake { Triple(200, emptyMap(), ByteArray(1 shl 20)) }
        val e = assertThrows(ZoteroException::class.java) { ZoteroClient(fake, "k").download(me, pdf, null, ByteArrayOutputStream()) { true } }
        assertEquals(ZoteroFailure.CANCELLED, e.failure)
        assertEquals(fake.opened, fake.closed)
    }

    @Test fun unexpectedStatusIsServer() {
        val e = assertThrows(ZoteroException::class.java) { ZoteroClient(Fake { status(500) }, "k").collections(me) }
        assertEquals(ZoteroFailure.SERVER, e.failure)
    }
}
