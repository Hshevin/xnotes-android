package com.xnotes.map.source

import com.xnotes.map.SourceKind
import com.xnotes.map.SourceRef
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertNull
import kotlin.test.Test

class PageLinkTest {

    private val doc = "0b9f3c2e-58a4-4a7e-9d43-0c1e2f3a4b5c"
    private val page = "7d1e2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b"
    private var ids = 0
    private fun newId() = "s${++ids}"
    private fun parse(text: String) = PageLink.parse(text, ::newId)

    private fun ref(kind: SourceKind = SourceKind.NOTE_PAGE, pageId: String? = page, label: String = "Math · 3") =
        SourceRef("any", kind, doc, pageId, label)

    @Test fun encodesInTheDocumentedForm() {
        assertEquals(
            "xnotes://link?v=1&kind=note_page&doc=$doc&page=$page&label=Math%20%C2%B7%203",
            PageLink.encode(ref()),
        )
        assertEquals(
            "xnotes://link?v=1&kind=canvas&doc=$doc&label=Sketch",
            PageLink.encode(ref(SourceKind.CANVAS, null, "Sketch")),
        )
    }

    @Test fun everyKindRoundTripsWithANewId() {
        for (r in listOf(ref(), ref(SourceKind.PDF_PAGE), ref(SourceKind.CANVAS, null, "Board"))) {
            val back = parse(PageLink.encode(r))
            assertEquals(r.copy(id = back!!.id), back)
            assertEquals("s$ids", back.id)
        }
    }

    @Test fun awkwardLabelsSurviveTheRoundTrip() {
        for (label in listOf("a&b=c", "100% #1 ?x", "数学 第3页", "plus+sign", "emoji 😀 ok", "~-._")) {
            assertEquals(label, parse(PageLink.encode(ref(label = label)))!!.label)
        }
    }

    @Test fun surroundingWhitespaceAndParameterOrderDoNotMatter() {
        val text = "  \n xnotes://link?label=X&page=$page&doc=$doc&kind=pdf_page&v=1 \t\n"
        assertEquals(SourceRef("s1", SourceKind.PDF_PAGE, doc, page, "X"), parse(text))
    }

    @Test fun unknownParametersAreIgnoredAndAMissingLabelIsEmpty() {
        val r = parse("xnotes://link?v=1&kind=canvas&doc=$doc&zoom=2")
        assertEquals(SourceRef("s1", SourceKind.CANVAS, doc, null, ""), r)
    }

    @Test fun aPlusIsAPlus() {
        assertEquals("a+b", parse("xnotes://link?v=1&kind=canvas&doc=$doc&label=a+b")!!.label)
    }

    @Test fun anythingElseIsNotALink() {
        val good = "xnotes://link?v=1&kind=note_page&doc=$doc&page=$page&label=L"
        val bad = listOf(
            "",
            "hello",
            good.replace("xnotes://link?", "xnotes://page?"),
            good.replace("xnotes://", "https://"),
            good.replace("v=1", "v=2"),
            good.replace("v=1&", ""),
            good.replace("note_page", "slide"),
            good.replace(doc, doc.uppercase()),
            good.replace(doc, "not-a-uuid"),
            good.replace("&page=$page", ""),
            "xnotes://link?v=1&kind=canvas&doc=$doc&page=$page",
            "$good&doc=$doc",
            "$good&label=again",
            good.replace("label=L", "label=%ZZ"),
            good.replace("label=L", "label=%4"),
            good.replace("label=L", "label=%FF"),
            "see $good",
            "$good $good",
            "$good\n$good",
        )
        for (text in bad) assertNull("accepted: $text", parse(text))
        assertEquals(0, ids)
    }

    @Test fun labelsAreCleanedAndCut() {
        assertEquals("ab", parse("xnotes://link?v=1&kind=canvas&doc=$doc&label=%20a%07b%0A%20")!!.label)
        val long = "字".repeat(PageLink.MAX_LABEL + 50)
        assertEquals(PageLink.MAX_LABEL, parse(PageLink.encode(ref(label = long)))!!.label.length)
        // An emoji is two chars but one code point and is never split.
        val emoji = "😀".repeat(PageLink.MAX_LABEL + 10)
        val cut = parse(PageLink.encode(ref(label = emoji)))!!.label
        assertEquals(PageLink.MAX_LABEL, cut.length - cut.zipWithNext().count { (first, second) -> first.isHighSurrogate() && second.isLowSurrogate() })
        assertEquals(emoji.substring(0, cut.length), cut)
    }
}
