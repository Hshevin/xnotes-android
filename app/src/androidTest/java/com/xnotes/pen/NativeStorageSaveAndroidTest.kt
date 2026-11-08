package com.xnotes.pen

import android.content.ContextWrapper
import android.provider.DocumentsContract
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
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
    @Test fun reopeningAnUnacknowledgedHandoffDoesNotWriteTheOwnedProvider() {
        val base=PenTestRunner.instance.targetContext;val key="handoff-gate-${UUID.randomUUID()}"
        val folder=File(AppStorageDocumentsProvider.rootDir(base),key).apply {mkdir()}
        val privateRoot=File(base.cacheDir,key).apply {mkdir()}
        val context=object:ContextWrapper(base) {override fun getNoBackupFilesDir()=privateRoot}
        val storage=NotebookStorage(context)
        try {
            val id=UUID.randomUUID().toString();val page=UUID.randomUUID().toString()
            val current=File(folder,"source.xnote");val next=File(privateRoot,"candidate.xnote")
            bundle(current,id,page,"A");bundle(next,id,page,"B");val original=current.readBytes()
            val uri=DocumentsContract.buildDocumentUri(base.packageName+".documents","root/$key/source.xnote").toString()
            val location=storage.registry.locationKey(uri)
            val slot=java.security.MessageDigest.getInstance("SHA-256").digest(location.toByteArray()).joinToString("") {"%02x".format(it)}
            val dir=File(privateRoot,"migrations/$slot")
            MigrationPublication(dir).stage(current,next,MigrationPublication.Purpose.SAVE_NOTE,
                expectedOriginalHash=BundleMigration.hash(current),expectedCandidateHash=BundleMigration.hash(next),handoffId=UUID.randomUUID().toString())
            val retained=dir.listFiles().orEmpty().associate {it.name to it.readBytes().toList()}
            storage.registry.close()
            val reopened=NotebookStorage(context)
            try {
                val failure=try {reopened.ensure(uri,"source","xnote");null} catch(error:MigrationPublication.RecoveryRequired) {error}
                assertNotNull(failure);assertEquals(com.xnotes.storage.StorageMsg.MIGRATION_RECOVERY_PENDING,failure?.reason)
                assertArrayEquals(original,current.readBytes());assertTrue(reopened.hasRecovery(uri))
                assertEquals(retained,dir.listFiles().orEmpty().associate {it.name to it.readBytes().toList()})
            } finally {reopened.registry.close()}
        } finally {storage.registry.close();folder.deleteRecursively();privateRoot.deleteRecursively()}
    }
    @Test fun keepingAnAlreadyWrittenCandidateDoesNotRestoreTheOldProviderBytes() {
        val base=PenTestRunner.instance.targetContext;val key="keep-written-${UUID.randomUUID()}"
        val folder=File(AppStorageDocumentsProvider.rootDir(base),key).apply {mkdir()}
        val privateRoot=File(base.cacheDir,key).apply {mkdir()}
        val context=object:ContextWrapper(base) {override fun getNoBackupFilesDir()=privateRoot}
        val storage=NotebookStorage(context)
        try {
            val id=UUID.randomUUID().toString();val page=UUID.randomUUID().toString()
            val current=File(folder,"source.xnote");val next=File(privateRoot,"candidate.xnote")
            bundle(current,id,page,"A");bundle(next,id,page,"written B");val original=current.readBytes()
            val uri=DocumentsContract.buildDocumentUri(base.packageName+".documents","root/$key/source.xnote").toString()
            storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_keep_index BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'index failure'); END")
            try {storage.publishNote(uri,next,DocumentKind.NOTE,"source");fail("must retain recovery")}
            catch(_:MigrationPublication.RecoveryRequired) {}
            assertFalse(original.contentEquals(current.readBytes()));assertArrayEquals(next.readBytes(),current.readBytes());assertTrue(storage.hasRecovery(uri))
            storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_keep_index")
            val result=storage.keepWrittenSave(uri,DocumentKind.NOTE)
            assertEquals(BundleMigration.hash(current),result.sha256);assertEquals(id,result.documentId)
            assertFalse(storage.hasRecovery(uri));assertArrayEquals(next.readBytes(),current.readBytes());assertTrue(storage.registry.expectationForId(id).requiresPages)
        } finally {storage.registry.close();folder.deleteRecursively();privateRoot.deleteRecursively()}
    }
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
