package com.xnotes.pen

import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.ReferenceContractViolation
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual provider/atomic target/PFD/SQLite behavior; compile-only until run on Android. */
class NativeCreationAndroidTest {
    private fun scenario(block:(NotebookStorage,File,File,String)->Unit) {
        val base=PenTestRunner.instance.targetContext;val key="native-creation-${UUID.randomUUID()}"
        val folder=File(AppStorageDocumentsProvider.rootDir(base),key).apply {mkdir()}
        val private=File(base.cacheDir,key).apply {mkdir()}
        val context=object:ContextWrapper(base) {override fun getNoBackupFilesDir()=private}
        val storage=NotebookStorage(context)
        try {
            val parent=DocumentsContract.buildDocumentUri(base.packageName+".documents","root/$key")
            val uri=requireNotNull(DocumentsContract.createDocument(base.contentResolver,parent,"application/octet-stream","created.xnote"))
            val target=requireNotNull(storage.ownedFile(uri.toString()))
            assertTrue(target.exists());assertEquals(0L,target.length())
            block(storage,target,private,uri.toString())
        } finally {storage.registry.close();folder.deleteRecursively();private.deleteRecursively()}
    }
    @Test fun providerCreatedEmptyTargetCanPublishAndReopenItsExactPageContract()=scenario {storage,current,private,uri->
        val intent=storage.beginCreation(DocumentKind.NOTE);val permit=storage.prepareNewTarget(intent,uri)
        val candidate=File(private,"candidate");val page=UUID.randomUUID().toString();bundle(candidate,intent.documentId,page)
        val receipt=storage.publishCreated(uri,candidate,permit,"created.xnote")
        assertEquals(BundleMigration.hash(current),receipt.sha256);assertEquals(intent.documentId,receipt.documentId)
        assertTrue(storage.registry.expectationForId(intent.documentId).requiresPages);assertFalse(storage.hasRecovery(uri))
        storage.openSource(uri,DocumentKind.NOTE).use {source->
            val document=source.file.inputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).read(it)}
            source.verify(document);assertEquals(page,document.pages.single().referenceIdentity.id)
        }
    }
    @Test fun providerReuseIsNotPermissionToTruncateAFile()=scenario {storage,current,_,uri->
        current.writeText("existing content")
        val intent=storage.beginCreation(DocumentKind.NOTE)
        val error=try {storage.prepareNewTarget(intent,uri);null} catch(e:ReferenceContractViolation) {e}
        assertNotNull(error);assertEquals("existing content",current.readText());assertFalse(storage.hasRecovery(uri))
    }
    @Test fun failedIndexCommitRequiresExplicitCreationCompletionWithTheRetainedCandidate()=scenario {storage,current,private,uri->
        val intent=storage.beginCreation(DocumentKind.NOTE);val permit=storage.prepareNewTarget(intent,uri)
        val candidate=File(private,"candidate");bundle(candidate,intent.documentId,UUID.randomUUID().toString());val expected=candidate.readBytes()
        storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_creation BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'test DB failure'); END")
        val failure=try {storage.publishCreated(uri,candidate,permit,"created");null} catch(e:MigrationPublication.RecoveryRequired) {e}
        assertNotNull(failure);assertTrue(storage.hasRecovery(uri));assertArrayEquals(expected,current.readBytes())
        for(action in listOf<()->Unit>({storage.ensure(uri,"created","xnote")},{storage.restore(uri)},{storage.keepCurrent(uri)})) {
            val rejected=try {action();false} catch(_:MigrationPublication.RecoveryRequired) {true}
            assertTrue(rejected);assertTrue(storage.hasRecovery(uri));assertArrayEquals(expected,current.readBytes())
        }
        storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_creation");candidate.writeText("temporary file no longer usable")
        val parsed=Uri.parse(uri);val documentId=DocumentsContract.getDocumentId(parsed)
        val tree=DocumentsContract.buildTreeDocumentUri(parsed.authority,documentId.substringBeforeLast('/'))
        val alias=DocumentsContract.buildDocumentUriUsingTree(tree,documentId).toString()
        val receipt=storage.completeCreation(alias,"created")
        assertEquals(BundleMigration.hash(current),receipt.sha256);assertArrayEquals(expected,current.readBytes());assertFalse(storage.hasRecovery(uri))
    }
    private fun bundle(file:File,id:String,page:String) {
        ZipOutputStream(file.outputStream()).use {zip->
            BundleIdentity(id).write(zip);AnchorMetadata(id).write(zip)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"xnote","version":1,"has_pdf":false,"pages":[{"reference_id":"$page","items":[]}]}""".toByteArray());zip.closeEntry()
        }
    }
}
