package com.xnotes.pen

import android.content.ContextWrapper
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.storage.NativeReferenceReader
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.ReferenceContractViolation
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Source only until run on Android; host/JVM descriptor behavior does not replace these checks. */
class NativeReferenceReadAndroidTest {
    @Test fun commentedSourceReadsWithoutChangingBytesAndMovedContractRejectsLostMetadata() {
        val base=PenTestRunner.instance.targetContext
        val root=File(base.cacheDir,"reference-read-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}}
        val storage=NotebookStorage(context)
        try {
            val id=UUID.randomUUID().toString();val page=UUID.randomUUID().toString()
            val source=File(root,"source.xnote");val bytes=bundle(id,page);source.writeBytes(bytes)
            val uri=Uri.fromFile(source).toString()
            assertEquals(id,storage.identity(uri)!!.documentId);assertArrayEquals(bytes,source.readBytes())
            storage.registry.register(id,"xnote","original","content://example/document/original",pageReferences=true)
            source.writeBytes(bundle(id,null));val degraded=source.readBytes()
            try {storage.ensure(uri,"source","xnote");fail("must not silently recreate page references")}
            catch(_:ReferenceContractViolation) {}
            assertArrayEquals(degraded,source.readBytes());assertNull(storage.registry.expectationAt(uri))
        } finally {storage.registry.close();root.deleteRecursively()}
    }
    @Test fun nonSeekableDescriptorUsesFreshStreamFromTheBeginningAndClosesIt() {
        val context=PenTestRunner.instance.targetContext
        val root=File(context.cacheDir,"reference-pipe-${UUID.randomUUID()}").apply {mkdirs()}
        val id=UUID.randomUUID().toString();val page=UUID.randomUUID().toString();val bytes=bundle(id,page)
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        var closed=false
        try {
            val hintPipe=ParcelFileDescriptor.createPipe()
            val contentPipe=ParcelFileDescriptor.createPipe()
            val writer=executor.submit {
                ParcelFileDescriptor.AutoCloseOutputStream(contentPipe[1]).use {it.write(bytes)}
            }
            val info=NativeReferenceReader.read(root,tryHeader={
                try {hintPipe[0].use {assertEquals(-1L,it.statSize);null}} finally {hintPipe[1].close()}
            },open={object:java.io.FilterInputStream(ParcelFileDescriptor.AutoCloseInputStream(contentPipe[0])) {
                override fun close() {closed=true;super.close()}
            }}) {}
            writer.get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(id,info.header.identity!!.documentId);assertEquals(setOf(page),info.pageIds)
            assertTrue(closed);assertTrue(root.list()!!.isEmpty())
        } finally {executor.shutdownNow();root.deleteRecursively()}
    }
    private fun bundle(id:String,page:String?):ByteArray {
        val bytes=java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use {z->
            z.setComment("platform fallback fixture");BundleIdentity(id).write(z)
            page?.let {AnchorMetadata(id).write(z)}
            z.putNextEntry(ZipEntry("manifest.json"))
            val member=page?.let {"\"reference_id\":\"$it\","}.orEmpty()
            z.write("""{"format":"xnote","version":1,"pages":[{$member"items":[]}]}""".toByteArray())
            z.closeEntry()
        }
        return bytes.toByteArray()
    }
}
