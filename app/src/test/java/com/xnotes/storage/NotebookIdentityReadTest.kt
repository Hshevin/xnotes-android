package com.xnotes.storage

import android.content.ContextWrapper
import android.net.Uri
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import java.io.File
import java.nio.file.Files
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
class NotebookIdentityReadTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private val page="00000000-0000-0000-0000-000000000003"
    private fun scenario(block:(NotebookStorage,File)->Unit) {
        val root=Files.createTempDirectory("notebook-read-").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val storage=NotebookStorage(context)
        try {block(storage,root)} finally {storage.registry.close();root.deleteRecursively()}
    }
    private fun bundle(root:File,id:String?=a,anchored:Boolean=false,comment:Boolean=false,
                       pages:String="""[{"reference_id":"$page","items":[]}]"""):File =
        File.createTempFile("original-",".xnote",root).also {file->
            ZipOutputStream(file.outputStream()).use {z->
                if(comment) z.setComment("commented ZIP")
                id?.let {BundleIdentity(it).write(z)}
                if(anchored) AnchorMetadata(requireNotNull(id)).write(z)
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("""{"format":"xnote","version":1,"pages":$pages}""".toByteArray());z.closeEntry()
            }
        }
    @Test fun readsLegacyAndCommentedPageBundleWithoutMigrationOrPageContractActivation() = scenario {storage,root->
        for(anchored in listOf(false,true)) {
            val input=bundle(root,anchored=anchored,comment=true);val bytes=input.readBytes();val uri=Uri.fromFile(input).toString()
            assertEquals(a,storage.identity(uri)!!.documentId)
            assertArrayEquals(bytes,input.readBytes());assertNull(storage.registry.expectationAt(uri))
            assertFalse(storage.registry.expectationForId(a).requiresPages)
        }
        val old=bundle(root,id=null,pages="[{\"items\":[]}]");val bytes=old.readBytes()
        assertNull(storage.identity(Uri.fromFile(old).toString()));assertArrayEquals(bytes,old.readBytes())
    }
    @Test fun knownLocationCannotBeReassignedOrUpgradedWhenIdentityChangedOrMissing() = scenario {storage,root->
        for(id in listOf(b,null)) {
            val input=bundle(root,id=id);val bytes=input.readBytes();val uri=Uri.fromFile(input).toString()
            storage.registry.register(a,"xnote","original",uri)
            val error=assertThrows(ReferenceContractViolation::class.java) {storage.ensure(uri,"current","xnote")}
            assertEquals(StorageMsg.REFERENCE_IDENTITY_MISMATCH,error.reason)
            assertArrayEquals(bytes,input.readBytes());assertEquals(a,storage.registry.idAt(uri));assertFalse(storage.hasRecovery(uri))
        }
    }
    @Test fun movedUuidEvidenceRefusesMissingPageMetadataWithoutWritingOrRegisteringNewLocation() = scenario {storage,root->
        val input=bundle(root);val uri=Uri.fromFile(input).toString();val bytes=input.readBytes()
        storage.registry.register(a,"xnote","original","content://example/document/old",pageReferences=true)
        storage.registry.markUnavailable("content://example/document/old")
        val error=assertThrows(ReferenceContractViolation::class.java) {storage.ensure(uri,"moved","xnote")}
        assertEquals(StorageMsg.REFERENCE_PAGE_METADATA_MISSING,error.reason)
        assertNull(storage.registry.expectationAt(uri));assertArrayEquals(bytes,input.readBytes())
    }
    @Test fun malformedPagesAreLocalizedAndNeverRegisteredOrRewritten() = scenario {storage,root->
        for(pages in listOf("[]","[{}]","[{\"reference_id\":\"$page\"},{\"reference_id\":\"$page\"}]")) {
            val input=bundle(root,anchored=true,pages=pages);val uri=Uri.fromFile(input).toString();val bytes=input.readBytes()
            val error=assertThrows(IllegalStateException::class.java) {storage.ensure(uri,"broken","xnote")}
            assertEquals(StorageText.t(StorageMsg.REFERENCE_METADATA_INVALID),error.message)
            assertNotNull(error.cause);assertNull(storage.registry.expectationAt(uri));assertArrayEquals(bytes,input.readBytes())
            assertFalse(storage.hasRecovery(uri))
        }
        assertTrue(File(root,"cache").list()!!.isEmpty())
    }
    @Test fun actualStorageReadCanBeCancelledDuringPrivateSnapshotWithoutTouchingTheSource() = scenario {storage,root->
        val input=bundle(root,anchored=true,comment=true);val uri=Uri.fromFile(input).toString();val bytes=input.readBytes()
        val cancellation=java.util.concurrent.CancellationException("source chooser closed")
        val error=assertThrows(java.util.concurrent.CancellationException::class.java) {
            storage.identity(uri) {
                if(File(root,"cache").list()!!.any {it.startsWith("bundle-source-")}) throw cancellation
            }
        }
        assertSame(cancellation,error);assertArrayEquals(bytes,input.readBytes())
        assertNull(storage.registry.expectationAt(uri));assertTrue(File(root,"cache").list()!!.isEmpty())
    }
    @Test fun pendingCandidateCannotBeAutomaticallyPublishedBeforeIdentityValidation() = scenario {storage,root->
        val input=bundle(root,anchored=true);val uri=Uri.fromFile(input).toString();val bytes=input.readBytes()
        val replacement=bundle(root,id=b)
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") {"%02x".format(it)}
        val directory=File(root,"private/migrations/$key")
        MigrationPublication(directory).stage(input,replacement)
        storage.registry.register(a,"xnote","source",uri,pageReferences=true)
        val error=assertThrows(MigrationPublication.RecoveryRequired::class.java) {storage.ensure(uri,"source","xnote")}
        assertEquals(StorageMsg.MIGRATION_RECOVERY_PENDING,error.reason)
        assertArrayEquals(bytes,input.readBytes());assertTrue(storage.hasRecovery(uri));assertEquals(a,storage.registry.idAt(uri))
        assertTrue(File(directory,"original.zip").isFile);assertTrue(File(directory,"candidate.zip").isFile)
    }
}
