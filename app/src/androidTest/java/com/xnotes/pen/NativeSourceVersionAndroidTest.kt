package com.xnotes.pen

import android.content.ContextWrapper
import android.provider.DocumentsContract
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.ReferenceContractViolation
import com.xnotes.storage.StorageMsg
import com.xnotes.storage.NativeSourceSnapshot.ReferenceCatalog.Pages
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual provider/PFD/SQLite/managed-PDF contracts. Compile-only until explicitly run on Android. */
class NativeSourceVersionAndroidTest {
    private fun scenario(block:(NotebookStorage,File,File,String)->Unit) {
        val base=PenTestRunner.instance.targetContext
        val key="native-version-${UUID.randomUUID()}"
        val folder=File(AppStorageDocumentsProvider.rootDir(base),key).apply {mkdir()}
        val private=File(base.cacheDir,key).apply {mkdir()}
        val context=object:ContextWrapper(base) {override fun getNoBackupFilesDir()=private}
        val storage=NotebookStorage(context)
        val current=File(folder,"source.xnote")
        val uri=DocumentsContract.buildDocumentUri(base.packageName+".documents","root/$key/source.xnote").toString()
        try {block(storage,current,private,uri)} finally {storage.registry.close();folder.deleteRecursively();private.deleteRecursively()}
    }
    @Test fun sourceSnapshotAndExpectedPublicationUseTheActualReadAndPublishedBytes()=scenario {storage,current,private,uri->
        val id=UUID.randomUUID().toString();val page=UUID.randomUUID().toString()
        bundle(current,id,page,"original");val original=current.readBytes()
        val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val expected=storage.openSource(uri,DocumentKind.NOTE).use {source->
            val doc=source.file.inputStream().use {codec.read(it)};source.verify(doc);source.version
        }
        val next=File(private,"candidate.xnote");bundle(next,id,page,"my edit")
        bundle(current,id,page,"external edit");val foreign=current.readBytes()
        val failure=try {storage.publishExpected(uri,next,expected,"source");null} catch(e:ReferenceContractViolation) {e}
        assertEquals(StorageMsg.REFERENCE_SOURCE_CHANGED,failure?.reason)
        assertArrayEquals(foreign,current.readBytes());assertFalse(storage.hasRecovery(uri))
        current.writeBytes(original)
        val published=storage.publishExpected(uri,next,expected,"source")
        assertEquals(BundleMigration.hash(current),published.sha256);assertArrayEquals(next.readBytes(),current.readBytes())
        assertTrue(storage.registry.expectationForId(id).requiresPages);assertFalse(storage.hasRecovery(uri))
    }
    @Test fun sourcePdfLeaseHandsOffToPersistentModelProtectionBeforeCollection()=scenario {storage,current,_,uri->
        val id=UUID.randomUUID().toString();val page=UUID.randomUUID().toString()
        val resources=storage.pdfResources;val original=resources.admit("%PDF-lease-contract".byteInputStream(),"original")
        bundle(current,id,page,"source",original.nameWithoutExtension)
        val source=storage.openSource(uri,DocumentKind.NOTE);val adoption=source.retainPdfForAdoption()
        storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        source.close();source.close();resources.collect();assertTrue(original.isFile)
        resources.pin("note:$id",setOf(original.nameWithoutExtension));adoption.close()
        resources.collect();assertTrue(original.isFile)
        resources.pin("note:$id",emptySet());storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        resources.collect();assertFalse(original.exists());assertTrue(current.exists())
    }
    @Test fun platformCodecReadsEntriesAfterAClosingPdfStoreCallback()=scenario {storage,current,privateRoot,uri->
        val id=UUID.randomUUID().toString();val page=UUID.randomUUID().toString();val pdf="%PDF-stream-contract".toByteArray()
        val bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
        val png=java.io.ByteArrayOutputStream().also {try {assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))} finally {bitmap.recycle()}}.toByteArray()
        ZipOutputStream(current.outputStream()).use {zip->
            zip.putNextEntry(ZipEntry(com.xnotes.format.BundlePdf.ENTRY));zip.write(pdf);zip.closeEntry()
            BundleIdentity(id).write(zip);AnchorMetadata(id).write(zip)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[{"kind":"image","asset":"assets/image-after.png","rect":[0,0,2,2],"src_w":2,"src_h":2}]}]}""".toByteArray());zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/image-after.png"));zip.write(png);zip.closeEntry()
        }
        val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        storage.openSource(uri,DocumentKind.NOTE).use {source->
            val doc=source.file.inputStream().use {codec.read(it,pdfDir=privateRoot,imageDir=privateRoot,pdfStore={source.admitOriginalPdf(it,"original")})}
            source.verify(doc);assertEquals(page,doc.pages.single().referenceIdentity.id)
            assertTrue(doc.pages.single().items.single() is com.xnotes.core.model.ImageItem)
            assertArrayEquals(pdf,doc.pdfFile!!.readBytes())
            storage.pdfResources.pin("note:$id",setOf(doc.pdfFile!!.nameWithoutExtension))
        }
    }
    private fun bundle(file:File,id:String,page:String,body:String,pdf:String?=null) {
        ZipOutputStream(file.outputStream()).use {zip->
            BundleIdentity(id,pdf,externalPdf=pdf!=null).write(zip);AnchorMetadata(id).write(zip)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"xnote","version":1,"has_pdf":${pdf!=null},"pages":[{"reference_id":"$page","items":[]}],"user_data":"$body"}""".toByteArray());zip.closeEntry()
        }
    }
    @Test fun providerCatalogPreservesObservedPageOrderAndNeverActivatesLegacyIdentity()=scenario {storage,current,_,uri->
        val id=UUID.randomUUID().toString();val first="00000000-0000-4000-8000-000000000099";val second="00000000-0000-4000-8000-000000000003"
        fun write(pages:List<String>,anchored:Boolean=true) {
            ZipOutputStream(current.outputStream()).use {zip->
                BundleIdentity(id).write(zip);if(anchored) AnchorMetadata(id).write(zip)
                val entries=pages.joinToString(",") {"""{${if(anchored) "\"reference_id\":\"$it\"," else ""}"items":[]}"""}
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write("""{"format":"xnote","version":1,"pages":[$entries]}""".toByteArray());zip.closeEntry()
            }
        }
        write(listOf(first,second));val bytes=current.readBytes();val original=storage.referenceCatalog(uri,DocumentKind.NOTE)
        assertEquals(listOf(first,second),(original.pages as Pages.Available).orderedIds)
        assertEquals(BundleMigration.hash(current),original.sha256);assertArrayEquals(bytes,current.readBytes())
        write(listOf(second,first));val reordered=storage.referenceCatalog(uri,DocumentKind.NOTE)
        assertEquals(listOf(second,first),(reordered.pages as Pages.Available).orderedIds)
        assertNotEquals(original.sha256,reordered.sha256);assertEquals(listOf(first,second),(original.pages as Pages.Available).orderedIds)
        write(listOf(first),anchored=false);val legacy=current.readBytes()
        assertSame(Pages.NeedsActivation,storage.referenceCatalog(uri,DocumentKind.NOTE).pages)
        assertArrayEquals(legacy,current.readBytes());assertTrue(storage.registry.locations(id).isEmpty())
    }
    @Test fun checkedPdfLeaseCanCancelDuringFirstHashWithoutKeepingAnActiveLease()=scenario {storage,_,_,_->
        val resources=storage.pdfResources;val original=resources.admit(ByteArray(400000) {42}.inputStream(),"original")
        val bytes=original.readBytes();var checks=0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            resources.holdResolved(setOf(original.nameWithoutExtension)) {if(++checks==4) throw java.util.concurrent.CancellationException()}.close()
        }
        val leases=storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();it.getInt(0)}
        assertEquals(0,leases);assertArrayEquals(bytes,original.readBytes())
        resources.holdResolved(setOf(original.nameWithoutExtension)) {}.close()
    }
}
