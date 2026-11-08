package com.xnotes.platform

import android.content.ContextWrapper
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.PreparedNativeOpen
import com.xnotes.storage.ReferenceContractViolation
import com.xnotes.storage.StorageMsg
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeWorkingRecoveryTest {
    private class Fixture(val root:File) {
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val uri="content://working-recovery/document/source"
        val sourceFile=File(root,"source")
        val assets=File(root,"assets").apply {mkdir()}
        val pdfs=File(root,"pdfs").apply {mkdir()}
        var beforeRead:(()->Unit)?=null
        var beforeWrite:(()->Unit)?=null
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read():java.io.InputStream {beforeRead?.invoke();return sourceFile.inputStream()}
            override fun write(source:File) {beforeWrite?.invoke();source.copyTo(sourceFile,overwrite=true)}
        }})
        val noteCodec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).apply {resolvePdf={storage.pdfResources.resolve(it)}}
        val canvasCodec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        val directory=File(root,"working")
        fun store()=NativeWorkingSession(directory,noteCodec,canvasCodec,storage.pdfResources)
        val view=NativeWorkingSession.NoteView(1.5,3.0,4.0,true,ViewOverrides(rotation=90))
        fun bound(document:Document=Document.blank(3).apply {enableReferenceAnchors()},external:Boolean=false):Pair<Document,PreparedNativeOpen.EditSource> {
            sourceFile.outputStream().use {noteCodec.write(document.snapshot(),it,embedPdf=!external)}
            val p=storage.prepareOpen(uri,DocumentKind.NOTE,noteCodec,canvasCodec,pdfs,assets)
            val doc=p.note!!;doc.path=uri;p.protectForAdoption();p.adopt(doc);p.close()
            return doc to p.editSource()
        }
        fun boundCanvas():Pair<InfiniteDocument,PreparedNativeOpen.EditSource> {
            val doc=InfiniteDocument();sourceFile.outputStream().use {canvasCodec.write(doc.snapshotForWrite(),it)}
            val p=storage.prepareOpen(uri,DocumentKind.CANVAS,noteCodec,canvasCodec,pdfs,assets)
            val loaded=p.canvas!!;loaded.path=uri;p.protectForAdoption();p.adopt(loaded);p.close()
            return loaded to p.editSource()
        }
        fun recover(store:NativeWorkingSession=store(),check:()->Unit={}):NativeWorkingSession.Recovery=
            store.prepareRecovery(storage,pdfs,assets,check)
        fun cacheBytes()=directory.listFiles().orEmpty().filter {it.isFile}.associate {it.name to it.readBytes().toList()}
        fun publish(p:PreparedNativeOpen) {
            p.protectForAdoption()
            val context=if(p.note!=null) {p.adopt(p.note);p.editSource()} else {p.adopt(p.canvas!!);p.editSource()}
            val request=p.note?.let {context.capture(it)} ?: context.capture(p.canvas!!)
            val receipt=request.publish(storage,noteCodec,canvasCodec,File(root,"saves"),"source")
            assertTrue(context.accept(request,receipt));assertEquals(BundleMigration.hash(sourceFile),receipt.version.sha256)
        }
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-working-recovery-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    @Test fun editedPageSetIsVerifiedAgainstTheCacheAndCanSaveBackAgainstTheActualSourceBase()=scenario {f->
        val (doc,source)=f.bound();val originalBase=BundleMigration.hash(f.sourceFile)
        val removed=doc.pages.removeAt(1).referenceIdentity.id;doc.pages.reverse();doc.pages.add(Page(100.0,100.0))
        doc.bookmarks.add(Bookmark(0,"unsaved"));doc.displayName="working title";doc.dirty=true
        val store=f.store();store.publish(store.capture(doc,source,f.view))
        val expectedPages=doc.pages.map {it.referenceIdentity.id};val before=f.sourceFile.readBytes()
        val result=f.recover() as NativeWorkingSession.Recovery.Bound
        result.use {
            val p=it.prepared;val restored=p.note!!
            assertEquals(originalBase,p.version.sha256);assertArrayEquals(before,f.sourceFile.readBytes())
            assertEquals(expectedPages,restored.pages.map {page->page.referenceIdentity.id});assertFalse(expectedPages.contains(removed))
            assertEquals("unsaved",restored.bookmarks.single().label);assertTrue(restored.dirty);assertEquals("working title",restored.displayName)
            assertEquals(f.uri,restored.path);assertEquals(f.view,it.metadata.noteView)
            assertThrows(IllegalStateException::class.java) {p.editSource()}
            f.publish(p)
            val saved=f.sourceFile.inputStream().use {input->f.noteCodec.read(input)}
            assertEquals(expectedPages,saved.pages.map {page->page.referenceIdentity.id});assertEquals("unsaved",saved.bookmarks.single().label)
        }
    }
    @Test fun canvasRecoveryUsesTheLatestMetadataIncludingAnExplicitlyAbsentView()=scenario {f->
        val (doc,source)=f.boundCanvas();val store=f.store();doc.lastView=Waypoint("old",1.0,2.0,.5)
        store.publish(store.capture(doc,source));doc.lastView=Waypoint("new",30.0,-20.0,2.0)
        assertFalse(store.capture(doc,source).requiresBody);store.publish(store.capture(doc,source))
        (f.recover() as NativeWorkingSession.Recovery.Bound).use {
            assertEquals(doc.lastView,it.prepared.canvas!!.lastView);assertEquals(doc.lastView,it.metadata.canvasView)
        }
        doc.lastView=null;store.publish(store.capture(doc,source))
        (f.recover() as NativeWorkingSession.Recovery.Bound).use {
            assertNull(it.prepared.canvas!!.lastView);f.publish(it.prepared)
        }
    }
    @Test fun portableCacheRestoresTheActualSourcePdfIdentityAndPackagingWithAnIndependentHandoffLease() {
        for(external in listOf(false,true)) scenario {f->
            val original=File(f.root,"import.pdf").apply {writeText("%PDF-source-$external")}
            val draft=Document.blank().apply {pdfFile=original;enableReferenceAnchors()}
            if(external) {
                draft.pdfFile=f.storage.pdfResources.admit(original,"original")
                draft.identity=draft.identity.copy(pdfResource=draft.pdfFile!!.nameWithoutExtension)
            }
            val (doc,source)=f.bound(draft,external);val sourceIdentity=doc.identity
            val store=f.store();doc.dirty=true;store.publish(store.capture(doc,source,f.view))
            store.load()!!.use {assertNotNull(BundleReferenceInfo.inspect(it.body,true).header.identity!!.pdfResource)}
            if(!external) assertNull(sourceIdentity.pdfResource)
            val before=f.sourceFile.readBytes()
            (f.recover() as NativeWorkingSession.Recovery.Bound).use {
                val p=it.prepared;assertEquals(sourceIdentity,p.note!!.identity)
                val pdf=p.note.pdfFile!!;assertArrayEquals(original.readBytes(),pdf.readBytes())
                // Remove the old model's durable pin: only the preparation's cache lease protects this handoff.
                f.storage.pdfResources.pin("note:${doc.identity.documentId}",emptySet())
                f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
                f.storage.pdfResources.collect();assertTrue("Cache admission lease must survive source.close for embedded too",pdf.isFile)
                assertArrayEquals(before,f.sourceFile.readBytes());f.publish(p)
                val info=BundleReferenceInfo.inspect(f.sourceFile,true)
                assertEquals(external,info.header.identity!!.externalPdf)
                assertEquals(sourceIdentity.pdfResource,info.header.identity.pdfResource)
                assertEquals(BundleMigration.hash(original),info.originalPdfHash)
            }
        }
    }
    @Test fun changedOrMissingSourcesPreserveTheCacheAndNeverBecomeAnEmptyRecovery() {
        for(missing in listOf(false,true)) scenario {f->
            val (doc,source)=f.bound();val store=f.store();store.publish(store.capture(doc,source,f.view));val cache=f.cacheBytes()
            if(missing) f.sourceFile.delete() else {
                val foreign=f.sourceFile.inputStream().use {f.noteCodec.read(it)}
                foreign.bookmarks.add(Bookmark(0,"external edit"));f.sourceFile.outputStream().use {f.noteCodec.write(foreign.snapshot(),it)}
            }
            val before=if(missing) null else f.sourceFile.readBytes()
            if(missing) assertThrows(java.io.FileNotFoundException::class.java) {f.recover()}
            else {
                val error=assertThrows(ReferenceContractViolation::class.java) {f.recover()}
                assertEquals(StorageMsg.REFERENCE_SOURCE_CHANGED,error.reason)
            }
            assertEquals(cache,f.cacheBytes());assertEquals(!missing,f.sourceFile.exists())
            before?.let {assertArrayEquals(it,f.sourceFile.readBytes())}
        }
    }
    @Test fun aChangedDraftPdfCannotReceiveAuthorityToReplaceTheOriginalPdf()=scenario {f->
        val original=Document.blank().apply {pdfFile=File(f.root,"original.pdf").apply {writeText("%PDF-original")}}
        val (doc,source)=f.bound(original);doc.pdfFile=File(f.root,"different.pdf").apply {writeText("%PDF-different")};doc.dirty=true
        val store=f.store();store.publish(store.capture(doc,source,f.view));val cache=f.cacheBytes();val before=f.sourceFile.readBytes()
        val error=assertThrows(ReferenceContractViolation::class.java) {f.recover()}
        assertEquals(StorageMsg.PDF_RESOURCE_MISMATCH,error.reason)
        assertEquals(cache,f.cacheBytes());assertArrayEquals(before,f.sourceFile.readBytes())
    }
    @Test fun aSourceChangedAfterPreparationStillBlocksTheRestoredModelsActualPublication()=scenario {f->
        val (doc,source)=f.bound();val store=f.store();doc.bookmarks.add(Bookmark(0,"recovered"));doc.dirty=true
        store.publish(store.capture(doc,source,f.view));val cache=f.cacheBytes()
        (f.recover() as NativeWorkingSession.Recovery.Bound).use {result->
            val foreign=f.sourceFile.inputStream().use {f.noteCodec.read(it)}
            foreign.bookmarks.add(Bookmark(0,"new external edit"));f.sourceFile.outputStream().use {f.noteCodec.write(foreign.snapshot(),it)}
            val before=f.sourceFile.readBytes()
            val error=assertThrows(ReferenceContractViolation::class.java) {f.publish(result.prepared)}
            assertEquals(StorageMsg.REFERENCE_SOURCE_CHANGED,error.reason)
            assertArrayEquals(before,f.sourceFile.readBytes());assertEquals(cache,f.cacheBytes())
        }
    }
    @Test fun noHeadAndUnboundDraftAreExplicitResultsWithoutReadingASource()=scenario {f->
        f.beforeRead={fail("Unbound drafts must not be matched to any file")}
        assertSame(NativeWorkingSession.Recovery.Empty,f.recover())
        val store=f.store();store.publish(store.capture(Document.blank(),null,f.view));val cache=f.cacheBytes()
        assertTrue(f.recover() is NativeWorkingSession.Recovery.Unbound);assertEquals(cache,f.cacheBytes())
    }
    @Test fun permissionLossAndPendingNativePublicationDoNotGrantOrConsumeARecovery() {
        for(pending in listOf(false,true)) scenario {f->
            val (doc,source)=f.bound();val store=f.store();store.publish(store.capture(doc,source,f.view))
            val before=f.sourceFile.readBytes();val cache=f.cacheBytes()
            if(pending) {
                doc.bookmarks.add(Bookmark(0,"force a real target publication"));doc.dirty=true
                f.beforeWrite={throw java.io.IOException("target temporarily unavailable")}
                val failed=assertThrows(MigrationPublication.RecoveryRequired::class.java) {
                    source.capture(doc).publish(f.storage,f.noteCodec,f.canvasCodec,File(f.root,"save"),"source")
                }
                assertEquals(StorageMsg.MIGRATION_WRITE_INCOMPLETE,failed.reason)
                assertTrue(failed.cause is java.io.IOException)
                assertTrue(f.storage.hasRecovery(f.uri))
                assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.recover()}
                assertTrue(f.storage.hasRecovery(f.uri))
            } else {
                f.beforeRead={throw SecurityException("permission revoked")}
                assertThrows(SecurityException::class.java) {f.recover()}
            }
            assertArrayEquals(before,f.sourceFile.readBytes());assertEquals(cache,f.cacheBytes())
        }
    }
    @Test fun cancellationDuringRecoveryCannotConsumeOrDeleteTheWorkingSession()=scenario {f->
        val (doc,source)=f.bound();val store=f.store();store.publish(store.capture(doc,source,f.view));val cache=f.cacheBytes()
        var sourceRead=false;f.beforeRead={sourceRead=true}
        assertThrows(CancellationException::class.java) {f.recover {if(sourceRead) throw CancellationException("after reading starts")}}
        assertTrue(sourceRead);assertEquals(cache,f.cacheBytes());assertTrue(f.sourceFile.isFile)
    }
    @Test fun foreignFabricatedAndClosedReadsCannotIssueARecoveryContextEvenWithTheRightBodyHash()=scenario {f->
        val (doc,source)=f.bound();val store=f.store();store.publish(store.capture(doc,source,f.view))
        val read=store.load()!!;val other=f.store();val generic=WorkingSessionCache(f.directory,{_,_,_->}).load()!!
        val fake=NativeWorkingSession.Read(Any(),generic,read.metadata)
        val dirs=f.assets.listFiles()!!.map {it.name}.toSet()
        fun prepare(adapter:NativeWorkingSession,held:NativeWorkingSession.Read)=f.storage.openSource(f.uri,DocumentKind.NOTE).use {
            PreparedNativeOpen.readWorking(adapter,held,it,f.storage.pdfResources,f.noteCodec,f.canvasCodec,f.pdfs,f.assets,{})
        }
        try {
            assertThrows(IllegalStateException::class.java) {prepare(other,read)}
            assertThrows(IllegalStateException::class.java) {prepare(store,fake)}
            read.close();assertThrows(IllegalStateException::class.java) {prepare(store,read)}
            assertEquals(dirs,f.assets.listFiles()!!.map {it.name}.toSet())
        } finally {read.close();fake.close()}
    }
    @Test fun leaseClosureOrBodyTamperingDuringDecodeCannotProduceAnAdoptableModel() {
        for(close in listOf(false,true)) scenario {f->
            val (doc,source)=f.bound();val store=f.store();store.publish(store.capture(doc,source,f.view))
            val read=store.load()!!;val body=read.body;val before=f.sourceFile.readBytes()
            val dirs=f.assets.listFiles()!!.map {it.name}.toSet();var altered=false
            try {
                f.storage.openSource(f.uri,DocumentKind.NOTE).use {native->
                    val error=assertThrows(Exception::class.java) {
                        PreparedNativeOpen.readWorking(store,read,native,f.storage.pdfResources,f.noteCodec,f.canvasCodec,f.pdfs,f.assets) {
                            if(!altered && f.assets.listFiles()!!.any {it.name !in dirs}) {
                                altered=true
                                if(close) read.close() else body.appendText("changed during decode")
                            }
                        }
                    }
                    if(close) assertTrue(error is IllegalStateException) else assertTrue(error is WorkingSessionCache.Invalid)
                }
                assertTrue(altered);assertEquals(dirs,f.assets.listFiles()!!.map {it.name}.toSet())
                assertArrayEquals(before,f.sourceFile.readBytes());assertTrue(body.isFile)
            } finally {read.close()}
        }
    }
}
