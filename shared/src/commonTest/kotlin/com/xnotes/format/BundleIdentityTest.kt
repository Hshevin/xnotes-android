package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.openRead
import okio.*
import kotlin.test.assertFailsWith
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.snapshot
import com.xnotes.core.testkit.*
import kotlin.test.Test

class BundleIdentityTest : BundleTestFiles() {
    @Test fun pagedIdentitySurvivesSaveReadAndAutosaveSnapshot() {
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = Document.blank()
        val out = Buffer(); codec.write(doc.snapshot(), out)
        val loaded = codec.read(out.bytes().asSource())
        assertEquals(doc.identity, loaded.identity)
        loaded.dirty = true
        val next = Buffer(); codec.write(loaded.snapshot(), next)
        assertEquals(doc.identity, codec.read(next.bytes().asSource()).identity)
    }
    @Test fun canvasIdentitySurvivesSaveReadAndAutosaveSnapshot() {
        val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = InfiniteDocument()
        val out = Buffer(); codec.write(doc.snapshotForWrite(), out)
        val loaded = codec.read(out.bytes().asSource())
        assertEquals(doc.identity, loaded.identity)
        assertEquals(doc.identity, loaded.snapshotForWrite().identity)
    }
    @Test fun newlyCreatedNotesHaveIndependentIdentities() {
        assertNotEquals(Document().identity.documentId, Document().identity.documentId)
        assertNotEquals(InfiniteDocument().identity.documentId, InfiniteDocument().identity.documentId)
    }
}
