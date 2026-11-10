package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Platform JSON/null behavior, kept separately from JVM JSON dependencies. */
class ProtectedNoteMetadataAndroidTest {
    private fun valid()=JSONObject().put("dirty",false).put("zoom",1.0).put("scrollX",0)
        .put("scrollY",0).put("zoomLocked",false)
    @Test fun anActualAndroidSidecarWithoutOptionalHintsIsValid() {
        assertEquals(SessionStore.Metadata(null,null,false,1.0,0.0,0.0,false,ViewOverrides()),
            ProtectedNoteMetadata.parse(valid().toString().toByteArray()))
    }
    @Test fun platformJsonNullAndStringCoercionsDoNotGrantAValidCache() {
        for(key in listOf("dirty","zoom","scrollX","scrollY","zoomLocked","path","displayName","rotation"))
            assertThrows(Exception::class.java) {ProtectedNoteMetadata.parse(valid().put(key,JSONObject.NULL).toString().toByteArray())}
        assertThrows(Exception::class.java) {ProtectedNoteMetadata.parse(valid().put("dirty","false").toString().toByteArray())}
    }
    @Test fun duplicateAndUnknownFieldsCannotBeHiddenByPlatformJsonParsing() {
        val wire=valid().toString()
        for(suffix in listOf(",\"dirty\":true}",",\"future\":{\"version\":2}}",",\"displayName\":\"\\u００４１\"}"))
            assertThrows(Exception::class.java) {ProtectedNoteMetadata.parse((wire.dropLast(1)+suffix).toByteArray())}
    }
    @Test fun malformedUtf8AndUnpairedSurrogatesPreserveAnInvalidClassification() {
        assertThrows(Exception::class.java) {ProtectedNoteMetadata.parse(byteArrayOf(0xc3.toByte(),0x28))}
        val raw=valid().toString().dropLast(1)+",\"displayName\":\"\\ud800\"}"
        assertThrows(Exception::class.java) {ProtectedNoteMetadata.parse(raw.toByteArray())}
    }
}
