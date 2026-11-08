package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeEditSourceTest {
    private class Fixture(val root:File) {
        val uri="content://edit-source/document/a"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val current=File(root,"source");val assets=File(root,"assets").apply {mkdir()}
        val pdfs=File(root,"pdfs").apply {mkdir()};val scratch=File(root,"scratch").apply {mkdir()}
        var reads=0;var writes=0;var beforeWrite:(()->Unit)?=null;var beforeRead:(()->Unit)?=null
        var cancelWrite=false
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read():java.io.InputStream {reads++;beforeRead?.invoke();return current.inputStream()}
            override fun write(source:File) {
                writes++;beforeWrite?.invoke();if(cancelWrite) throw CancellationException("cancelled save")
                source.copyTo(current,overwrite=true)
            }
        }})
        val noteCodec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).apply {resolvePdf={storage.pdfResources.resolve(it)}}
        val canvasCodec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        fun prepare(kind:DocumentKind):PreparedNativeOpen {
            val p=storage.prepareOpen(uri,kind,noteCodec,canvasCodec,pdfs,assets)
            p.note?.let {it.path=uri};p.canvas?.let {it.path=uri}
            p.protectForAdoption();p.note?.let {p.adopt(it)};p.canvas?.let {p.adopt(it)};p.close()
            return p
        }
        fun note(pdf:File?=null,embedded:Boolean=false):Document {
            val doc=Document.blank().apply {enableReferenceAnchors()}
            if(pdf!=null) {doc.pdfFile=pdf;doc.identity=doc.identity.copy(pdfResource=pdf.nameWithoutExtension,externalPdf=!embedded)}
            current.outputStream().use {noteCodec.write(doc.snapshot(),it,embedPdf=pdf==null || embedded)}
            return doc
        }
        fun canvas() {current.outputStream().use {canvasCodec.write(InfiniteDocument(created=0).snapshotForWrite(),it)}}
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-edit-source-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    private fun ink()=com.xnotes.core.model.Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN)).apply {
        addSample(Sample(10.0,20.0,.4,t=10.0));addSample(Sample(30.0,40.0,.8,t=20.0))
    }
    @Test fun realRequestStagesItsOwnBodyBeforeTheOwnerAcceptsAnyReceipt()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        doc.pages.single().items.add(ink());doc.dirty=true
        val before=f.current.readBytes();val request=source.capture(doc)
        val stage=request.prepare(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        assertEquals(0,f.writes);assertArrayEquals(before,f.current.readBytes());assertTrue(f.storage.hasRecovery(f.uri))
        assertEquals(request.expected.sha256,source.version(doc).sha256)
        assertTrue(f.scratch.listFiles().orEmpty().isEmpty())
        val receipt=stage.commit()
        assertTrue(source.accept(request,receipt));assertTrue(doc.dirty);assertEquals(1,f.writes)
        assertEquals(stage.candidate.sha256,source.version(doc).sha256)
    }
    @Test fun laterNoteGeometryEditsCannotChangeTheCapturedNativeRequest()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        val stroke=ink();doc.pages.single().items.add(stroke);doc.dirty=true
        val request=source.capture(doc);val revision=request.snapshot.bodyRevision
        stroke.translate(100.0,200.0);doc.dirty=true
        val receipt=request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        val saved=f.current.inputStream().use {f.noteCodec.read(it)}.pages.single().items.single() as com.xnotes.core.model.Stroke
        assertEquals(10.0,saved.xAt(0),.001);assertEquals(20.0,saved.yAt(0),.001)
        assertEquals(110.0,stroke.xAt(0),.001);assertEquals(revision,request.snapshot.bodyRevision)
        assertTrue(doc.bodyRevision>revision);assertTrue(source.accept(request,receipt));assertTrue(doc.dirty)
    }
    @Test fun laterCanvasGeometryEditsCannotChangeTheCapturedNativeRequest()=scenario {f->
        f.canvas();val p=f.prepare(DocumentKind.CANVAS);val doc=requireNotNull(p.canvas);val source=p.editSource()
        val stroke=ink();doc.add(stroke);doc.dirty=true
        val request=source.capture(doc);stroke.translate(100.0,200.0);doc.dirty=true
        request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"canvas")
        val saved=f.current.inputStream().use {f.canvasCodec.read(it)}.items.single() as com.xnotes.core.model.Stroke
        assertEquals(10.0,saved.xAt(0),.001);assertEquals(20.0,saved.yAt(0),.001)
        assertEquals(110.0,stroke.xAt(0),.001);assertTrue(doc.dirty)
    }
    @Test fun coordinatedRequestNeverIssuesAReceiptBeforeAWorkingCacheAcknowledgementExists()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        doc.pages.single().items.add(ink());doc.dirty=true;val request=source.capture(doc)
        val original=f.current.readBytes();val stage=request.prepareForHandoff(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        assertNotNull(stage.handoffId);assertEquals(0,f.writes)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {stage.commit()}
        assertArrayEquals(original,f.current.readBytes());assertEquals(request.expected.sha256,source.version(doc).sha256)
        assertTrue(f.storage.hasRecovery(f.uri));assertEquals(0,f.writes)
    }
    @Test fun revokingTheActualSourceDuringCommitTargetValidationPreventsAnyWrite()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        doc.pages.single().items.add(ink());doc.dirty=true
        val stage=source.capture(doc).prepare(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        val original=f.current.readBytes();f.beforeRead={source.invalidate()}
        assertThrows(ReferenceContractViolation::class.java) {stage.commit()}
        assertArrayEquals(original,f.current.readBytes());assertEquals(0,f.writes);assertTrue(f.storage.hasRecovery(f.uri))
    }
    @Test fun stagedExternalPdfHasADurableMigrationPinBetweenTemporaryLeaseAndCommit()=scenario {f->
        val resources=f.storage.pdfResources;val pdf=resources.admit("%PDF-staged-original".byteInputStream(),"original")
        f.note(pdf);val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        doc.pages.single().items.add(ink());doc.dirty=true
        val noteOwner="note:${doc.identity.documentId}";resources.pin(noteOwner,emptySet())
        val request=source.capture(doc);val stage=request.prepare(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        val key=f.storage.registry.locationKey(f.uri)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
        assertEquals(setOf(pdf.nameWithoutExtension),resources.references("migration:$hash"))
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        resources.collect();assertTrue(pdf.isFile);assertEquals(0,f.writes)
        val receipt=stage.commit();assertTrue(source.accept(request,receipt))
        assertEquals(setOf(pdf.nameWithoutExtension),resources.references(noteOwner))
        assertTrue(resources.references("migration:$hash").isEmpty());resources.collect();assertTrue(pdf.isFile)
    }
    @Test fun canvasUsesTheSameStagedRequestAndReceiptContract()=scenario {f->
        f.canvas();val p=f.prepare(DocumentKind.CANVAS);val doc=requireNotNull(p.canvas);val source=p.editSource()
        doc.add(ink());doc.dirty=true;val original=f.current.readBytes()
        val request=source.capture(doc);val other=source.capture(doc)
        val stage=request.prepare(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"canvas")
        assertArrayEquals(original,f.current.readBytes());assertEquals(0,f.writes)
        val receipt=stage.commit();assertFalse(source.accept(other,receipt));assertTrue(source.accept(request,receipt))
        assertEquals(stage.candidate.sha256,source.version(doc).sha256);assertEquals(1,f.writes)
    }
    @Test fun lossOfTheOwnerAfterProviderWritePreservesTheWrittenBytesWithoutIssuingAReceipt()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        doc.pages.single().items.add(ink());doc.dirty=true
        val stage=source.capture(doc).prepare(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        f.beforeWrite={source.invalidate()}
        assertThrows(ReferenceContractViolation::class.java) {stage.commit()}
        assertEquals(1,f.writes);assertEquals(stage.candidate.sha256,BundleMigration.hash(f.current))
        assertTrue(f.storage.hasRecovery(f.uri));assertTrue(doc.dirty)
        f.beforeWrite=null
        assertEquals(stage.candidate.sha256,f.storage.keepWrittenSave(f.uri,DocumentKind.NOTE).sha256)
    }
    @Test fun aPreparedAdoptionIssuesOnlyOneContextAndItsSnapshotKeepsPageAndInkIdentity()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        assertSame(source,p.editSource());val page=doc.pages.single().referenceIdentity.id
        val stroke=ink();doc.pages.single().items.add(stroke);doc.dirty=true
        val revision=doc.bodyRevision;val request=source.capture(doc)
        assertNotSame(doc,request.snapshot);assertEquals(page,request.snapshot.pages.single().referenceIdentity.id)
        assertEquals(revision,doc.bodyRevision);assertTrue(doc.dirty)
        val receipt=request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        assertTrue(source.accept(request,receipt));assertTrue(doc.dirty) // the owner, not this proof, decides dirty acknowledgement
        assertEquals(BundleMigration.hash(f.current),source.version(doc).sha256)
        assertEquals(page,doc.pages.single().referenceIdentity.id);assertSame(stroke,doc.pages.single().items.single())
    }
    @Test fun twoRealSavesAdvanceOnlyTheirOwnContextAndUseTheLatestBase()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        repeat(2) {
            doc.pages.single().items.add(ink());doc.dirty=true
            val request=source.capture(doc);val receipt=request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
            assertTrue(source.accept(request,receipt));assertEquals(BundleMigration.hash(f.current),source.version(doc).sha256)
        }
        assertEquals(2,f.writes);assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun anotherModelsRealReceiptAtTheSameUuidAndLocationCannotAcknowledgeThisRequest()=scenario {f->
        f.note();val a=f.prepare(DocumentKind.NOTE);val b=f.prepare(DocumentKind.NOTE)
        val ad=requireNotNull(a.note);val bd=requireNotNull(b.note);val ac=a.editSource();val bc=b.editSource()
        assertEquals(ad.identity.documentId,bd.identity.documentId);val before=ac.version(ad)
        val ar=ac.capture(ad);val br=bc.capture(bd)
        val receipt=br.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        assertFalse(ac.accept(ar,receipt));assertFalse(ac.accept(br,receipt));assertSame(before,ac.version(ad))
        assertTrue(bc.accept(br,receipt));assertFalse(bc.accept(br,receipt))
    }
    @Test fun independentModelWrongKindIdentityOrLocationCannotBorrowTheContext()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        val independent=f.current.inputStream().use {f.noteCodec.read(it)}.apply {path=f.uri}
        assertThrows(ReferenceContractViolation::class.java) {source.capture(independent)}
        assertThrows(ReferenceContractViolation::class.java) {source.capture(InfiniteDocument())}
        doc.path="content://edit-source/document/other"
        assertThrows(ReferenceContractViolation::class.java) {source.capture(doc)}
        doc.path=f.uri;doc.identity=doc.identity.copy(documentId=UUID.randomUUID().toString())
        assertThrows(ReferenceContractViolation::class.java) {source.capture(doc)}
    }
    @Test fun modelEditsDuringPublicationAreKeptWhenTheBaseReceiptArrives()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        doc.pages.single().items.add(ink());doc.dirty=true // force an actual write rather than an identical-byte no-op
        val request=source.capture(doc)
        f.beforeWrite={doc.pages.single().items.add(ink());doc.dirty=true}
        val receipt=request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        assertTrue(source.accept(request,receipt));assertEquals(2,doc.pages.single().items.size);assertTrue(doc.dirty)
        assertEquals(1,f.current.inputStream().use {f.noteCodec.read(it)}.pages.single().items.size)
        assertEquals(BundleMigration.hash(f.current),source.version(doc).sha256)
    }
    @Test fun retirementInvalidatesOldRequestsAndRepeatedPreparedAccessCannotResurrectTheBase()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        val request=source.capture(doc);val receipt=request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        source.invalidate();assertFalse(source.accept(request,receipt));assertSame(source,p.editSource())
        assertThrows(ReferenceContractViolation::class.java) {source.capture(doc)}
        val writes=f.writes
        assertThrows(ReferenceContractViolation::class.java) {request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")}
        assertEquals(writes,f.writes)
    }
    @Test fun failedRegistrationOrCancelledWriteHasNoReceiptAndCannotMoveTheBase() {
        for(cancel in listOf(false,true)) scenario {f->
            f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource();val base=source.version(doc)
            doc.pages.single().items.add(ink());doc.dirty=true;val request=source.capture(doc)
            if(cancel) f.cancelWrite=true else f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_ack BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'DB failed'); END")
            assertThrows(if(cancel) CancellationException::class.java else MigrationPublication.RecoveryRequired::class.java) {
                request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
            }
            assertSame(base,source.version(doc));assertTrue(doc.dirty);assertTrue(f.storage.hasRecovery(f.uri));assertTrue(f.scratch.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun sameUuidExternalChangeIsRejectedBeforeAnyTargetWriteAndTheOwnerIsNotRebased()=scenario {f->
        f.note();val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource();val base=source.version(doc)
        val external=f.current.inputStream().use {f.noteCodec.read(it)}.apply {pages.single().items.add(ink())}
        f.current.outputStream().use {f.noteCodec.write(external.snapshot(),it)};val bytes=f.current.readBytes()
        val request=source.capture(doc)
        assertThrows(ReferenceContractViolation::class.java) {request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")}
        assertEquals(0,f.writes);assertSame(base,source.version(doc));assertArrayEquals(bytes,f.current.readBytes());assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun canvasUsesTheSameRequestReceiptContractWithoutChangingViewportOrDirty()=scenario {f->
        f.canvas();val p=f.prepare(DocumentKind.CANVAS);val doc=requireNotNull(p.canvas);val source=p.editSource()
        doc.add(ink());doc.dirty=true;val view=doc.lastView
        val request=source.capture(doc);val receipt=request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"canvas")
        assertTrue(source.accept(request,receipt));assertEquals(view,doc.lastView);assertTrue(doc.dirty)
        assertEquals(BundleMigration.hash(f.current),source.version(doc).sha256)
    }
    @Test fun changedActualPdfCannotBeSavedUnderTheOldResourceField()=scenario {f->
        val pdf=f.storage.pdfResources.admit("%PDF-original".byteInputStream(),"original");f.note(pdf)
        val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource();val before=f.current.readBytes()
        doc.pdfFile=File(f.root,"replacement.pdf").apply {writeText("%PDF-different")};doc.dirty=true
        val request=source.capture(doc)
        assertThrows(ReferenceContractViolation::class.java) {request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")}
        assertEquals(0,f.writes);assertArrayEquals(before,f.current.readBytes());assertTrue(pdf.exists());assertTrue(doc.dirty)
        assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun aSuccessfulNoopShaStillExpiresEveryEarlierRequestAndDuplicateReceipt()=scenario {f->
        f.canvas();normalizeZipTimes(f.current,f.root)
        val p=f.prepare(DocumentKind.CANVAS);val doc=requireNotNull(p.canvas);val source=p.editSource();val base=source.version(doc)
        val a=source.capture(doc);val b=source.capture(doc)
        // Normalize only ZIP timestamps in this fixture, keeping real codec payloads. The publisher
        // still goes through actual staging/readback/registration; no fake receipt is constructed.
        f.beforeRead={f.scratch.listFiles().orEmpty().filter {it.isFile}.forEach {normalizeZipTimes(it,f.root)}}
        val ar=a.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"canvas")
        val br=b.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"canvas")
        assertEquals(base.sha256,ar.version.sha256);assertEquals(base.sha256,br.version.sha256)
        assertTrue(source.accept(a,ar));assertFalse(source.accept(a,ar));assertFalse(source.accept(b,br))
        assertEquals(base.sha256,source.version(doc).sha256);assertEquals(0,f.writes)
    }
    @Test fun publicationLeasesTheOriginalBeforeNativeValidationAndHandsItToTheSavedNotePin()=scenario {f->
        val resources=f.storage.pdfResources;val pdf=resources.admit("%PDF-lease".byteInputStream(),"original");f.note(pdf)
        val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        doc.pages.single().items.add(ink());doc.dirty=true;val request=source.capture(doc)
        resources.pin("note:${doc.identity.documentId}",emptySet())
        f.beforeRead={
            f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0");resources.collect();assertTrue(pdf.isFile)
        }
        val receipt=request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        assertTrue(source.accept(request,receipt));assertEquals(1,f.writes)
        assertEquals(setOf(pdf.nameWithoutExtension),resources.references("note:${doc.identity.documentId}"))
        resources.collect();assertTrue(pdf.exists())
    }
    @Test fun cleanupFailurePreservesTheOriginalCancellationAndCannotCreateAReceipt()=scenario {f->
        val pdf=f.storage.pdfResources.admit("%PDF-cleanup".byteInputStream(),"original");f.note(pdf)
        val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource();val base=source.version(doc)
        val request=source.capture(doc);val cancelled=CancellationException("cancel during PDF hash");var checks=0
        val thrown=assertThrows(CancellationException::class.java) {
            request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source") {
                if(++checks==2) {
                    f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_close BEFORE DELETE ON leases BEGIN SELECT RAISE(ABORT,'lease close failed'); END")
                    throw cancelled
                }
            }
        }
        assertSame(cancelled,thrown);assertTrue(thrown.suppressed.isNotEmpty());assertSame(base,source.version(doc))
        assertEquals(0,f.writes);assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun aCleanupFailureAfterConfirmedPublicationDoesNotHideItsActualReceipt()=scenario {f->
        val pdf=f.storage.pdfResources.admit("%PDF-committed".byteInputStream(),"original");f.note(pdf)
        val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        doc.pages.single().items.add(ink());doc.dirty=true;val request=source.capture(doc)
        f.beforeWrite={f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_close BEFORE DELETE ON leases BEGIN SELECT RAISE(ABORT,'lease close failed'); END")}
        val receipt=request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        assertTrue(source.accept(request,receipt));assertEquals(BundleMigration.hash(f.current),source.version(doc).sha256)
        assertFalse(f.storage.hasRecovery(f.uri));assertTrue(pdf.isFile);assertTrue(doc.dirty)
    }
    @Test fun embeddedPdfKeepsItsOriginalBytesAndPackagingAfterManagedAdoptionAndSave()=scenario {f->
        val pdf=f.storage.pdfResources.admit("%PDF-embedded unchanged".byteInputStream(),"original");val bytes=pdf.readBytes()
        f.note(pdf,embedded=true)
        val p=f.prepare(DocumentKind.NOTE);val doc=requireNotNull(p.note);val source=p.editSource()
        assertTrue(f.storage.pdfResources.isOwned(requireNotNull(doc.pdfFile)))
        doc.pages.single().items.add(ink());doc.dirty=true;val request=source.capture(doc)
        val receipt=request.publish(f.storage,f.noteCodec,f.canvasCodec,f.scratch,"source")
        assertTrue(source.accept(request,receipt))
        val info=com.xnotes.format.BundleReferenceInfo.inspect(f.current,true)
        assertFalse(requireNotNull(info.header.identity).externalPdf);assertEquals(pdf.nameWithoutExtension,info.embeddedPdfHash)
        java.util.zip.ZipFile(f.current).use {zip->assertArrayEquals(bytes,zip.getInputStream(requireNotNull(zip.getEntry("assets/source.pdf"))).use {it.readBytes()})}
        assertArrayEquals(bytes,pdf.readBytes());assertTrue(doc.dirty)
    }
    @Test fun aPreparedButUnadoptedModelCannotIssueALongTermContext()=scenario {f->
        f.note()
        val p=f.storage.prepareOpen(f.uri,DocumentKind.NOTE,f.noteCodec,f.canvasCodec,f.pdfs,f.assets)
        try {assertThrows(IllegalStateException::class.java) {p.editSource()}}
        finally {p.close()}
        assertTrue(f.assets.listFiles().orEmpty().isEmpty())
    }
    private fun normalizeZipTimes(file:File,root:File) {
        val temp=File.createTempFile("zip-time-", ".zip",root)
        try {
            ZipInputStream(file.inputStream()).use {input->ZipOutputStream(temp.outputStream()).use {output->
                output.setLevel(java.util.zip.Deflater.BEST_SPEED)
                while(true) {
                    val entry=input.nextEntry ?: break
                    val bytes=input.readBytes();val next=ZipEntry(entry.name).apply {time=1700000000000L;method=entry.method}
                    if(next.method==ZipEntry.STORED) {next.size=bytes.size.toLong();next.crc=java.util.zip.CRC32().apply {update(bytes)}.value}
                    output.putNextEntry(next);output.write(bytes);output.closeEntry();input.closeEntry()
                }
            }}
            temp.copyTo(file,overwrite=true)
        } finally {temp.delete()}
    }
}
