package com.xnotes.map.source

import com.xnotes.map.SourceKind
import com.xnotes.map.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SourceResolverTest {

    private val doc = "0b9f3c2e-58a4-4a7e-9d43-0c1e2f3a4b5c"
    private val other = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
    private val p1 = "7d1e2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b"
    private val p2 = "8e2f3a4b-5c6d-4e7f-9a0b-1c2d3e4f5a6b"

    /** Locations by document id; what each location holds; locations that throw. */
    private class Files : SourceCatalogs {
        val where = HashMap<String, List<String>>()
        val at = HashMap<String, SourceCatalog>()
        val broken = HashSet<String>()
        val reads = ArrayList<String>()
        override fun locations(documentId: String) = where[documentId].orEmpty()
        override fun read(location: String): SourceCatalog? {
            reads += location
            if (location in broken) throw IOException("unreadable")
            return at[location]
        }
    }

    private val files = Files()
    private val resolver = SourceResolver(files)

    private fun note(id: String = doc, pages: List<String>? = listOf(p1, p2), name: String = "Math") =
        SourceCatalog(id, SourceCatalog.Kind.NOTE, name, pages)

    private fun pageRef(page: String = p2, kind: SourceKind = SourceKind.NOTE_PAGE, id: String = "a") =
        SourceRef(id, kind, doc, page, "stale label")

    @Test fun aPageThatIsThereIsReadyWithItsCurrentNumberAndName() {
        files.where[doc] = listOf("u1")
        files.at["u1"] = note(name = "Renamed")
        assertEquals(SourceStatus.Ready("u1", "Renamed", 2), resolver.resolve(pageRef()))
        assertEquals("a PDF page link resolves against the note", SourceStatus.Ready("u1", "Renamed", 2), resolver.resolve(pageRef(kind = SourceKind.PDF_PAGE)))
    }

    @Test fun aDeletedPageIsReportedNotRepointed() {
        files.where[doc] = listOf("u1")
        files.at["u1"] = note(pages = listOf(p1))
        assertEquals(SourceStatus.PageDeleted("u1", "Math"), resolver.resolve(pageRef()))
    }

    @Test fun aCanvasIsReadyWithoutAPage() {
        files.where[doc] = listOf("c1")
        files.at["c1"] = SourceCatalog(doc, SourceCatalog.Kind.CANVAS, "Board", null)
        assertEquals(SourceStatus.Ready("c1", "Board", null), resolver.resolve(SourceRef("a", SourceKind.CANVAS, doc, null, "")))
    }

    @Test fun theWrongKindOfDocumentIsSaidSo() {
        files.where[doc] = listOf("c1")
        files.at["c1"] = SourceCatalog(doc, SourceCatalog.Kind.CANVAS, "Board", null)
        assertEquals(SourceStatus.WrongKind, resolver.resolve(pageRef()))
        files.at["c1"] = note()
        assertEquals(SourceStatus.WrongKind, resolver.resolve(SourceRef("a", SourceKind.CANVAS, doc, null, "")))
        files.at["c1"] = SourceCatalog(doc, SourceCatalog.Kind.OTHER, "x.pdf", null)
        assertEquals(SourceStatus.WrongKind, resolver.resolve(pageRef()))
    }

    @Test fun aBrokenPageIdContractIsUnavailable() {
        files.where[doc] = listOf("u1")
        files.at["u1"] = note(pages = null)
        assertEquals(SourceStatus.Unavailable, resolver.resolve(pageRef()))
        files.at["u1"] = note(pages = listOf(p2, p1, p2))
        assertEquals(SourceStatus.Unavailable, resolver.resolve(pageRef()))
    }

    @Test fun noLocationIsMissing() {
        assertEquals(SourceStatus.Missing, resolver.resolve(pageRef()))
        files.where[doc] = listOf("gone")
        assertEquals("a location with nothing there", SourceStatus.Missing, resolver.resolve(pageRef()))
    }

    @Test fun anotherDocumentAtTheLocationIsSkippedNotTrusted() {
        files.where[doc] = listOf("u1", "u2")
        files.at["u1"] = note(id = other, name = "Imposter")
        files.at["u2"] = note(name = "Real")
        assertEquals(SourceStatus.Ready("u2", "Real", 2), resolver.resolve(pageRef()))
        files.at.remove("u2")
        assertEquals(SourceStatus.Missing, resolver.resolve(pageRef()))
    }

    @Test fun anUnreadableLocationGivesWayToAGoodOneButOtherwiseIsUnavailable() {
        files.where[doc] = listOf("bad", "u2")
        files.broken += "bad"
        files.at["u2"] = note()
        assertEquals(SourceStatus.Ready("u2", "Math", 2), resolver.resolve(pageRef()))
        files.at.remove("u2")
        assertEquals(SourceStatus.Unavailable, resolver.resolve(pageRef()))
    }

    @Test fun theFirstMatchingLocationDecidesEvenWhenALaterOneWouldBeKinder() {
        files.where[doc] = listOf("u1", "u2")
        files.at["u1"] = note(pages = listOf(p1))
        files.at["u2"] = note()
        assertEquals(SourceStatus.PageDeleted("u1", "Math"), resolver.resolve(pageRef()))
        assertEquals(listOf("u1"), files.reads)
    }

    @Test fun resolvingManyReadsEachLocationOnce() {
        files.where[doc] = listOf("u1")
        files.where[other] = listOf("c1")
        files.at["u1"] = note()
        files.at["c1"] = SourceCatalog(other, SourceCatalog.Kind.CANVAS, "Board", null)
        val refs = listOf(
            pageRef(p1, id = "a"), pageRef(p2, id = "b"), pageRef("9f3a4b5c-6d7e-4f8a-9b0c-1d2e3f4a5b6c", id = "c"),
            SourceRef("d", SourceKind.CANVAS, other, null, ""),
        )
        val out = resolver.resolveAll(refs)
        assertEquals(
            mapOf(
                "a" to SourceStatus.Ready("u1", "Math", 1),
                "b" to SourceStatus.Ready("u1", "Math", 2),
                "c" to SourceStatus.PageDeleted("u1", "Math"),
                "d" to SourceStatus.Ready("c1", "Board", null),
            ),
            out,
        )
        assertEquals(setOf("u1", "c1"), files.reads.toSet())
        assertEquals(2, files.reads.size)
    }

    @Test fun theLabelIsNeverUsedToResolve() {
        files.where[doc] = listOf("u1")
        files.at["u1"] = note(pages = listOf(p1))
        assertEquals(SourceStatus.PageDeleted("u1", "Math"), resolver.resolve(SourceRef("a", SourceKind.NOTE_PAGE, doc, p2, "Math · 1")))
    }
}
