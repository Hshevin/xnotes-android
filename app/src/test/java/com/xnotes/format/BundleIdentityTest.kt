package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.snapshot
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class BundleIdentityTest {
    @Test fun pagedIdentitySurvivesSaveReadAndAutosaveSnapshot() {
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = Document.blank()
        val out = ByteArrayOutputStream(); codec.write(doc.snapshot(), out)
        val loaded = codec.read(out.toByteArray().inputStream())
        assertEquals(doc.identity, loaded.identity)
        loaded.dirty = true
        val next = ByteArrayOutputStream(); codec.write(loaded.snapshot(), next)
        assertEquals(doc.identity, codec.read(next.toByteArray().inputStream()).identity)
    }
    @Test fun canvasIdentitySurvivesSaveReadAndAutosaveSnapshot() {
        val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = InfiniteDocument()
        val out = ByteArrayOutputStream(); codec.write(doc.snapshotForWrite(), out)
        val loaded = codec.read(out.toByteArray().inputStream())
        assertEquals(doc.identity, loaded.identity)
        assertEquals(doc.identity, loaded.snapshotForWrite().identity)
    }
    @Test fun newlyCreatedNotesHaveIndependentIdentities() {
        assertNotEquals(Document().identity.documentId, Document().identity.documentId)
        assertNotEquals(InfiniteDocument().identity.documentId, InfiniteDocument().identity.documentId)
    }
}
