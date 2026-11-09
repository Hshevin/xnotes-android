package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PreparedRecoveryPreviewTest {
    private class Fixture(val root:File) {
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val assets=File(root,"assets").apply {mkdir()};val pdfs=File(root,"pdfs").apply {mkdir()}
        val noteCodec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
        val canvasCodec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        var writes=0
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read()=error("A history preview must not open a source URI")
            override fun write(source:File) {writes++;error("A history preview must not write a source URI")}
        }})
        fun preview(snapshot:BundleSnapshot,kind:DocumentKind=DocumentKind.NOTE,check:()->Unit={})=
            PreparedRecoveryPreview.readLegacy(snapshot,kind,storage.pdfResources,noteCodec,canvasCodec,pdfs,assets,"Historical title",check)
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("readonly-recovery-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    private fun bundle(f:Fixture,kind:DocumentKind=DocumentKind.NOTE,identity:BundleIdentity?=null,pdf:ByteArray?=null,manifest:String?=null):File {
        val file=File.createTempFile("history-",".zip",f.root)
        val items="""[{"kind":"image","asset":"assets/image-000.png","rect":[0,0,20,20],"src_w":20,"src_h":20},{"kind":"future-ink","raw":{"times":[0.0,4.80],"pressure":[0.2,0.99]}}]"""
        val body=manifest ?: if(kind==DocumentKind.NOTE)
            """{"format":"xnote","version":1,"has_pdf":${pdf!=null},"pages":[{"items":$items}]}"""
        else """{"format":"xcanvas","version":1,"items":$items}"""
        ZipOutputStream(file.outputStream()).use {zip->
            identity?.write(zip)
            for((name,bytes) in listOf("manifest.json" to body.toByteArray(),"assets/image-000.png" to byteArrayOf(1,2,3),"assets/future.bin" to byteArrayOf(5,7,9))) {
                zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
            }
            pdf?.let {zip.putNextEntry(ZipEntry(BundlePdf.ENTRY));zip.write(it);zip.closeEntry()}
        }
        return file
    }
    @Test fun legacyNoIdentityNotesAndCanvasesStayUnboundAndCloseOnlyTheirOwnedAssets()=scenario {f->
        val unrelated=File(f.assets,"another-pane").apply {mkdir();File(this,"ink").writeText("keep")}
        for(kind in listOf(DocumentKind.NOTE,DocumentKind.CANVAS)) {
            val source=bundle(f,kind);val original=source.readBytes();val snapshot=BundleSnapshot.take(source,f.root);val held=snapshot.file
            val p=f.preview(snapshot,kind)
            assertEquals(kind,p.kind);assertEquals(source.length(),p.byteSize);assertEquals("Historical title",p.displayName)
            assertEquals(1,p.pageCount);assertEquals(setOf(unrelated),f.assets.listFiles()!!.filter {it==unrelated}.toSet())
            if(kind==DocumentKind.NOTE) {assertNull(p.note!!.path);assertFalse(p.note!!.dirty)}
            else {assertNull(p.canvas!!.path);assertFalse(p.canvas!!.dirty)}
            assertEquals(2,f.assets.listFiles()!!.size);p.close();p.close()
            assertFalse(held.exists());assertEquals(listOf(unrelated),f.assets.listFiles()!!.toList())
            assertArrayEquals(original,source.readBytes());assertEquals(0,f.writes)
            assertThrows(IllegalStateException::class.java) {p.prepareCopy(UUID.randomUUID().toString(),f.root)}
        }
    }
    @Test fun independentCopyUsesTheExactRawSnapshotEvenIfThePreviewModelIsChanged()=scenario {f->
        val source=bundle(f,identity=BundleIdentity());val before=BundleMigration.fingerprints(source)
        val snapshot=BundleSnapshot.take(source,f.root)
        f.preview(snapshot).use {preview->
            preview.note!!.pages.single().items.clear()
            val target=UUID.randomUUID().toString();val copy=preview.prepareCopy(target,f.root)
            try {
                assertEquals(target,BundleIdentity.read(copy.file)!!.documentId)
                assertEquals(before.filterKeys {it!=BundleIdentity.ENTRY},BundleMigration.fingerprints(copy.file).filterKeys {it!=BundleIdentity.ENTRY})
                assertEquals(before,BundleMigration.fingerprints(source));assertEquals(0,f.writes)
            } finally {copy.file.delete()}
        }
    }
    @Test fun anEmbeddedOriginalStaysLeasedUntilPreviewCloseAndIsNeverDeletedByAssetCleanup()=scenario {f->
        val source=bundle(f,pdf="%PDF-1.7 immutable original".toByteArray());val before=source.readBytes()
        val preview=f.preview(BundleSnapshot.take(source,f.root));val pdf=preview.note!!.pdfFile!!
        assertEquals(BundleMigration.hash(pdf),preview.note!!.identity.pdfResource)
        assertNull(BundleIdentity.read(source));assertArrayEquals(before,source.readBytes())
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        f.storage.pdfResources.collect();assertTrue(pdf.isFile)
        preview.close();assertTrue(pdf.isFile) // Closing never deletes a shared original directly.
        f.storage.pdfResources.collect();assertFalse(pdf.exists())
        assertArrayEquals(before,source.readBytes());assertEquals(0,f.writes)
    }
    @Test fun changedPrivateSnapshotAndMalformedFullContractsFailWithoutLeavingDecodedAssets()=scenario {f->
        val source=bundle(f);val original=source.readBytes();val snapshot=BundleSnapshot.take(source,f.root);val held=snapshot.file
        held.appendText("private corruption")
        assertThrows(Exception::class.java) {f.preview(snapshot)}
        assertFalse(held.exists());assertTrue(f.assets.listFiles()!!.isEmpty());assertArrayEquals(original,source.readBytes())
        for(raw in listOf("""{"format":"xnote","version":1,"has_pdf":true,"pages":[]}""",
            """{"format":"xnote","version":2,"pages":[]}""",
            """{"format":"xnote","version":1,"pages":[]} trailing""")) {
            val bad=bundle(f,manifest=raw);val bytes=bad.readBytes();val input=BundleSnapshot.take(bad,f.root);val inputFile=input.file
            assertThrows(Exception::class.java) {f.preview(input)}
            assertFalse(inputFile.exists());assertTrue(f.assets.listFiles()!!.isEmpty());assertArrayEquals(bytes,bad.readBytes())
        }
    }
    @Test fun cancellationAfterAssetsAreCreatedReleasesTheSnapshotAndPdfLeasesButKeepsSource()=scenario {f->
        val source=bundle(f,pdf="%PDF-1.7 cancellation".toByteArray());val before=source.readBytes()
        val input=BundleSnapshot.take(source,f.root);val inputFile=input.file;var triggered=false
        assertThrows(CancellationException::class.java) {f.preview(input) {
            val leased=f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();it.getInt(0)>0}
            if(leased && f.assets.listFiles().orEmpty().isNotEmpty()) {triggered=true;throw CancellationException("cancel preview decode")}
        }}
        assertTrue(triggered);assertFalse(inputFile.exists());assertTrue(f.assets.listFiles()!!.isEmpty())
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        f.storage.pdfResources.collect();assertTrue(f.storage.pdfResources.directory.listFiles().orEmpty().none {it.extension=="pdf"})
        assertArrayEquals(before,source.readBytes());assertEquals(0,f.writes)
    }
}
