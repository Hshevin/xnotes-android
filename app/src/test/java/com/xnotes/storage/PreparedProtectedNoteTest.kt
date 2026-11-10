package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.DocumentCodec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PreparedProtectedNoteTest {
    private class Fixture(val root:File) {
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val assets=File(root,"assets").apply {mkdir()}
        val pdfs=File(root,"pdfs").apply {mkdir()}
        val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
        val storage=NotebookStorage(context,targetOverride={error("A cache must not open an original URI")})
        fun bundle(unknown:Boolean=false,pdf:ByteArray?=null):File=File(root,"cache.xnote").also {file->
            ZipOutputStream(file.outputStream()).use {zip->
                zip.putNextEntry(ZipEntry("manifest.json"))
                val item=if(unknown) """{"kind":"future-item"}""" else
                    """{"kind":"image","asset":"assets/image-000.png","rect":[0,0,20,20],"src_w":20,"src_h":20}"""
                zip.write("""{"format":"xnote","version":1,"has_pdf":${pdf!=null},"pages":[{"width":100,"height":100,"items":[$item]}]}""".toByteArray())
                zip.closeEntry();zip.putNextEntry(ZipEntry("assets/image-000.png"));zip.write(byteArrayOf(1,2,3));zip.closeEntry()
                if(pdf!=null) {zip.putNextEntry(ZipEntry(com.xnotes.format.BundlePdf.ENTRY));zip.write(pdf);zip.closeEntry()}
            }
        }
        fun read(snapshot:BundleSnapshot,check:()->Unit={})=PreparedProtectedNote.read(snapshot,storage.pdfResources,
            codec,pdfs,assets,check)
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("protected-assets-").toFile()
        val fixture=Fixture(root)
        try {block(fixture)} finally {fixture.storage.registry.close();root.deleteRecursively()}
    }
    @Test fun abortedPreparationDeletesOnlyItsOwnAssetsAndSnapshot()=scenario {f->
        val unrelated=File(f.assets,"other-request").apply {mkdir();File(this,"keep").writeText("keep")}
        val source=f.bundle();val bytes=source.readBytes()
        val snapshot=BundleSnapshot.take(source,f.root);val raw=snapshot.file
        val prepared=f.read(snapshot)
        assertEquals(1,prepared.document.pages.single().items.size)
        assertEquals(2,f.assets.listFiles()!!.size)
        assertNull(prepared.document.path)
        prepared.close();prepared.close()
        assertFalse(raw.exists());assertEquals(listOf(unrelated),f.assets.listFiles()!!.toList())
        assertArrayEquals(bytes,source.readBytes())
    }
    @Test fun adoptedCloseKeepsLiveModelAssetsAndCannotAdoptAgain()=scenario {f->
        val prepared=f.read(BundleSnapshot.take(f.bundle(),f.root))
        val files=f.assets.walkTopDown().filter {it.isFile}.toList()
        assertTrue(files.isNotEmpty())
        val lifetime=prepared.adopt(prepared.document)
        prepared.close();prepared.close()
        assertTrue(files.all {it.isFile})
        assertThrows(IllegalStateException::class.java) {prepared.adopt(prepared.document)}
        lifetime.close();lifetime.close()
        assertTrue(files.all {it.isFile})
    }
    @Test fun incompleteDecodeAndChangedSnapshotCannotBeAdopted()=scenario {f->
        val source=f.bundle(unknown=true);val bytes=source.readBytes()
        val input=BundleSnapshot.take(source,f.root);val file=input.file
        assertThrows(Exception::class.java) {f.read(input)}
        assertFalse(file.exists());assertTrue(f.assets.listFiles()!!.isEmpty())
        assertArrayEquals(bytes,source.readBytes())
        val changed=BundleSnapshot.take(f.bundle(),f.root)
        changed.file.appendText("changed private body")
        assertThrows(Exception::class.java) {f.read(changed)}
        assertFalse(changed.file.exists());assertTrue(f.assets.listFiles()!!.isEmpty())
    }
    @Test fun cancelledDecodeOwnsLateProducedAssetsWithoutDeletingSource()=scenario {f->
        val source=f.bundle();val original=BundleMigration.hash(source)
        val input=BundleSnapshot.take(source,f.root);var reachedAssets=false
        assertThrows(CancellationException::class.java) {f.read(input) {
            if(f.assets.listFiles().orEmpty().isNotEmpty()) {
                reachedAssets=true;throw CancellationException("cancel after private assets exist")
            }
        }}
        assertTrue(reachedAssets);assertFalse(input.file.exists());assertTrue(f.assets.listFiles()!!.isEmpty())
        assertEquals(original,BundleMigration.hash(source))
    }
    @Test fun adoptingTransfersTheManagedPdfLeaseWithoutPinningAnUnverifiedUuid()=scenario {f->
        val source=f.bundle(pdf="%PDF-1.7 immutable original".toByteArray())
        val original=source.readBytes()
        val prepared=f.read(BundleSnapshot.take(source,f.root))
        val pdf=requireNotNull(prepared.document.pdfFile)
        assertTrue(f.storage.pdfResources.isOwned(pdf))
        assertEquals(BundleMigration.hash(pdf),prepared.document.identity.pdfResource)
        val lifetime=prepared.adopt(prepared.document)
        prepared.close()
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        f.storage.pdfResources.collect();assertTrue(pdf.isFile)
        lifetime.close();lifetime.close()
        assertTrue("Retiring a model releases a lease, it never directly deletes shared PDF",pdf.isFile)
        f.storage.pdfResources.collect();assertFalse(pdf.exists())
        assertArrayEquals(original,source.readBytes())
    }
}
