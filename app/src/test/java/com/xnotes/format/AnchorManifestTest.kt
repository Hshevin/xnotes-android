package com.xnotes.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnchorManifestTest {
    private fun upgrade(text:String,anchored:Boolean=false):Pair<String,AnchorManifest.Plan> {
        val bytes=text.toByteArray(Charsets.UTF_8)
        val input={ByteArrayInputStream(bytes)}
        val plan=AnchorManifest.inspect(input,anchored)
        val out=ByteArrayOutputStream();AnchorManifest.write(input,out,plan)
        return out.toString("UTF-8") to plan
    }
    @Test fun onlyDirectPageFieldsChangeAndAllObjectBytesStayExact() {
        val source=""" {"pages":[ {"width":1.0000e+02,"items":[{"kind":"future","samples":[[-0,1.2500,0.1234567890123456789,1e9999]],"中文":"🖊\\u4e2d","extension":{"items":[{}]}}]},{} ],"format":"xnote","version":1,"extension":{"pages":[{}]}}  """
        val (next,plan)=upgrade(source)
        assertEquals(2,plan.identities.size);assertEquals(2,plan.insertions.size)
        val json=JSONObject(next);val pages=json.getJSONArray("pages")
        assertTrue(pages.getJSONObject(0).has("reference_id"));assertTrue(pages.getJSONObject(1).has("reference_id"))
        val item=pages.getJSONObject(0).getJSONArray("items").getJSONObject(0)
        assertFalse(item.has("reference_id"));assertFalse(item.getJSONObject("extension").getJSONArray("items").getJSONObject(0).has("reference_id"))
        assertFalse(json.getJSONObject("extension").getJSONArray("pages").getJSONObject(0).has("reference_id"))
        // Independent byte oracle: remove exactly the newly introduced simple UUID properties.
        assertEquals(source,next.replace(Regex("(?:,)?\\\"reference_id\\\":\\\"[a-f0-9-]{36}\\\""),""))
        val (twice,again)=upgrade(next,true);assertEquals(next,twice);assertTrue(again.insertions.isEmpty());assertEquals(plan.identities,again.identities)
    }
    @Test fun charactersAndClosingBracketsAcrossAllBufferOffsetsRemainByteIdentical() {
        for(padding in listOf(0,1,16360,16361,16362,16363,16364,16365,16366,32760)) {
            val source="""{"format":"xnote","version":1,"padding":"${"x".repeat(padding)}🖊中","pages":[ {  } ]} """
            val (next,_)=upgrade(source)
            assertArrayEquals(source.toByteArray(),next.replace(Regex("\\\"reference_id\\\":\\\"[a-f0-9-]{36}\\\""),"").toByteArray())
        }
    }
    private fun refuses(text:String,anchored:Boolean=false) {
        try {upgrade(text,anchored);fail("Must reject identity or schema corruption")} catch(_:IllegalArgumentException) {} catch(_:JsonPullException) {}
    }
    @Test fun rejectsDuplicatesMissingAnchoredIdsMixedAndInvalidIdentityOrSchema() {
        val id="00000000-0000-0000-0000-000000000001"
        refuses("""{"format":"xnote","version":1,"pages":[{}, {"reference_id":"$id"}]}""")
        refuses("""{"format":"xnote","version":1,"pages":[{"reference_id":"$id"},{"reference_id":"$id"}]}""")
        refuses("""{"format":"xnote","version":1,"pages":[{}]}""",true)
        for(field in listOf("null","1","\"broken\"")) refuses("""{"format":"xnote","version":1,"pages":[{"reference_id":$field}]}""")
        for(text in listOf(
            """{"format":"xnote","version":2,"pages":[]}""",
            """{"format":"xnote","version":1,"pages":{}}""",
            """{"format":"xnote","version":1,"pages":[null]}""",
            """{"format":"xnote","version":1,"pages":[4]}""",
            """{"format":"xnote","version":1,"pages":[{}],"pa\u0067es":[]}""",
            """{"format":"xnote","version":1,"pages":[{}],"format":"xnote"}""",
            """{"format":"xnote","version":1,"pages":[{}],"nested":{"a":1,"a":2}}""",
            """{"format":"xnote","version":1,"pages":[{}]} trailing"""
        )) refuses(text)
    }
    @Test fun emptyNoteCannotAcquireOrClaimPageAnchors() {
        refuses("""{"format":"xnote","version":1,"pages":[]}""")
        refuses("""{"format":"xnote","version":1,"pages":[]}""",true)
    }
    @Test fun preexistingEscapedUuidBytesArePreservedExactly() {
        val source="""{"format":"xnote","version":1,"pages":[{"kind":"future","reference_id":"\u00300000000-0000-0000-0000-000000000001"}]}"""
        val (next,plan)=upgrade(source,true)
        assertEquals(source,next);assertTrue(plan.insertions.isEmpty())
        assertEquals(setOf("00000000-0000-0000-0000-000000000001"),plan.identities)
    }
    @Test fun malformedUtf8AndCancellationAreRejectedWithoutPartialPublishing() {
        val malformed=byteArrayOf(0xc3.toByte(),0x28)
        try {AnchorManifest.inspect({ByteArrayInputStream(malformed)},false);fail("bad UTF8")} catch(_:java.nio.charset.CharacterCodingException) {}
        try {AnchorManifest.inspect({ByteArrayInputStream("{}".toByteArray())},false,cancelled={true});fail("cancel")} catch(_:IllegalStateException) {}
    }
    @Test fun keyMemoryBudgetCountsAllLiveAncestorObjectsButReleasesCompletedSiblings() {
        fun keys(n:Int)= (0 until n).joinToString(",") { "\""+"k".repeat(1000)+it+"\":0" }
        fun manifest(extension:String)="""{"format":"xnote","version":1,"pages":[{}],"extension":$extension}"""
        upgrade(manifest("{"+keys(100)+"}"))
        refuses(manifest("{"+keys(300)+"}"))
        refuses(manifest("{"+keys(150)+",\"nested\":{"+keys(150)+"}}"))
        upgrade(manifest("["+(0 until 4).joinToString(",") {"{"+keys(100)+"}"}+"]"))
        refuses(manifest("{\""+"x".repeat(1025)+"\":0}"))
    }

}
