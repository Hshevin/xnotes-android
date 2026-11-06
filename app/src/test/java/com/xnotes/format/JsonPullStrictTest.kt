package com.xnotes.format

import java.io.Reader
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class JsonPullStrictTest {
    private fun strict(value:String,limit:Int=1_048_576) {
        val p=JsonPull(StringReader(value),strict=true,tokenLimit=limit)
        p.skipValue();assertEquals(JsonPull.Token.END_DOCUMENT,p.peek())
    }
    private fun rejects(value:String,limit:Int=1_048_576) {
        try {strict(value,limit);fail("must reject malformed or oversized JSON")} catch(_:JsonPullException) {}
    }
    @Test fun preservesValidNumericLexemesWithoutRequiringFiniteDoubleConversion() {
        strict("[-0,1.0000,1e+02,-2.5E-8,1e99999]")
    }
    @Test fun rejectsMalformedNumbersAndTrailingValues() {
        listOf("01","1.","1e","1e+","--1","1-2","1.2.3","+1","NaN","[1] false","{}{}","null x").forEach {rejects(it)}
    }
    @Test fun rejectsRawControlCharactersButKeepsEscapedOnes() {
        rejects("\"a\nb\"");rejects("\"a\u0000b\"");strict("\"a\\n\\u0000b\"")
    }
    @Test fun tokenLimitAndDepthAreEnforcedOnlyInStrictMode() {
        rejects("\"123456789\"",8);rejects("123456789",8)
        val deep="[".repeat(257)+"0"+"]".repeat(257);rejects(deep)
        val default=JsonPull(StringReader("[01,\"line\ntext\"]"))
        default.skipValue() // unchanged legacy forgiving path
    }
    @Test fun consumedPositionExcludesReadAheadAndSurvivesOneCharacterReadsAndRefills() {
        for(chunk in listOf(1,7,16384,32768)) {
            val text="{\"padding\":\""+"🖊中文".repeat(7000)+"\",\"end\":{}}   "
            val reader=object:Reader() {
                var at=0
                override fun read(buffer:CharArray,off:Int,len:Int):Int {
                    if(at==text.length) return -1
                    val n=minOf(chunk,len,text.length-at);text.toCharArray(buffer,off,at,at+n);at+=n;return n
                }
                override fun close() {}
            }
            val p=JsonPull(reader,strict=true)
            p.beginObject();assertEquals("padding",p.nextName());p.skipValue();assertEquals("end",p.nextName())
            p.beginObject();assertFalse(p.hasNext())
            assertEquals(text.indexOf("}}").toLong(),p.position)
            p.endObject();assertFalse(p.hasNext());assertEquals(text.indexOf("}}").toLong()+1,p.position)
            p.endObject();assertEquals(JsonPull.Token.END_DOCUMENT,p.peek());assertEquals(text.length.toLong(),p.position)
        }
    }
}
