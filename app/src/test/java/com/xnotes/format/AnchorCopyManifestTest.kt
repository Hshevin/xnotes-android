package com.xnotes.format

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnchorCopyManifestTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private fun escaped(id:String)=id.map {"\\u%04x".format(it.code)}.joinToString("")
    private fun template(padding:String="")=""" {"format":"xnote","version":1,"padding":"$padding","pages":[{"items":[{"reference_id":"$a","samples":[[-0,1.20000e+02,0.1234567890123456789]],"pages":[{"reference_id":"nested-id"}]}],"reference_\u0069d" :
        "@A@"  },{"reference_id":"@B@","items":[]}],"extension":{"pages":[{"reference_id":"$b"}]}}  """
    private fun write(bytes:ByteArray,plan:AnchorManifest.Plan,short:Boolean=false):ByteArray = ByteArrayOutputStream().also {out->
        AnchorManifest.write({if(short) object:ByteArrayInputStream(bytes) {override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(len,1))} else bytes.inputStream()},out,plan)
    }.toByteArray()
    @Test fun replacesOnlyDirectPageStringLiteralsWithDisjointIdsAndKeepsAllOtherBytes() {
        val pattern=template();val source=pattern.replace("@A@",escaped(a)).replace("@B@",b).toByteArray()
        val before=source.clone();val plan=AnchorManifest.inspectCopy({source.inputStream()})
        val result=write(source,plan);val pages=JSONObject(String(result)).getJSONArray("pages")
        val fresh=(0 until pages.length()).map {pages.getJSONObject(it).getString("reference_id")}
        assertEquals(2,fresh.toSet().size);assertTrue(fresh.none {it==a || it==b})
        assertEquals(fresh.toSet(),plan.identities)
        assertArrayEquals(pattern.replace("@A@",fresh[0]).replace("@B@",fresh[1]).toByteArray(),result)
        assertArrayEquals(result,write(source,plan));assertArrayEquals(before,source)
        assertTrue(AnchorManifest.inspect({result.inputStream()},true).insertions.isEmpty())
    }
    @Test fun copyPreservesUnicodeAndLiteralBoundariesAcrossSingleByteReads() {
        for(size in listOf(0,16100,16200,16300,16340,16380,32740)) {
            val pattern=template("x".repeat(size)+"🖊中文")
            val source=pattern.replace("@A@",escaped(a)).replace("@B@",escaped(b)).toByteArray()
            val open={object:ByteArrayInputStream(source) {override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(len,1))}}
            val plan=AnchorManifest.inspectCopy(open)
            val result=write(source,plan,true);val pages=JSONObject(String(result)).getJSONArray("pages")
            val expected=pattern.replace("@A@",pages.getJSONObject(0).getString("reference_id")).replace("@B@",pages.getJSONObject(1).getString("reference_id"))
            assertArrayEquals(expected.toByteArray(),result)
        }
    }
    @Test fun copyRefusesMissingDuplicateOrUnsupportedPageIdentityRatherThanInventingTargets() {
        for(source in listOf(
            """{"format":"xnote","version":1,"pages":[{}]}""",
            """{"format":"xnote","version":1,"pages":[{"reference_id":"$a"},{"reference_id":"$a"}]}""",
            """{"format":"xnote","version":1,"pages":[]}""",
            """{"format":"xcanvas","version":1,"items":[]}"""
        )) assertThrows(IllegalArgumentException::class.java) {AnchorManifest.inspectCopy({source.byteInputStream()})}
    }
    @Test fun truncatedOrCancelledCopyAndOutputFailureDoNotCloseTheCallerOutput() {
        val bytes=template().replace("@A@",escaped(a)).replace("@B@",b).toByteArray()
        val plan=AnchorManifest.inspectCopy({bytes.inputStream()})
        val cut=String(bytes).indexOf(escaped(a))+10
        assertThrows(IllegalArgumentException::class.java) {write(bytes.copyOf(cut),plan)}
        assertThrows(IllegalStateException::class.java) {AnchorManifest.write({bytes.inputStream()},ByteArrayOutputStream(),plan,cancelled={true})}
        var closed=false;var inputClosed=false
        val broken=object:java.io.OutputStream() {
            override fun write(b:Int) {throw IOException("output failed")}
            override fun close() {closed=true}
        }
        assertThrows(IOException::class.java) {AnchorManifest.write({object:ByteArrayInputStream(bytes) {
            override fun close() {inputClosed=true;super.close()}
        }},broken,plan)}
        assertFalse(closed);assertTrue(inputClosed)
    }
    @Test fun cancellationDuringSkippingClosesOwnedInputButLeavesOutputOwnedByCaller() {
        var read=0;var inputClosed=false;var outputClosed=false
        val bytes="x".repeat(40000).toByteArray()
        val input=object:ByteArrayInputStream(bytes) {
            override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,len).also {if(it>0) read+=it}
            override fun close() {inputClosed=true;super.close()}
        }
        val output=object:ByteArrayOutputStream() {override fun close() {outputClosed=true;super.close()}}
        val plan=AnchorManifest.Plan("xnote",listOf(AnchorManifest.Insertion(0,"new",20000)),emptySet())
        assertThrows(IllegalStateException::class.java) {AnchorManifest.write({input},output,plan,cancelled={read>=8192})}
        assertTrue(read in 8192 until bytes.size)
        assertTrue(inputClosed);assertFalse(outputClosed)
    }
    @Test fun invalidOverlappingOrOverflowingEditsAreRejectedBeforeOpeningInputOrWritingOutput() {
        val i=AnchorManifest.Insertion(1,"new",3)
        for(edits in listOf(listOf(i,i.copy(position=3)),listOf(i.copy(skip=-1)),listOf(i.copy(skip=Long.MAX_VALUE)),listOf(i.copy(position=-1)))) {
            var opened=false;val out=ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                AnchorManifest.write({opened=true;"original".byteInputStream()},out,AnchorManifest.Plan("xnote",edits,emptySet()))
            }
            assertFalse(opened);assertEquals(0,out.size())
        }
    }
    @Test fun cancellationAfterValidationStillStopsIdentityGeneration() {
        val bytes=template().replace("@A@",a).replace("@B@",b).toByteArray()
        var closed=0
        assertThrows(IllegalStateException::class.java) {
            AnchorManifest.inspectCopy({object:ByteArrayInputStream(bytes) {
                override fun close() {closed++;super.close()}
            }},cancelled={closed>=2})
        }
        assertEquals(2,closed)
    }
}
