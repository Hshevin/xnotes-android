package com.xnotes.platform

import org.junit.Assert.*
import org.junit.Test

class ProtectedCanvasMetadataTest {
    @Test fun actualCanvasSidecarRoundTripsItsRequiredBooleanAndOptionalStrings() {
        assertEquals(CanvasSessionStore.Metadata(null,null,true),ProtectedCanvasMetadata.parse("""{"dirty":true}""".toByteArray()))
        assertEquals(CanvasSessionStore.Metadata("content://canvas/original","Canvas",false),ProtectedCanvasMetadata.parse(
            """{"dirty":false,"path":"content://canvas/original","display_name":"Canvas"}""".toByteArray()))
    }
    @Test fun missingNullWrongTypesDuplicatesAndUnknownFieldsAreRejected() {
        for(raw in listOf("{}","""{"dirty":null}""","""{"dirty":"false"}""","""{"dirty":0}""",
            """{"dirty":false,"path":null}""","""{"dirty":false,"display_name":false}""",
            """{"dirty":false,"dirty":true}""","""{"dirty":false,"d\u0069rty":true}""",
            """{"dirty":false,"displayName":"wrong schema"}""","""{"dirty":false,"future":{}}"""))
            assertThrows("Rejected Canvas metadata: $raw",Exception::class.java) {ProtectedCanvasMetadata.parse(raw.toByteArray())}
    }
    @Test fun androidLenientSyntaxAndTrailingDocumentsAreRejectedBeforeCoercion() {
        for(raw in listOf("""{/*comment*/"dirty":false}""","""{dirty:false}""","""{'dirty':false}""",
            """{"dirty":false,}""","""{"dirty":NaN}""","""{"dirty":false} {}"""))
            assertThrows(Exception::class.java) {ProtectedCanvasMetadata.parse(raw.toByteArray())}
    }
    @Test fun byteBudgetMalformedUtf8LoneSurrogatesAndNestedValuesAreRejected() {
        for(bytes in listOf(ByteArray(ProtectedCanvasMetadata.MAX_BYTES+1) {' '.code.toByte()},byteArrayOf(0xc3.toByte(),0x28),
            """{"dirty":false,"path":"\ud800"}""".toByteArray(),
            ("{\"dirty\":"+"[".repeat(2048)+"false"+"]".repeat(2048)+"}").toByteArray()))
            assertThrows(Exception::class.java) {ProtectedCanvasMetadata.parse(bytes)}
    }
}
