package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SharedPdfCodecTest {
    @Test fun persistentSinkReceivesExactPdfAndIdentitySurvivesStream() {
        val dir=Files.createTempDirectory("pdf-sink").toFile()
        try {
            val bytes="%PDF-1.7\noriginal source\u0000\u0001".toByteArray()
            val source=File(dir,"source.pdf").apply { writeBytes(bytes) }
            val shared=File(dir,"shared.pdf")
            val doc=Document.blank().apply { pdfFile=source }
            val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
            val out=ByteArrayOutputStream(); codec.write(doc,out)
            repeat(2) {
                val loaded=codec.read(out.toByteArray().inputStream(),pdfDir=dir,pdfStore={ stream -> shared.outputStream().use { stream.copyTo(it) }; shared })
                assertEquals(shared,loaded.pdfFile)
                assertEquals(doc.identity,loaded.identity)
                assertArrayEquals(bytes,shared.readBytes()); assertArrayEquals(bytes,source.readBytes())
            }
        } finally { dir.deleteRecursively() }
    }
}
