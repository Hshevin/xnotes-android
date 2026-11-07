package com.xnotes.pen

import android.content.ContextWrapper
import android.provider.DocumentsContract
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Real owned provider/PFD/SQLite boundary. Compile-only until explicitly run on Android. */
class NativeStorageSaveAndroidTest {
    @Test fun ownedSaveAndRegistryFailureRetryKeepPageContractWithoutLosingRecovery() {
        val base=PenTestRunner.instance.targetContext
        val key="native-save-test-${UUID.randomUUID()}"
        val folder=File(AppStorageDocumentsProvider.rootDir(base),key).apply {mkdir()}
        val privateRoot=File(base.cacheDir,key).apply {mkdir()}
        val context=object:ContextWrapper(base) {override fun getNoBackupFilesDir()=privateRoot}
        val storage=NotebookStorage(context)
        try {
            val id=UUID.randomUUID().toString();val page=UUID.randomUUID().toString()
            val current=File(folder,"source.xnote");val next=File(privateRoot,"next.xnote")
            bundle(current,id,page,"original");bundle(next,id,page,"candidate")
            val uri=DocumentsContract.buildDocumentUri(base.packageName+".documents","root/$key/source.xnote").toString()
            storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_publication BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'injected registry failure'); END")
            try {storage.publishNote(uri,next,DocumentKind.NOTE,"source.xnote");fail("must retain pending on DB failure")}
            catch(_:MigrationPublication.RecoveryRequired) {}
            assertArrayEquals(next.readBytes(),current.readBytes());assertTrue(storage.hasRecovery(uri))
            storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_publication")
            assertEquals(id,storage.ensure(uri,"source.xnote","xnote").documentId)
            assertFalse(storage.hasRecovery(uri));assertTrue(storage.registry.expectationForId(id).requiresPages)
            assertArrayEquals(next.readBytes(),current.readBytes())
        } finally {storage.registry.close();folder.deleteRecursively();privateRoot.deleteRecursively()}
    }
    private fun bundle(file:File,id:String,page:String,body:String) {
        ZipOutputStream(file.outputStream()).use {z->
            BundleIdentity(id).write(z);AnchorMetadata(id).write(z)
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[]}],"user_data":"$body"}""".toByteArray())
            z.closeEntry()
        }
    }
}
