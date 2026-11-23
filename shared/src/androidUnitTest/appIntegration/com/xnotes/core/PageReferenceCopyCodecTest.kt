package com.xnotes.core

import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PageReferenceCopyCodecTest {
    private val target="00000000-0000-4000-8000-000000000002"
    private val third="00000000-0000-4000-8000-000000000003"
    private fun note(count:Int=2)=Document.blank(count).also {it.enableReferenceAnchors()}
    private fun ids(doc:Document)=doc.pages.map {it.referenceIdentity.id}
    @Test fun codecRoundTripKeepsBothFilesIndependentAndReadPagesCarryUndoProvenance() {
        val codec=com.xnotes.format.DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
        fun bytes(doc:Document)=java.io.ByteArrayOutputStream().also {codec.write(doc,it)}.toByteArray()
        val original=note(3);val oldIds=ids(original);val originalBytes=bytes(original.snapshot())
        val loaded=codec.read(originalBytes.inputStream());val removed=loaded.pages.removeAt(0)
        val copy=PageReferenceCopy.prepare(loaded,target);val candidate=copy.project(loaded.snapshot())
        val copyBytes=bytes(candidate);copy.adopt(loaded);loaded.pages.add(0,removed)
        val restored=loaded.snapshot();assertNotEquals(oldIds[0],restored.pages[0].referenceIdentity.id)
        assertEquals(oldIds,ids(codec.read(originalBytes.inputStream())))
        val reopened=codec.read(copyBytes.inputStream());assertEquals(target,reopened.identity.documentId)
        assertEquals(ids(candidate),ids(reopened));assertTrue(ids(reopened).toSet().intersect(oldIds.toSet()).isEmpty())
        val cd=PageReferenceCopy.prepare(loaded,third)
        assertEquals(ids(cd.project(candidate)),ids(cd.project(restored)).drop(1))
    }
}
