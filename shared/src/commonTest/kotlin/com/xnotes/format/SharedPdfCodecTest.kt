package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.openRead
import okio.*
import kotlin.test.assertFailsWith
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.testkit.*
import kotlin.test.Test

class SharedPdfCodecTest : BundleTestFiles() {
    @Test fun persistentSinkReceivesExactPdfAndIdentitySurvivesStream() {
        val dir=testDirectory("pdf-sink")
        try {
            val bytes="%PDF-1.7\noriginal source\u0000\u0001".encodeToByteArray()
            val source=dir.child("source.pdf").apply { setBytes(bytes) }
            val shared=dir.child("shared.pdf")
            val doc=Document.blank().apply { pdfFile=source }
            val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
            val out=Buffer(); codec.write(doc,out)
            repeat(2) {
                val loaded=codec.read(out.bytes().asSource(),pdfDir=dir,pdfStore={ stream -> shared.fileSink().use { stream.buffer().readAll(it) }; shared })
                assertEquals(shared,loaded.pdfFile)
                assertEquals(doc.identity,loaded.identity)
                assertArrayEquals(bytes,shared.fileBytes()); assertArrayEquals(bytes,source.fileBytes())
            }
        } finally { dir.deleteTree() }
    }
}
