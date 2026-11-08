package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleAnchors
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundlePdf
import com.xnotes.format.BundleReferenceInfo
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual storage/provider publication and recovery; deliberately no ink/PDF decoder or model. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PageActivationStorageTest {
    private val id="bb4a7b2a-77ef-4ad8-8064-a1eca12a2501"
    private val originalPdf="%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n".toByteArray()

    private class Fixture(val root:File):AutoCloseable {
        val uri="content://page-activation/document/a"
        val alias="content://page-activation/tree/root/document/a"
        val other="content://page-activation/document/b"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val current=File(root,"current.xnote")
        var writes=0;var reads=0
        var writeFailure:String?=null
        var beforeRead:(()->Unit)?=null
        var validating:(()->Unit)?=null
        private val instances=mutableListOf<NotebookStorage>()
        val storage=newStorage()
        fun newStorage()=NotebookStorage(context,targetOverride={backend()},sourceValidationHook={validating?.invoke()})
            .also {instances+=it}
        fun backend()=object:MigrationPublication.Target {
            override fun read():java.io.InputStream {reads++;beforeRead?.invoke();return current.inputStream()}
            override fun write(source:File) {
                writes++
                if(writeFailure=="before") throw IOException("provider rejected the write")
                if(writeFailure=="partial") {current.writeText("partial");throw IOException("provider partial write")}
                source.copyTo(current,overwrite=true)
                if(writeFailure=="after") throw IOException("provider wrote B, then reported failure")
            }
        }
        val directory:File get() {
            val key=storage.registry.locationKey(uri)
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                .joinToString("") {"%02x".format(it)}
            return File(context.noBackupFilesDir,"migrations/$digest")
        }
        val marker get()=File(directory,"pending")
        fun journal()=MigrationPublication(directory)
        fun expected(kind:DocumentKind=DocumentKind.NOTE)=storage.openSource(uri,kind).use {it.version}
        fun retained()=listOf("original.zip","candidate.zip","pending").associateWith {File(directory,it).readBytes()}
        fun assertRetained(expected:Map<String,ByteArray>) {
            expected.forEach {(name,bytes)->assertArrayEquals(name,bytes,File(directory,name).readBytes())}
        }
        fun leases()=storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null)
            .use {it.moveToFirst();it.getInt(0)}
        override fun close() {instances.asReversed().forEach {it.registry.close()};root.deleteRecursively()}
    }
    private fun scenario(block:(Fixture)->Unit) {
        Fixture(Files.createTempDirectory("page-activation-storage-").toFile()).use(block)
    }
    private fun bundle(file:File,id:String=this.id,kind:DocumentKind=DocumentKind.NOTE,
                       body:String="original",external:String?=null,embedded:ByteArray?=null,
                       marker:Boolean=false,orphanIds:Boolean=false):File=file.also {
        ZipOutputStream(it.outputStream()).use {zip->
            BundleIdentity(id,pdfResource=external,externalPdf=external!=null).write(zip)
            if(marker) AnchorMetadata(id).write(zip)
            if(embedded!=null) {
                zip.putNextEntry(ZipEntry(BundlePdf.ENTRY));zip.write(embedded);zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            val content=if(kind==DocumentKind.NOTE) {
                val a=if(marker || orphanIds) "\"reference_id\":\"${UUID.randomUUID()}\"," else ""
                val b=if(marker || orphanIds) "\"reference_id\":\"${UUID.randomUUID()}\"," else ""
                // Raw unknown ink and number/escape spelling must survive; no ink/PDF decoder is used.
                "\"pages\":[{$a\"items\":[{\"type\":\"future-ink\",\"samples\":[1e+03,-0.000,7],\"text\":\"\\u4e2d文\"}]},{$b\"items\":[]}]"
            } else "\"items\":[]"
            zip.write("""{ "format":"${kind.extension}","version":1,"has_pdf":${external!=null || embedded!=null},$content,"body":"$body" }""".toByteArray())
            zip.closeEntry();zip.putNextEntry(ZipEntry("unknown/future.bin"))
            zip.write(byteArrayOf(0,127,-128,-1,10,0,27));zip.closeEntry()
        }
    }
    private fun entries(file:File)=ZipFile(file).use {zip->zip.entries().asSequence()
        .associate {entry->entry.name to zip.getInputStream(entry).use {it.readBytes()}}}
    private fun rewrite(file:File,name:String,change:(ByteArray)->ByteArray) {
        val before=entries(file)
        ZipOutputStream(file.outputStream()).use {zip->before.forEach {(entry,bytes)->
            zip.putNextEntry(ZipEntry(entry));zip.write(if(entry==name) change(bytes) else bytes);zip.closeEntry()
        }}
    }
    private fun assertEntryBytes(before:Map<String,ByteArray>,after:Map<String,ByteArray>,except:Set<String>) {
        for((name,bytes) in before) if(name !in except) assertArrayEquals(name,bytes,after[name])
    }
    private fun rejected(block:()->Unit):Exception {
        val error=runCatching(block).exceptionOrNull()
        assertTrue("Expected a contract/recovery rejection, got $error",
            error is ReferenceContractViolation || error is MigrationPublication.RecoveryRequired || error is PdfResources.Unavailable)
        return error as Exception
    }
    private fun available(catalog:NativeSourceSnapshot.ReferenceCatalog)=
        (catalog.pages as NativeSourceSnapshot.ReferenceCatalog.Pages.Available).orderedIds
    private fun stage(f:Fixture,candidate:File) {
        f.journal().stage(f.current,candidate,MigrationPublication.Purpose.ACTIVATE_PAGES,
            expectedOriginalHash=BundleMigration.hash(f.current),expectedCandidateHash=BundleMigration.hash(candidate))
    }

    @Test fun actualActivationPreservesRawPayloadAndReturnsThePublishedOrderedCatalog()=scenario {f->
        bundle(f.current,embedded=originalPdf)
        val a=File(f.root,"original.zip").also {f.current.copyTo(it)};val raw=entries(a);val expected=f.expected()
        assertSame(NativeSourceSnapshot.ReferenceCatalog.Pages.NeedsActivation,f.storage.referenceCatalog(f.uri,DocumentKind.NOTE).pages)
        val actual=f.storage.activatePages(f.alias,expected,"source.xnote")
        val ids=available(actual)
        assertEquals(2,ids.size);assertEquals(2,ids.toSet().size);assertEquals(id,actual.documentId)
        assertEquals(f.storage.registry.locationKey(f.uri),actual.locationKey)
        assertEquals(BundleMigration.hash(f.current),actual.sha256);assertEquals(DocumentKind.NOTE,actual.kind)
        assertEquals(ids,BundleReferenceInfo.inspectStrict(f.current).orderedPageIds)
        BundleAnchors.verifyActivation(a,f.current)
        assertEntryBytes(raw,entries(f.current),setOf("manifest.json",AnchorMetadata.ENTRY))
        assertArrayEquals(originalPdf,entries(f.current)[BundlePdf.ENTRY])
        assertEquals(1,f.writes);assertFalse(f.storage.hasRecovery(f.uri));assertTrue(f.storage.registry.expectationForId(id).requiresPages)
        // Reopening is a fresh source, and activation cannot silently rewrite a second ID assignment.
        val fresh=f.storage.openSource(f.uri,DocumentKind.NOTE).use {it.version}
        val bytes=f.current.readBytes();assertEquals(ids,available(f.storage.activatePages(f.uri,fresh,"source.xnote")))
        assertEquals(1,f.writes);assertArrayEquals(bytes,f.current.readBytes())
        rejected {f.storage.publishNote(f.uri,a,DocumentKind.NOTE,"stale unmarked live model")}
        assertEquals(1,f.writes);assertArrayEquals(bytes,f.current.readBytes());assertFalse(f.storage.hasRecovery(f.uri))
    }

    @Test fun wrongVersionLocationKindAndInvalidPageMetadataNeverWriteOrCreatePending() {
        for(mode in listOf("sameUuidChangedSha","changedUuid","wrongLocation","canvas","lostMarker","orphanIds","badMarker","duplicateIds","missingId")) scenario {f->
            bundle(f.current);val old=f.expected()
            val expected=when(mode) {
                "sameUuidChangedSha"->{bundle(f.current,body="external modification");old}
                "changedUuid"->{bundle(f.current,id=UUID.randomUUID().toString());old}
                "wrongLocation"->NativeSourceVersion.inspect(f.current,f.storage.registry.locationKey(f.other),DocumentKind.NOTE).version
                "canvas"->{bundle(f.current,kind=DocumentKind.CANVAS);f.expected(DocumentKind.CANVAS)}
                "lostMarker"->{f.storage.registry.register(id,"xnote","source",f.uri,pageReferences=true);old}
                "orphanIds"->{bundle(f.current,orphanIds=true);old}
                else->{
                    bundle(f.current,marker=true)
                    when(mode) {
                        "badMarker"->rewrite(f.current,AnchorMetadata.ENTRY) {"broken marker".toByteArray()}
                        "duplicateIds"->rewrite(f.current,"manifest.json") {
                            val text=it.toString(Charsets.UTF_8);val ids=Regex("\\\"reference_id\\\":\\\"([^\\\"]+)\\\"").findAll(text).map {m->m.groupValues[1]}.toList()
                            text.replace(ids[1],ids[0]).toByteArray()
                        }
                        else->rewrite(f.current,"manifest.json") {it.toString(Charsets.UTF_8).replaceFirst(Regex("\\\"reference_id\\\":\\\"[^\\\"]+\\\","),"").toByteArray()}
                    };old
                }
            }
            val before=f.current.readBytes()
            rejected {f.storage.activatePages(f.uri,expected,"source.xnote")}
            assertArrayEquals(mode,before,f.current.readBytes());assertEquals(mode,0,f.writes);assertFalse(mode,f.storage.hasRecovery(f.uri));assertEquals(mode,0,f.leases())
        }
    }

    @Test fun restartContinuesTheFixedCandidateAtAOrBWithoutMintingAgain() {
        for(mode in listOf("before","after","index","registeredB")) scenario {f->
            bundle(f.current);val expected=f.expected();val a=f.current.readBytes()
            if(mode=="index") {
                f.storage.registry.register(id,"xnote","source",f.uri)
                f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_activation_index BEFORE UPDATE ON documents BEGIN SELECT RAISE(ABORT,'injected index failure'); END")
            } else f.writeFailure=if(mode=="registeredB") "after" else mode
            rejected {f.storage.activatePages(f.uri,expected,"source.xnote")}
            assertTrue(mode,f.marker.isFile);val kept=f.retained();val b=kept.getValue("candidate.zip")
            val fixedIds=BundleReferenceInfo.inspectStrict(File(f.directory,"candidate.zip")).orderedPageIds
            assertArrayEquals(if(mode=="before") a else b,f.current.readBytes());assertEquals(1,f.writes)
            f.writeFailure=null
            // Represents registration committed before journal cleanup failed: historical A is
            // unmarked, while current B and the monotonic registry already require page IDs.
            if(mode=="registeredB") f.storage.registry.register(id,"xnote","source",f.uri,pageReferences=true)
            if(mode=="index") f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_activation_index")
            val restarted=f.newStorage()
            val actual=restarted.completePageActivation(f.alias,"source.xnote")
            assertEquals(fixedIds,available(actual));assertArrayEquals(b,f.current.readBytes())
            assertEquals(if(mode=="before") 2 else 1,f.writes);assertFalse(f.marker.exists())
            assertTrue(restarted.registry.expectationForId(id).requiresPages)
            assertEquals(BundleMigration.hash(f.current),actual.sha256);assertEquals(0,f.leases())
        }
    }

    @Test fun partialOrForeignCAndRevokedPermissionKeepTargetAndRetainedData() {
        for(mode in listOf("partial","sameUuidForeign","foreignUuid","permission")) scenario {f->
            bundle(f.current);val expected=f.expected();f.writeFailure="before"
            rejected {f.storage.activatePages(f.uri,expected,"source")};f.writeFailure=null
            when(mode) {
                "partial"->f.current.writeText("interrupted target C")
                "sameUuidForeign"->bundle(f.current,body="someone else's same UUID body")
                "foreignUuid"->bundle(f.current,id=UUID.randomUUID().toString(),body="foreign C")
                else->f.beforeRead={throw SecurityException("read permission revoked")}
            }
            val kept=f.retained();val current=f.current.readBytes();val writes=f.writes
            val error=runCatching {f.newStorage().completePageActivation(f.alias,"source")}.exceptionOrNull()
            assertNotNull(error)
            assertTrue("$mode: $error",error is MigrationPublication.RecoveryRequired || error is ReferenceContractViolation || error is SecurityException)
            assertArrayEquals(current,f.current.readBytes());f.assertRetained(kept);assertEquals(writes,f.writes);assertEquals(0,f.leases())
        }
    }

    @Test fun genericForgedCandidatesAndAFirstPublishStageReplacementNeverGainRawWriteAuthority() {
        for(entry in listOf("manifest.json","unknown/future.bin",BundlePdf.ENTRY)) scenario {f->
            bundle(f.current,embedded=originalPdf);val a=f.current.readBytes()
            val candidate=BundleAnchors.prepare(f.current,f.root).file
            rewrite(candidate,entry) {if(entry=="manifest.json") it.toString(Charsets.UTF_8).replace("original","forged").toByteArray() else it+42.toByte()}
            // This is a valid same-UUID native container and marker. Only the full A→B raw proof rejects it.
            assertEquals(id,BundleReferenceInfo.inspectStrict(candidate).header.identity!!.documentId)
            stage(f,candidate);val kept=f.retained()
            rejected {f.storage.completePageActivation(f.uri,"source")}
            assertArrayEquals(a,f.current.readBytes());assertEquals(0,f.writes);f.assertRetained(kept)
        }
        scenario {f->
            bundle(f.current);val expected=f.expected();val a=f.current.readBytes();var swapped=false
            var replacement:Map<String,ByteArray>?=null
            rejected {f.storage.activatePages(f.uri,expected,"source") {
                if(f.marker.exists() && !swapped) {
                    swapped=true
                    val candidate=File(f.directory,"candidate.zip");val original=File(f.directory,"original.zip")
                    val previousIds=BundleReferenceInfo.inspectStrict(candidate).orderedPageIds
                    val second=BundleAnchors.prepare(original,f.root).file
                    BundleAnchors.verifyActivation(original,second)
                    assertNotEquals(previousIds,BundleReferenceInfo.inspectStrict(second).orderedPageIds)
                    second.copyTo(candidate,overwrite=true);second.delete()
                    // This replacement is a second VALID raw activation of the same A/UUID,
                    // but its freshly minted page IDs are not the fixed B from THIS stage.
                    f.marker.writeText("3\n${BundleMigration.hash(File(f.directory,"original.zip"))}\n${BundleMigration.hash(candidate)}\nactivate-pages")
                    replacement=f.retained()
                }
            }}
            assertTrue("The callback must really replace the first staged record",swapped)
            assertEquals(0,f.writes);assertArrayEquals(a,f.current.readBytes());f.assertRetained(requireNotNull(replacement))
        }
    }

    @Test fun activationPurposeIsRejectedByAllOldConsumersIncludingHandoffAndNativeSaveContract()=scenario {f->
        bundle(f.current);val original=BundleReferenceInfo.inspectStrict(f.current)
        val candidate=BundleAnchors.prepare(f.current,f.root).file;val next=BundleReferenceInfo.inspectStrict(candidate)
        stage(f,candidate);val kept=f.retained();val a=f.current.readBytes()
        val consumers=listOf<()->Unit>(
            {f.storage.ensure(f.uri,"source","xnote");Unit},
            {f.storage.ensureForOpen(f.uri,"source","xnote");Unit},
            {f.storage.publishNote(f.uri,candidate,DocumentKind.NOTE,"source")},
            {f.storage.restore(f.uri)}, {f.storage.keepCurrent(f.uri)},
            {f.storage.keepWrittenSave(f.uri,DocumentKind.NOTE);Unit},
            {f.storage.completeCreation(f.uri,"source");Unit},
            {f.storage.openSource(f.uri,DocumentKind.NOTE).close()},
            {f.journal().publish(f.backend())}, {f.journal().restore(f.backend())}, {f.journal().keepCurrent()},
            {NativeSaveContract.verify(original,next,original,MigrationPublication.Purpose.ACTIVATE_PAGES,null,null)}
        )
        consumers.forEachIndexed {index,consume->
            rejected(consume);assertEquals("old consumer $index wrote",0,f.writes)
            assertArrayEquals(a,f.current.readBytes());f.assertRetained(kept)
        }
        val detached=MigrationPublication(File(f.root,"activation-handoff"))
        assertThrows(IllegalArgumentException::class.java) {
            detached.stage(f.current,candidate,MigrationPublication.Purpose.ACTIVATE_PAGES,
                expectedOriginalHash=BundleMigration.hash(f.current),expectedCandidateHash=BundleMigration.hash(candidate),handoffId=UUID.randomUUID().toString())
        }
        assertFalse(detached.pending)
        f.marker.writeText(f.marker.readText().replaceFirst("3\n","4\n")+"\n${UUID.randomUUID()}")
        val invalid=f.retained()
        rejected {f.storage.completePageActivation(f.uri,"source")}
        rejected {f.storage.ensure(f.uri,"source","xnote")}
        f.assertRetained(invalid);assertEquals(0,f.writes);assertArrayEquals(a,f.current.readBytes())
    }

    @Test fun pdfLeasesPinsCancellationAndFreshHashProtectOriginalsWithoutDecoding()=scenario {f->
        val resources=f.storage.pdfResources
        val pdf=resources.admit(originalPdf.inputStream(),"original.pdf");val pdfId=pdf.nameWithoutExtension
        bundle(f.current,external=pdfId);val expected=f.expected();val a=f.current.readBytes()
        var protected=false
        f.validating={
            assertTrue(f.leases()>0);protected=true
            f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
            resources.collect();assertTrue(pdf.isFile)
        }
        var cancelled=false
        assertThrows(CancellationException::class.java) {f.storage.activatePages(f.uri,expected,"source") {
            if(f.marker.exists() && !cancelled) {cancelled=true;throw CancellationException("after fixed stage")}
        }}
        assertTrue(protected);assertTrue(cancelled);assertEquals(0,f.writes);assertArrayEquals(a,f.current.readBytes())
        assertTrue(f.marker.exists());assertEquals(0,f.leases());val kept=f.retained()
        assertTrue(f.storage.registry.owners("migration:").isNotEmpty());assertTrue(pdf.isFile)
        assertThrows(CancellationException::class.java) {f.storage.completePageActivation(f.alias,"source") {throw CancellationException("before completion")}}
        f.assertRetained(kept);assertEquals(0,f.writes);assertEquals(0,f.leases())
        // A trusted cache stamp is insufficient at this explicit raw publication boundary.
        val stamp=pdf.lastModified();val bytes=pdf.readBytes();pdf.writeBytes(bytes.copyOf().apply {this[0]=(this[0].toInt() xor 1).toByte()});assertTrue(pdf.setLastModified(stamp))
        rejected {f.storage.completePageActivation(f.uri,"source")}
        f.assertRetained(kept);assertEquals(0,f.writes);assertArrayEquals(a,f.current.readBytes());assertEquals(0,f.leases())
        pdf.writeBytes(bytes);assertTrue(pdf.setLastModified(stamp))
        val actual=f.newStorage().completePageActivation(f.alias,"source")
        assertEquals(2,available(actual).size);assertEquals(1,f.writes);assertFalse(f.marker.exists());assertEquals(0,f.leases())
        assertArrayEquals(originalPdf,pdf.readBytes());assertEquals(setOf(pdfId),resources.references("note:$id"))
        assertEquals(0,f.storage.registry.collect(System.currentTimeMillis()+30L*24*60*60*1000,0,resources.directory));assertTrue(pdf.isFile)
        assertEquals(pdfId,BundleReferenceInfo.inspectStrict(f.current).originalPdfHash)
    }
}
