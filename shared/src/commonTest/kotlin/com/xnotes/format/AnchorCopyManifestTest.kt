package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.testkit.*
import kotlin.test.Test

class AnchorCopyManifestTest : BundleTestFiles() {
    @Test fun copyPlanExposesTheActualNewPageOrderWithoutBorrowingTheOriginalIds() {
        val first="00000000-0000-4000-8000-000000000099";val second="00000000-0000-4000-8000-000000000001"
        val bytes="""{"format":"xnote","version":1,"pages":[{"reference_id":"$first","items":[]},{"reference_id":"$second","items":[]}]}""".encodeToByteArray()
        val original=AnchorManifest.inspectSource({bytes.asSource()},true)
        assertEquals(listOf(first,second),original.orderedPageIds)
        val copy=AnchorManifest.inspectCopySource({bytes.asSource()})
        val actual=AnchorManifest.inspectSource({write(bytes,copy).asSource()},true)
        assertEquals(copy.orderedPageIds,actual.orderedPageIds);assertEquals(copy.identities,copy.orderedPageIds.toSet())
        assertTrue(copy.identities.intersect(original.identities).isEmpty())
    }
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private fun escaped(id:String)=id.map {"\\u"+it.code.toString(16).padStart(4,'0')}.joinToString("")
    private fun template(padding:String="")=""" {"format":"xnote","version":1,"padding":"$padding","pages":[{"items":[{"reference_id":"$a","samples":[[-0,1.20000e+02,0.1234567890123456789]],"pages":[{"reference_id":"nested-id"}]}],"reference_\u0069d" :
        "@A@"  },{"reference_id":"@B@","items":[]}],"extension":{"pages":[{"reference_id":"$b"}]}}  """
    private fun write(bytes:ByteArray,plan:AnchorManifest.Plan,short:Boolean=false):ByteArray = Buffer().also {out->
        AnchorManifest.write({if(short) oneByteSource(bytes) else bytes.asSource()},out,plan)
    }.bytes()
    @Test fun replacesOnlyDirectPageStringLiteralsWithDisjointIdsAndKeepsAllOtherBytes() {
        val pattern=template();val source=pattern.replace("@A@",escaped(a)).replace("@B@",b).encodeToByteArray()
        val before=source.copyOf();val plan=AnchorManifest.inspectCopySource({source.asSource()})
        val result=write(source,plan);val pages=TestJsonTree.parse(result.decodeToString()).field("pages")
        val fresh=(0 until pages.size).map {pages.item(it).string("reference_id")}
        assertEquals(2,fresh.toSet().size);assertTrue(fresh.none {it==a || it==b})
        assertEquals(fresh.toSet(),plan.identities)
        assertArrayEquals(pattern.replace("@A@",fresh[0]).replace("@B@",fresh[1]).encodeToByteArray(),result)
        assertArrayEquals(result,write(source,plan));assertArrayEquals(before,source)
        assertTrue(AnchorManifest.inspectSource({result.asSource()},true).insertions.isEmpty())
    }
    @Test fun copyPreservesUnicodeAndLiteralBoundariesAcrossSingleByteReads() {
        for(size in listOf(0,16100,16200,16300,16340,16380,32740)) {
            val pattern=template("x".repeat(size)+"🖊中文")
            val source=pattern.replace("@A@",escaped(a)).replace("@B@",escaped(b)).encodeToByteArray()
            val open={oneByteSource(source)}
            val plan=AnchorManifest.inspectCopySource(open)
            val result=write(source,plan,true);val pages=TestJsonTree.parse(result.decodeToString()).field("pages")
            val expected=pattern.replace("@A@",pages.item(0).string("reference_id")).replace("@B@",pages.item(1).string("reference_id"))
            assertArrayEquals(expected.encodeToByteArray(),result)
        }
    }
    @Test fun copyRefusesMissingDuplicateOrUnsupportedPageIdentityRatherThanInventingTargets() {
        for(source in listOf(
            """{"format":"xnote","version":1,"pages":[{}]}""",
            """{"format":"xnote","version":1,"pages":[{"reference_id":"$a"},{"reference_id":"$a"}]}""",
            """{"format":"xnote","version":1,"pages":[]}""",
            """{"format":"xcanvas","version":1,"items":[]}"""
        )) assertFailsWith<IllegalArgumentException> {AnchorManifest.inspectCopySource({source.encodeToByteArray().asSource()})}
    }
    @Test fun truncatedOrCancelledCopyAndOutputFailureDoNotCloseTheCallerOutput() {
        val bytes=template().replace("@A@",escaped(a)).replace("@B@",b).encodeToByteArray()
        val plan=AnchorManifest.inspectCopySource({bytes.asSource()})
        val cut=bytes.decodeToString().indexOf(escaped(a))+10
        assertFailsWith<IllegalArgumentException> {write(bytes.copyOf(cut),plan)}
        assertFailsWith<IllegalStateException> {AnchorManifest.write({bytes.asSource()},Buffer(),plan,cancelled={true})}
        var closed=false;var inputClosed=false
        val broken=object:Sink by Buffer() {
            override fun write(source:Buffer,byteCount:Long) {throw IOException("output failed")}
            override fun close() {closed=true}
        }
        assertFailsWith<IOException> {AnchorManifest.write({object:ForwardingSource(bytes.asSource()) {
            override fun close() {inputClosed=true;super.close()}
        }},broken,plan)}
        assertFalse(closed);assertTrue(inputClosed)
    }
    @Test fun cancellationDuringSkippingClosesOwnedInputButLeavesOutputOwnedByCaller() {
        var read=0;var inputClosed=false;var outputClosed=false
        val bytes="x".repeat(40000).encodeToByteArray()
        val input=object:ForwardingSource(bytes.asSource()) {
            override fun read(sink:Buffer,byteCount:Long)=super.read(sink,byteCount).also {if(it>0) read+=it.toInt()}
            override fun close() {inputClosed=true;super.close()}
        }
        val output=object:Sink by Buffer() {override fun close() {outputClosed=true}}
        val plan=AnchorManifest.Plan("xnote",listOf(AnchorManifest.Insertion(0,"new",20000)),emptyList())
        assertFailsWith<IllegalStateException> {AnchorManifest.write({input},output,plan,cancelled={read>=8192})}
        assertTrue(read in 8192 until bytes.size)
        assertTrue(inputClosed);assertFalse(outputClosed)
    }
    @Test fun invalidOverlappingOrOverflowingEditsAreRejectedBeforeOpeningInputOrWritingOutput() {
        val i=AnchorManifest.Insertion(1,"new",3)
        for(edits in listOf(listOf(i,i.copy(position=3)),listOf(i.copy(skip=-1)),listOf(i.copy(skip=Long.MAX_VALUE)),listOf(i.copy(position=-1)))) {
            var opened=false;val out=Buffer()
            assertFailsWith<IllegalArgumentException> {
                AnchorManifest.write({opened=true;"original".encodeToByteArray().asSource()},out,AnchorManifest.Plan("xnote",edits,emptyList()))
            }
            assertFalse(opened);assertEquals(0,out.size.toInt())
        }
    }
    @Test fun cancellationAfterValidationStillStopsIdentityGeneration() {
        val bytes=template().replace("@A@",a).replace("@B@",b).encodeToByteArray()
        var closed=0
        assertFailsWith<IllegalStateException> {
            AnchorManifest.inspectCopySource({object:ForwardingSource(bytes.asSource()) {
                override fun close() {closed++;super.close()}
            }},cancelled={closed>=2})
        }
        assertEquals(2,closed)
    }
}
