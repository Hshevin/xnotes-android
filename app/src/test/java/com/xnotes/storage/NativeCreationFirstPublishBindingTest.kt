package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared actual storage/provider fixture; deliberately has no pending-token implementation. */
internal class CreationPendingFixture(val root:File) {
    val uri="content://creation-pending/document/a"
    val alias="content://creation-pending/tree/root/document/a"
    val other="content://creation-pending/document/b"
    val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
    }
    val current=File(root,"current").apply {writeBytes(byteArrayOf())}
    var reads=0;var writes=0;var failAfterWrite=false;var partial=false
    var beforeRead:(()->Unit)?=null
    var validating:(()->Unit)?=null
    val storage=NotebookStorage(context,targetOverride={backend()},sourceValidationHook={validating?.invoke()})
    fun backend()=object:MigrationPublication.Target {
        override fun read():java.io.InputStream {reads++;beforeRead?.invoke();return current.inputStream()}
        override fun write(source:File) {
            writes++
            if(partial) {current.writeText("partial");throw IOException("provider partial write")}
            source.copyTo(current,overwrite=true)
            if(failAfterWrite) throw IOException("provider wrote the complete file then failed")
        }
    }
    val directory:File get() {
        val key=storage.registry.locationKey(uri)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
        return File(context.noBackupFilesDir,"migrations/$hash")
    }
    val marker get()=File(directory,"pending")
    fun journal()=MigrationPublication(directory)
    fun bundle(id:String,kind:DocumentKind=DocumentKind.NOTE,pdf:String?=null,body:String="mine"):File =
        File.createTempFile("creation-body-",".zip",root).also {file->ZipOutputStream(file.outputStream()).use {zip->
            BundleIdentity(id,pdf,externalPdf=pdf!=null).write(zip)
            if(kind==DocumentKind.NOTE) AnchorMetadata(id).write(zip)
            zip.putNextEntry(ZipEntry("manifest.json"))
            val content=if(kind==DocumentKind.NOTE) "\"pages\":[{\"reference_id\":\"${UUID.randomUUID()}\",\"items\":[]}]" else "\"items\":[]"
            zip.write("""{"format":"${kind.extension}","version":1,"has_pdf":${pdf!=null},$content,"body":"$body"}""".toByteArray());zip.closeEntry()
        }}
    fun failed(kind:DocumentKind=DocumentKind.NOTE,pdf:String?=null):Pair<NativeCreationIntent,File> {
        val intent=storage.beginCreation(kind);val permit=storage.prepareNewTarget(intent,uri)
        val candidate=bundle(intent.documentId,kind,pdf)
        failAfterWrite=true
        try {assertThrows(MigrationPublication.RecoveryRequired::class.java) {storage.publishCreated(uri,candidate,permit,"created")}}
        finally {failAfterWrite=false}
        assertTrue(marker.isFile)
        return intent to candidate
    }
    fun replace(id:String,kind:DocumentKind=DocumentKind.NOTE,pdf:String?=null):File {
        val candidate=bundle(id,kind,pdf,"replacement")
        val empty=File.createTempFile("empty-",".zip",root)
        check(marker.delete())
        journal().stage(empty,candidate,if(kind==DocumentKind.NOTE) MigrationPublication.Purpose.CREATE_NOTE else MigrationPublication.Purpose.CREATE_CANVAS)
        empty.delete();return candidate
    }
    fun retainedBytes()=listOf("original.zip","candidate.zip","pending").associateWith {File(directory,it).readBytes()}
    fun assertRetained(expected:Map<String,ByteArray>) {expected.forEach {(name,bytes)->assertArrayEquals(name,bytes,File(directory,name).readBytes())}}
    fun leaseCount()=storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();it.getInt(0)}
    fun close() {storage.registry.close();root.deleteRecursively()}
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeCreationFirstPublishBindingTest {
    @Test fun aLeaseCloseCannotReplaceTheFirstPublishedStageWithAnotherPayloadOfTheSameUuid() {
        val f=CreationPendingFixture(Files.createTempDirectory("creation-first-stage-").toFile())
        try {
            val pdf=f.storage.pdfResources.admit("%PDF-stage-seam".byteInputStream(),"original")
            val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
            val candidate=f.bundle(intent.documentId,pdf=pdf.nameWithoutExtension)
            var replacement:File?=null;var swapped=false;var expected:Map<String,ByteArray>?=null
            val db=f.storage.registry.writableDatabase
            db.setCustomScalarFunction("replace_creation_on_release") {
                if(f.marker.isFile && !swapped) {
                    swapped=true
                    replacement=f.replace(intent.documentId,pdf=pdf.nameWithoutExtension)
                    expected=f.retainedBytes()
                }
                "ok"
            }
            db.execSQL("CREATE TRIGGER replace_creation_release AFTER DELETE ON leases BEGIN SELECT replace_creation_on_release('release'); END")
            val failure=runCatching {f.storage.publishCreated(f.uri,candidate,permit,"created")}.exceptionOrNull()
            assertTrue("The real lease release must have replaced the staged record",swapped)
            assertNotEquals(BundleMigration.hash(candidate),BundleMigration.hash(requireNotNull(replacement)))
            assertTrue("First publication must reject the replaced stage; writes=${f.writes}, replacementPublished=${f.current.isFile && BundleMigration.hash(f.current)==BundleMigration.hash(requireNotNull(replacement))}",
                failure is MigrationPublication.RecoveryRequired)
            assertEquals(0,f.writes);assertEquals(0L,f.current.length());assertNull(f.storage.registry.idAt(f.uri))
            f.assertRetained(requireNotNull(expected));assertTrue(pdf.isFile)
        } finally {f.close()}
    }
}
