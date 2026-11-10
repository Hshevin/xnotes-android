package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import org.junit.Assert.*
import org.junit.Test

class ProtectedNoteMetadataTest {
    private val minimal="""{"dirty":false,"zoom":0,"scrollX":0,"scrollY":0,"zoomLocked":false}"""
    @Test fun actualDefaultSidecarNeedsNeitherPathNameNorOverrides() {
        assertEquals(SessionStore.Metadata(null,null,false,0.0,0.0,0.0,false,ViewOverrides()),
            ProtectedNoteMetadata.parse(minimal.toByteArray()))
    }
    @Test fun strictTypesAndMissingRequiredFieldsAreRejected() {
        for(raw in listOf(minimal.replace("false","\"false\""),minimal.replace("\"zoom\":0","\"zoom\":\"1\""),
            minimal.replace("\"dirty\":false,",""),minimal.replace("\"zoom\":0","\"zoom\":null"),
            minimal.dropLast(1)+",\"path\":null}",minimal.dropLast(1)+",\"zoom\":2}"))
            assertThrows(Exception::class.java) {ProtectedNoteMetadata.parse(raw.toByteArray())}
    }
    @Test fun validFlatViewOverridesRetainExactValues() {
        val data=ProtectedNoteMetadata.parse((minimal.dropLast(1)+
            ",\"path\":\"content://notes/original\",\"displayName\":\"Recovered\",\"contrast\":132,\"rotation\":270,\"freeMovement\":true}").toByteArray())
        assertEquals("content://notes/original",data.path);assertEquals("Recovered",data.displayName)
        assertEquals(132,data.viewOverrides.contrast);assertEquals(270,data.viewOverrides.rotation)
        assertEquals(true,data.viewOverrides.freeMovement)
    }
    @Test fun budgetsUtf8UnpairedSurrogatesAndTrailingInputAreRejected() {
        for(bytes in listOf(ByteArray(ProtectedNoteMetadata.MAX_BYTES+1) {' '.code.toByte()},
            byteArrayOf(0xc3.toByte(),0x28),
            (minimal.dropLast(1)+",\"displayName\":\"\\ud800\"}").toByteArray(),
            (minimal+" {}").toByteArray()))
            assertThrows(Exception::class.java) {ProtectedNoteMetadata.parse(bytes)}
    }
    @Test fun unknownNestedOrOutOfRangeValuesAreNotCoerced() {
        for(field in listOf("\"future\":{}","\"zoom\":-1","\"scrollX\":1e999","\"rotation\":45",
            "\"contrast\":201","\"invert\":-1","\"mode\":\"future\"","\"multiply\":\"#00000000\"",
            "\"keepImages\":null","\"path\":[]")) {
            val key=field.substringBefore(':')
            val raw=if(key=="\"zoom\"") minimal.replace("\"zoom\":0",field)
                else if(key=="\"scrollX\"") minimal.replace("\"scrollX\":0",field)
                else minimal.dropLast(1)+","+field+"}"
            assertThrows(Exception::class.java) {ProtectedNoteMetadata.parse(raw.toByteArray())}
        }
    }
}
