package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.*
import com.xnotes.storage.NativeSourceSnapshot.ReferenceCatalog.Pages
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeReferenceCatalogTest {
    private val id="00000000-0000-4000-8000-000000000001"
    private val a="00000000-0000-4000-8000-000000000003"
    private val z="00000000-0000-4000-8000-000000000099"
    private class Fixture(val root:File) {
        val uri="content://reference-catalog/document/source"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val file=File(root,"source");var reads=0;var writes=0;var denied=false;var validation:(()->Unit)?=null
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read():java.io.InputStream {reads++;if(denied) throw SecurityException("permission denied");return file.inputStream()}
            override fun write(source:File) {writes++;source.copyTo(file,overwrite=true)}
        }},sourceValidationHook={validation?.invoke()})
        fun catalog(kind:DocumentKind=DocumentKind.NOTE,uri:String=this.uri,check:()->Unit={})=storage.referenceCatalog(uri,kind,check)
        fun leases()=storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();it.getInt(0)}
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("reference-catalog-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    private fun bundle(file:File,ids:List<String?> =listOf(z,a),kind:DocumentKind=DocumentKind.NOTE,anchored:Boolean=kind==DocumentKind.NOTE,
                       marker:ByteArray?=null,pdf:String?=null,embedded:ByteArray?=null,identity:Boolean=true) {
        ZipOutputStream(file.outputStream()).use {zip->
            if(identity) BundleIdentity(id,pdf,externalPdf=pdf!=null && embedded==null).write(zip)
            if(anchored) {zip.putNextEntry(ZipEntry(AnchorMetadata.ENTRY));zip.write(marker ?: AnchorMetadata(id).bytes());zip.closeEntry()}
            if(embedded!=null) {zip.putNextEntry(ZipEntry(BundlePdf.ENTRY));zip.write(embedded);zip.closeEntry()}
            val pages=ids.joinToString(",") {page->"""{${if(anchored && page!=null) "\"reference_id\":\"$page\"," else ""}"items":[{"type":"future-unrenderable-content"}]}"""}
            val contents=if(kind==DocumentKind.NOTE) "\"pages\":[$pages]" else "\"items\":[]"
            val manifest="""{"format":"${kind.extension}","version":1,"has_pdf":${pdf!=null || embedded!=null},$contents,"extension":{"pages":[{"reference_id":"not-a-direct-page"}]}}"""
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest.toByteArray());zip.closeEntry()
        }
    }
    @Test fun observedOrderTracksReorderAndDeletionWhileOldCatalogStaysFrozenAndNoModelsAreDecoded()=scenario {f->
        bundle(f.file);val original=f.file.readBytes();val first=f.catalog();val pages=first.pages as Pages.Available
        assertEquals(listOf(z,a),pages.orderedIds);assertEquals(setOf(a,z),pages.ids)
        assertEquals(id,first.documentId);assertEquals(DocumentKind.NOTE,first.kind);assertEquals(BundleMigration.hash(f.file),first.sha256)
        assertEquals(1,f.reads);assertArrayEquals(original,f.file.readBytes());assertTrue(f.storage.registry.locations(id).isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {(pages.orderedIds as MutableList<String>).add(id)}
        assertThrows(UnsupportedOperationException::class.java) {(pages.ids as MutableSet<String>).clear()}
        bundle(f.file,listOf(a,z));val next=f.catalog(uri="content://reference-catalog/tree/root/document/source")
        assertEquals(first.locationKey,next.locationKey);assertNotEquals(first.sha256,next.sha256)
        assertEquals(listOf(a,z),(next.pages as Pages.Available).orderedIds);assertEquals(listOf(z,a),pages.orderedIds)
        bundle(f.file,listOf(z));assertEquals(listOf(z),(f.catalog().pages as Pages.Available).orderedIds)
        assertEquals(0,f.writes);assertTrue(f.context.cacheDir.listFiles().orEmpty().isEmpty())
    }
    @Test fun legacyNotesNeedExplicitActivationAndCanvasCannotOfferPageReferences()=scenario {f->
        bundle(f.file,anchored=false);val original=f.file.readBytes();val note=f.catalog()
        assertSame(Pages.NeedsActivation,note.pages);assertArrayEquals(original,f.file.readBytes())
        bundle(f.file,kind=DocumentKind.CANVAS);assertSame(Pages.NotPaginated,f.catalog(DocumentKind.CANVAS).pages)
        val reads=f.reads;assertThrows(ReferenceContractViolation::class.java) {f.catalog(DocumentKind.MIND_MAP)}
        assertEquals(reads,f.reads);assertEquals(0,f.writes)
    }
    @Test fun aClosedSourceCannotIssueAnotherCatalogButItsEarlierDataRemainsReadable()=scenario {f->
        bundle(f.file);val source=f.storage.openSource(f.uri,DocumentKind.NOTE)
        val catalog=source.referenceCatalog();source.close()
        assertThrows(IllegalStateException::class.java) {source.referenceCatalog()}
        assertEquals(listOf(z,a),(catalog.pages as Pages.Available).orderedIds)
        assertTrue(f.context.cacheDir.listFiles().orEmpty().isEmpty())
    }
    @Test fun invalidPageMetadataCannotBeDowngradedToUnactivated() {
        val cases=listOf<(Fixture)->Unit>(
            {bundle(it.file,listOf(a,a))},{bundle(it.file,listOf(a,null))},
            {bundle(it.file,marker=AnchorMetadata(id).bytes().toString(Charsets.UTF_8).replace("\"version\":1","\"version\":2").toByteArray())},
            {bundle(it.file,marker=AnchorMetadata(a).bytes())},{bundle(it.file,anchored=false,identity=false)},
        )
        for(build in cases) scenario {f->
            build(f);val bytes=f.file.readBytes();assertThrows(ReferenceContractViolation::class.java) {f.catalog()}
            assertArrayEquals(bytes,f.file.readBytes());assertEquals(0,f.writes);assertTrue(f.context.cacheDir.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun locationAndUuidContractsBothRejectLostMarkers() {
        for(uri in listOf("content://reference-catalog/document/source","content://reference-catalog/document/moved")) scenario {f->
            f.storage.registry.register(id,"xnote","source",f.uri,pageReferences=true);bundle(f.file,anchored=false)
            val bytes=f.file.readBytes();assertThrows(ReferenceContractViolation::class.java) {f.catalog(uri=uri)}
            assertArrayEquals(bytes,f.file.readBytes());assertEquals(0,f.writes)
        }
    }
    @Test fun pendingPermissionAndCancellationPreserveSourceAndRecoveryArtifacts()=scenario {f->
        bundle(f.file);val bytes=f.file.readBytes();f.denied=true
        assertThrows(SecurityException::class.java) {f.catalog()};f.denied=false
        var checks=0
        assertThrows(CancellationException::class.java) {f.catalog {if(++checks==4) throw CancellationException("cancel snapshot")}}
        assertArrayEquals(bytes,f.file.readBytes());assertTrue(f.context.cacheDir.listFiles().orEmpty().isEmpty())
        val version=f.storage.openSource(f.uri,DocumentKind.NOTE).use {it.version}
        val candidate=File(f.root,"candidate");bundle(candidate,listOf(a,z))
        f.storage.prepareExpected(f.uri,candidate,version,handoffId=java.util.UUID.randomUUID().toString())
        val migrations=File(f.context.noBackupFilesDir,"migrations")
        val before=migrations.walkTopDown().filter {it.isFile}.associate {it.relativeTo(migrations).path to it.readBytes().toList()};val reads=f.reads
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.catalog()}
        assertEquals(reads,f.reads);assertEquals(before,migrations.walkTopDown().filter {it.isFile}.associate {it.relativeTo(migrations).path to it.readBytes().toList()})
        assertArrayEquals(bytes,f.file.readBytes());assertEquals(0,f.writes)
    }
    @Test fun embeddedPdfIsNotAdmittedAndExternalPdfOnlyHoldsATemporaryLease()=scenario {f->
        val resources=f.storage.pdfResources
        bundle(f.file,embedded="%PDF-not-decoded".toByteArray());val bytes=f.file.readBytes()
        assertTrue(f.catalog().pages is Pages.Available);assertTrue(resources.directory.listFiles().orEmpty().isEmpty())
        assertArrayEquals(bytes,f.file.readBytes())
        val pdf=resources.admit(ByteArray(200000) {42}.inputStream(),"original");bundle(f.file,pdf=pdf.nameWithoutExtension)
        var protected=false
        f.validation={assertTrue(f.leases()>0);f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0");resources.collect();assertTrue(pdf.isFile);protected=true}
        f.catalog();assertTrue(protected);assertEquals(0,f.leases());assertTrue(resources.references("note:$id").isEmpty())
        assertEquals(1,resources.directory.listFiles().orEmpty().size);assertEquals(0,f.writes)
    }
    @Test fun cancellingTheFirstPdfHashDoesNotLeakALeaseOrMarkTheObjectVerified()=scenario {f->
        val resources=f.storage.pdfResources;val pdf=resources.admit(ByteArray(400000) {42}.inputStream(),"original")
        val bytes=pdf.readBytes();var checks=0
        assertThrows(CancellationException::class.java) {resources.holdResolved(setOf(pdf.nameWithoutExtension)) {
            if(++checks==4) throw CancellationException("cancel during original hash")
        }.close()}
        assertEquals(0,f.leases());assertArrayEquals(bytes,pdf.readBytes())
        var nextChecks=0;resources.holdResolved(setOf(pdf.nameWithoutExtension)) {nextChecks++}.use {assertEquals(1,f.leases())}
        assertTrue("Cancelled verification must not populate the stamp cache",nextChecks>4);assertEquals(0,f.leases())
    }
}
