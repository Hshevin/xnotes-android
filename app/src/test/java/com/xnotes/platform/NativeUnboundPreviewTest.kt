package com.xnotes.platform

import android.content.ContextWrapper
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
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
import java.util.UUID
import java.util.concurrent.CancellationException

/** Actual portable working bodies; a preview is not a prepared native open or a writable origin. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeUnboundPreviewTest {
    private class Fixture(val root:File) {
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val sourceUri="content://unbound-preview/document/original"
        val sourceFile=File(root,"original.xnote")
        var sourceReads=0
        var sourceWrites=0
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read():java.io.InputStream {sourceReads++;return sourceFile.inputStream()}
            override fun write(source:File) {sourceWrites++;source.copyTo(sourceFile,overwrite=true)}
        }})
        val noteCodec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).apply {resolvePdf={storage.pdfResources.resolve(it)}}
        val canvasCodec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        val directory=File(root,"working")
        val assets=File(root,"assets").apply {mkdirs()}
        val pdfs=File(root,"pdfs").apply {mkdirs()}
        val store=NativeWorkingSession(directory,noteCodec,canvasCodec,storage.pdfResources)
        val view=NativeWorkingSession.NoteView(1.25,15.0,30.0,true,ViewOverrides(rotation=90))
        fun recover(check:()->Unit={}):NativeWorkingSession.Recovery=
            store.prepareRecovery(storage,pdfs,assets,check)
        fun bytes()=directory.listFiles().orEmpty().filter {it.isFile}.associate {it.name to it.readBytes().toList()}
        fun assetNames()=assets.listFiles().orEmpty().map {it.name}.toSet()
        fun leases()=storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {
            assertTrue(it.moveToFirst());it.getInt(0)
        }
        fun ageResources() {storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")}
        fun ink(x:Double=10.0)=Stroke(Tool.PEN,ToolConfig(),listOf(Sample(x,20.0,.5),Sample(x+20.0,40.0,.75)))
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-unbound-preview-").toFile()
        val fixture=Fixture(root)
        try {block(fixture)} finally {fixture.storage.registry.close();root.deleteRecursively()}
    }

    @Test fun unboundNoteActuallyDecodesOrderedPagesAndFrozenMetadataWithoutOpeningAnyOrigin()=scenario {f->
        val document=Document.blank(3).apply {
            enableReferenceAnchors();pages.reverse();pages[1].items.add(f.ink())
            bookmarks.add(Bookmark(1,"Unsaved bookmark"));displayName="Recovered draft.xnote";dirty=true
        }
        val write=f.store.capture(document,null,f.view)
        val pageIds=document.pages.map {it.referenceIdentity.id}
        document.displayName="Later live title";document.dirty=false
        assertTrue(f.store.publish(write).published)
        val before=f.bytes();val assetsBefore=f.assetNames()
        (f.recover() as NativeWorkingSession.Recovery.Unbound).use {recovery->
            assertEquals(WorkingSessionCache.Kind.NOTE,recovery.kind)
            assertEquals(document.identity.documentId,recovery.documentId)
            assertNull(recovery.metadata.source);assertTrue(recovery.metadata.dirty)
            assertEquals("Recovered draft.xnote",recovery.metadata.displayName);assertEquals(f.view,recovery.metadata.noteView)
            val preview=recovery.prepared;val note=requireNotNull(preview.note)
            assertEquals(DocumentKind.NOTE,preview.kind);assertNull(preview.canvas)
            assertEquals("Recovered draft.xnote",preview.displayName);assertEquals(3,preview.pageCount);assertTrue(preview.byteSize>0)
            assertNull(note.path);assertTrue(note.dirty);assertEquals("Recovered draft.xnote",note.displayName)
            assertEquals(pageIds,note.pages.map {it.referenceIdentity.id})
            assertEquals("Unsaved bookmark",note.bookmarks.single().label)
            val stroke=note.pages[1].items.single() as Stroke
            assertEquals(10.0,stroke.xAt(0),.001);assertEquals(20.0,stroke.yAt(0),.001)
            assertEquals(before,f.bytes());assertEquals(0,f.sourceReads);assertEquals(0,f.sourceWrites)
        }
        assertEquals(before,f.bytes());assertEquals(assetsBefore,f.assetNames())
        assertEquals("Later live title",document.displayName);assertFalse(document.dirty)
    }

    @Test fun canvasAbsentViewOverridesItsOldBodyAndPreviewPinsHistoryAcrossNewHeadAndClear()=scenario {f->
        val document=InfiniteDocument().apply {add(f.ink());lastView=Waypoint("old body",5.0,6.0,.5);dirty=true}
        assertTrue(f.store.publish(f.store.capture(document,null)).published)
        val oldBody=f.store.load()!!.use {it.body}
        document.lastView=null
        val viewOnly=f.store.capture(document,null);assertFalse(viewOnly.requiresBody)
        assertTrue(f.store.publish(viewOnly).published)
        val recovery=f.recover() as NativeWorkingSession.Recovery.Unbound
        try {
            val preview=recovery.prepared;val canvas=requireNotNull(preview.canvas)
            assertEquals(DocumentKind.CANVAS,preview.kind);assertNull(preview.note);assertNull(canvas.path)
            assertNull(recovery.metadata.canvasView);assertNull(canvas.lastView)
            assertEquals(10.0,(canvas.items.single() as Stroke).xAt(0),.001)
            document.add(f.ink(200.0));document.dirty=true
            assertTrue(f.store.publish(f.store.capture(document,null)).published)
            assertTrue("Held preview must keep its exact historical body",oldBody.isFile)
            assertTrue(f.store.publishClear(f.store.reserveClear()).published)
            assertTrue("A tombstone must not reclaim the open preview body",oldBody.isFile)
            assertEquals(1,canvas.items.size);assertEquals(10.0,(canvas.items.single() as Stroke).xAt(0),.001)
            assertSame(NativeWorkingSession.LoadState.Cleared,f.store.loadState());assertNull(f.store.load())
        } finally {recovery.close()}
        assertFalse("Closing the last preview releases the historical body for GC",oldBody.exists())
        assertTrue(f.assetNames().isEmpty());assertEquals(0,f.sourceReads);assertEquals(0,f.sourceWrites)
    }

    @Test fun nativeLoadStateSeparatesAbsenceAndClearAndNeverTurnsBadOrFutureHeadIntoEmpty() {
        scenario {f->
            assertSame(NativeWorkingSession.LoadState.Absent,f.store.loadState());assertNull(f.store.load())
            assertTrue(f.store.publishClear(f.store.reserveClear()).published)
            assertSame(NativeWorkingSession.LoadState.Cleared,f.store.loadState());assertNull(f.store.load())
            val document=Document.blank();assertTrue(f.store.publish(f.store.capture(document,null,f.view)).published)
            val active=f.store.loadState() as NativeWorkingSession.LoadState.Active
            active.read.use {assertEquals(document.identity.documentId,it.documentId);assertEquals(BundleMigration.hash(it.body),it.sha256)}
            f.store.load()!!.use {assertEquals(WorkingSessionCache.Kind.NOTE,it.kind)}
            assertEquals(0,f.sourceReads);assertEquals(0,f.sourceWrites)
        }
        for(raw in listOf("not json","""{"version":99,"state":"cleared"}""")) scenario {f->
            assertTrue(f.directory.isDirectory || f.directory.mkdirs());val head=File(f.directory,"head.json").apply {writeText(raw)}
            val bytes=head.readBytes()
            assertThrows(java.io.IOException::class.java) {f.store.loadState()}
            assertThrows(java.io.IOException::class.java) {f.recover()}
            assertArrayEquals(bytes,head.readBytes());assertTrue(f.assetNames().isEmpty())
            assertEquals(0,f.sourceReads);assertEquals(0,f.sourceWrites)
        }
    }

    @Test fun portablePdfHasARealPreviewLeaseAndCancellationAfterLeaseAcquisitionCleansOnlyOwnedAssets()=scenario {f->
        val original=File(f.root,"import.pdf").apply {writeText("%PDF-unbound-original\n"+"preserved".repeat(10000))}
        val originalBytes=original.readBytes()
        val document=Document.blank().apply {pdfFile=original;enableReferenceAnchors();dirty=true}
        assertTrue(f.store.publish(f.store.capture(document,null,f.view)).published)
        val before=f.bytes();val baselineLeases=f.leases();assertEquals(0,baselineLeases)
        val recovery=f.recover() as NativeWorkingSession.Recovery.Unbound
        val managed=requireNotNull(recovery.prepared.note!!.pdfFile)
        try {
            assertTrue(f.storage.pdfResources.isOwned(managed));assertArrayEquals(originalBytes,managed.readBytes())
            assertTrue("Preparation must actually acquire a registry PDF lease",f.leases()>baselineLeases)
            f.ageResources();assertEquals(0,f.storage.pdfResources.collect());assertTrue(managed.isFile)
        } finally {recovery.close()}
        assertEquals(baselineLeases,f.leases());assertTrue(f.assetNames().isEmpty())
        f.ageResources();assertEquals(1,f.storage.pdfResources.collect());assertFalse(managed.exists())
        var cancelledAfterLease=false
        assertThrows(CancellationException::class.java) {f.recover {
            if(f.leases()>baselineLeases) {cancelledAfterLease=true;throw CancellationException("after actual PDF handoff")}
        }}
        assertTrue("Cancellation must occur after a real lease, not at entry",cancelledAfterLease)
        assertEquals(baselineLeases,f.leases());assertTrue(f.assetNames().isEmpty())
        assertTrue(f.storage.pdfResources.directory.listFiles().orEmpty().none {it.name.startsWith("resource-")})
        assertArrayEquals(originalBytes,original.readBytes());assertEquals(before,f.bytes())
        assertEquals(0,f.sourceReads);assertEquals(0,f.sourceWrites)
    }

    @Test fun anUnboundDecodedModelCannotBorrowAnActualSourceContextAndClosedPreviewCannotCopy()=scenario {f->
        val document=Document.blank(2).apply {enableReferenceAnchors();dirty=true}
        assertTrue(f.store.publish(f.store.capture(document,null,f.view)).published)
        // An actual source with exactly the same document/page IDs is still a different model owner.
        f.sourceFile.outputStream().use {f.noteCodec.write(document.snapshot(),it)}
        val sourceBytes=f.sourceFile.readBytes();val cacheBytes=f.bytes()
        val recovery=f.recover() as NativeWorkingSession.Recovery.Unbound
        val preview=recovery.prepared;val decoded=requireNotNull(preview.note)
        assertNull(decoded.path)
        val opened=f.storage.prepareOpen(f.sourceUri,DocumentKind.NOTE,f.noteCodec,f.canvasCodec,f.pdfs,f.assets)
        val actual=requireNotNull(opened.note)
        actual.path=f.sourceUri;opened.protectForAdoption();opened.adopt(actual);opened.close()
        val source=opened.editSource()
        try {
            decoded.path=f.sourceUri // A writable-looking path must not confer source ownership.
            val rejected=assertThrows(ReferenceContractViolation::class.java) {source.capture(decoded)}
            assertEquals(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID,rejected.reason)
            assertThrows(ReferenceContractViolation::class.java) {f.store.capture(decoded,source,f.view)}
            assertEquals(0,f.sourceWrites);assertArrayEquals(sourceBytes,f.sourceFile.readBytes());assertEquals(cacheBytes,f.bytes())
            decoded.path=null
            val targetId=UUID.randomUUID().toString()
            val copy=preview.prepareCopy(targetId,File(f.root,"copies").apply {mkdirs()})
            try {
                val info=BundleReferenceInfo.inspect(copy.file,true)
                assertEquals(targetId,info.header.identity!!.documentId)
                assertTrue(info.pageIds.none {it in document.pages.map {page->page.referenceIdentity.id}})
                assertEquals(0,f.sourceWrites);assertArrayEquals(sourceBytes,f.sourceFile.readBytes());assertEquals(cacheBytes,f.bytes())
            } finally {copy.file.delete()}
        } finally {source.invalidate();recovery.close()}
        assertThrows(IllegalStateException::class.java) {preview.prepareCopy(UUID.randomUUID().toString(),f.root)}
        assertArrayEquals(sourceBytes,f.sourceFile.readBytes());assertEquals(cacheBytes,f.bytes())
    }
}
