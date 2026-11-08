package com.xnotes.platform

import android.content.ContextWrapper
import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.ReferenceContractViolation
import org.json.JSONObject
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
class NativeWorkingSessionTest {
    private class Fixture(val root:File) {
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val sourceUri="content://native-working/document/source"
        val sourceFile=File(root,"source")
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read()=sourceFile.inputStream()
            override fun write(source:File) {source.copyTo(sourceFile,overwrite=true)}
        }})
        val noteCodec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).apply {resolvePdf={storage.pdfResources.resolve(it)}}
        val canvasCodec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        val directory=File(root,"working")
        var event:((WorkingSessionCache.Event,File)->Unit)?=null
        val store=NativeWorkingSession(directory,noteCodec,canvasCodec,storage.pdfResources,onEvent={e,f->event?.invoke(e,f)})
        val view=NativeWorkingSession.NoteView(1.2,10.0,20.0,true,ViewOverrides())
        fun bound():Pair<Document,com.xnotes.storage.PreparedNativeOpen.EditSource> {
            val doc=Document.blank().apply {enableReferenceAnchors()}
            sourceFile.outputStream().use {noteCodec.write(doc.snapshot(),it)}
            val p=storage.prepareOpen(sourceUri,DocumentKind.NOTE,noteCodec,canvasCodec,File(root,"pdfs").apply {mkdir()},File(root,"assets").apply {mkdir()})
            val loaded=requireNotNull(p.note);loaded.path=sourceUri;loaded.displayName="source.xnote";p.protectForAdoption();p.adopt(loaded);p.close()
            return loaded to p.editSource()
        }
        fun allBytes():Map<String,List<Byte>> = directory.listFiles().orEmpty().filter {it.isFile}.associate {it.name to it.readBytes().toList()}
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-working-session-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    @Test fun capturedNativeBodyDoesNotReadLaterLiveTextAndTheNextRevisionGetsItsOwnBody()=scenario {f->
        val doc=Document.blank();val text=com.xnotes.core.model.TextItem(com.xnotes.core.geometry.Pt.ZERO,
            text="captured",measurer=FakeTextMeasurer())
        doc.pages.single().items.add(text);doc.dirty=true
        val first=f.store.capture(doc,null,f.view)
        text.text="later";doc.dirty=true
        assertTrue(f.store.publish(first).published)
        f.store.load()!!.use {read->
            val saved=read.body.inputStream().use {f.noteCodec.read(it)}
            assertEquals("captured",(saved.pages.single().items.single() as com.xnotes.core.model.TextItem).text)
        }
        val next=f.store.capture(doc,null,f.view);assertTrue(next.requiresBody);assertTrue(f.store.publish(next).published)
        f.store.load()!!.use {read->
            val saved=read.body.inputStream().use {f.noteCodec.read(it)}
            assertEquals("later",(saved.pages.single().items.single() as com.xnotes.core.model.TextItem).text)
        }
        assertFalse(f.store.capture(doc,null,f.view).requiresBody)
    }
    @Test fun canvasCachePinsTheCapturedStrokeBeforeItsLiveGeometryChanges()=scenario {f->
        val doc=InfiniteDocument();val ink=com.xnotes.core.model.Stroke(com.xnotes.core.tools.Tool.PEN,com.xnotes.core.tools.ToolConfig(),
            listOf(com.xnotes.core.stroke.Sample(10.0,20.0,.5),com.xnotes.core.stroke.Sample(30.0,40.0,.75)))
        doc.add(ink);doc.dirty=true;val request=f.store.capture(doc,null)
        ink.translate(100.0,200.0);doc.dirty=true
        assertTrue(f.store.publish(request).published)
        f.store.load()!!.use {read->
            val saved=read.body.inputStream().use {f.canvasCodec.read(it)}.items.single() as com.xnotes.core.model.Stroke
            assertEquals(10.0,saved.xAt(0),.001);assertEquals(20.0,saved.yAt(0),.001)
        }
        assertEquals(110.0,ink.xAt(0),.001)
    }
    @Test fun noteRoundTripsItsPortableBodyFrozenMetadataAndUnboundOrigin()=scenario {f->
        val doc=Document.blank(2).apply {enableReferenceAnchors();displayName="draft.xnote";dirty=true}
        val view=f.view.copy(overrides=ViewOverrides(mode=ViewingMode.COVER,verticalScroll=false,contrast=130,invert=40,
            brightness=85,sepia=25,multiply=Rgba(200,255,200),screen=Rgba(90,0,30),keepImages=true,rotation=270,scrollbar=true,freeMovement=true))
        val request=f.store.capture(doc,null,view);doc.displayName="later";doc.dirty=false
        assertTrue(f.store.publish(request).published)
        f.store.load()!!.use {read->
            assertEquals(WorkingSessionCache.Kind.NOTE,read.kind);assertEquals(doc.identity.documentId,read.documentId)
            assertEquals(BundleMigration.hash(read.body),read.sha256);assertNull(read.metadata.source)
            assertEquals("draft.xnote",read.metadata.displayName);assertTrue(read.metadata.dirty);assertEquals(view,read.metadata.noteView)
            val loaded=read.body.inputStream().use {f.noteCodec.read(it)}
            assertEquals(2,loaded.pages.size);assertEquals(doc.pages.map {it.referenceIdentity.id},loaded.pages.map {it.referenceIdentity.id})
        }
        assertFalse(doc.dirty);assertEquals("later",doc.displayName)
    }
    @Test fun boundOriginComesFromTheActualContextAndIsOnlyAReadDescription()=scenario {f->
        val (doc,source)=f.bound();val expected=source.version(doc)
        f.store.publish(f.store.capture(doc,source,f.view))
        f.store.load()!!.use {read->
            val origin=requireNotNull(read.metadata.source)
            assertEquals(expected.sha256,origin.sha256);assertEquals(expected.documentId,origin.documentId)
            assertEquals(expected.locationKey,origin.locationKey);assertEquals(f.sourceUri,origin.uri);assertEquals(DocumentKind.NOTE,origin.kind)
        }
        val independent=f.sourceFile.inputStream().use {f.noteCodec.read(it)}.apply {path=f.sourceUri}
        assertThrows(ReferenceContractViolation::class.java) {f.store.capture(independent,source,f.view)}
        assertThrows(ReferenceContractViolation::class.java) {f.store.capture(doc,null,f.view)}
        assertEquals(expected.sha256,BundleMigration.hash(f.sourceFile))
    }
    @Test fun canvasViewOnlyUsesLatestMetadataWhileReusingTheOldBody()=scenario {f->
        val doc=InfiniteDocument().apply {lastView=Waypoint("",1.0,2.0,.5);dirty=true}
        val first=f.store.capture(doc,null);assertTrue(f.store.publish(first).published)
        val body=f.store.load()!!.use {it.body}
        doc.lastView=Waypoint("",99.0,-12.0,3.0)
        val viewOnly=f.store.capture(doc,null);assertFalse(viewOnly.requiresBody);assertTrue(f.store.publish(viewOnly).published)
        f.store.load()!!.use {read->
            assertEquals(body,read.body);assertEquals(doc.lastView,read.metadata.canvasView);assertNull(read.metadata.noteView)
            val fromBody=read.body.inputStream().use {f.canvasCodec.read(it)}
            assertEquals(Waypoint("",1.0,2.0,.5),fromBody.lastView) // caller must apply metadata, not this older view
        }
    }
    @Test fun bookmarkChangesRequireANewBodyEvenWhenRenderingHasNoVersionChange()=scenario {f->
        val doc=Document.blank();f.store.publish(f.store.capture(doc,null,f.view))
        val first=f.store.load()!!.use {it.body}
        doc.bookmarks.add(Bookmark(0,"remember"));doc.dirty=true
        val request=f.store.capture(doc,null,f.view);assertTrue(request.requiresBody);f.store.publish(request)
        f.store.load()!!.use {read->assertNotEquals(first,read.body);assertEquals("remember",read.body.inputStream().use {f.noteCodec.read(it)}.bookmarks.single().label)}
    }
    @Test fun modesSwitchThroughOneHeadAndClearCannotBeRevivedByAnOlderWrite()=scenario {f->
        val note=Document.blank();val old=f.store.capture(note,null,f.view)
        val canvas=InfiniteDocument();assertTrue(f.store.publish(f.store.capture(canvas,null)).published)
        assertFalse(f.store.publish(old).published)
        assertEquals(WorkingSessionCache.Kind.CANVAS,f.store.load()!!.use {it.kind})
        val pending=f.store.capture(note,null,f.view);val clear=f.store.reserveClear();assertTrue(f.store.publishClear(clear).published)
        assertFalse(f.store.publish(pending).published);assertNull(f.store.load())
        val legacy=File(f.root,"document.xnote").apply {writeText("legacy recovery data")}
        assertTrue(f.store.publishClear(f.store.reserveClear()).published);assertTrue(legacy.exists())
    }
    @Test fun aFailedHeadLeavesThePreviousSessionAndRetriesItsBodyVersion()=scenario {f->
        val doc=Document.blank();f.store.publish(f.store.capture(doc,null,f.view));val old=f.allBytes()
        doc.bookmarks.add(Bookmark(0,"new"));doc.dirty=true
        f.event={event,_->if(event==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) throw java.io.IOException("head failed")}
        assertThrows(java.io.IOException::class.java) {f.store.publish(f.store.capture(doc,null,f.view))}
        assertEquals(old["head.json"],f.allBytes()["head.json"])
        f.event=null;val retry=f.store.capture(doc,null,f.view);assertTrue(retry.requiresBody);assertTrue(f.store.publish(retry).published)
    }
    @Test fun retiredOrAdvancedSourceProofIsCheckedEvenForViewOnlyRequests()=scenario {f->
        val (doc,source)=f.bound();f.store.publish(f.store.capture(doc,source,f.view))
        val stale=f.store.capture(doc,source,f.view.copy(scrollY=900.0));assertFalse(stale.requiresBody)
        val native=source.capture(doc);val receipt=native.publish(f.storage,f.noteCodec,f.canvasCodec,File(f.root,"save").apply {mkdir()},"source")
        assertTrue(source.accept(native,receipt));assertFalse(f.store.publish(stale).published)
        val fresh=f.store.capture(doc,source,f.view.copy(scrollY=500.0));assertFalse(fresh.requiresBody)
        source.invalidate();assertFalse(f.store.publish(fresh).published)
        f.store.load()!!.use {assertEquals(f.view,it.metadata.noteView)}
    }
    @Test fun contextRetirementAtHeadPreparationDoesNotPublishAViewOnlyChange()=scenario {f->
        val (doc,source)=f.bound();f.store.publish(f.store.capture(doc,source,f.view));val before=f.allBytes()["head.json"]
        val pending=f.store.capture(doc,source,f.view.copy(scrollY=333.0));assertFalse(pending.requiresBody)
        f.event={event,_->if(event==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) source.invalidate()}
        assertFalse(f.store.publish(pending).published);assertEquals(before,f.allBytes()["head.json"])
    }
    @Test fun unmanagedPdfDraftUsesASnapshotCopyAndViewOnlyDoesNotReadmitOrCopyIt()=scenario {f->
        val original=File(f.root,"unmanaged.pdf").apply {writeBytes("%PDF-".toByteArray()+ByteArray(2*1024*1024) {42})}
        val bytes=original.readBytes();val doc=Document.blank().apply {pdfFile=original;dirty=true}
        val originalIdentity=doc.identity;f.store.publish(f.store.capture(doc,null,f.view))
        val managed=f.storage.pdfResources.directory.listFiles().orEmpty().filter {it.name.endsWith(".pdf")};assertEquals(1,managed.size)
        val body=f.store.load()!!.use {read->
            java.util.zip.ZipFile(read.body).use {zip->assertArrayEquals(bytes,zip.getInputStream(zip.getEntry("assets/source.pdf")).use {it.readBytes()})}
            assertFalse(requireNotNull(com.xnotes.format.BundleReferenceInfo.inspect(read.body,true).header.identity).externalPdf)
            read.body
        }
        assertSame(original,doc.pdfFile);assertEquals(originalIdentity,doc.identity);assertArrayEquals(bytes,original.readBytes());assertTrue(doc.dirty)
        // A view-only update must not even require reading/admitting the original again.
        assertTrue(original.delete());val viewOnly=f.store.capture(doc,null,f.view.copy(scrollX=55.0));assertFalse(viewOnly.requiresBody)
        assertTrue(f.store.publish(viewOnly).published)
        f.store.load()!!.use {assertEquals(body,it.body);assertEquals(55.0,it.metadata.noteView!!.scrollX,0.0)}
        assertEquals(1,f.storage.pdfResources.directory.listFiles().orEmpty().count {it.name.endsWith(".pdf")})
    }
    @Test fun failedPdfCacheEncodingNeverDeletesTheLiveOriginal()=scenario {f->
        val original=File(f.root,"draft.pdf").apply {writeText("%PDF-still live")};val bytes=original.readBytes()
        val doc=Document.blank().apply {pdfFile=original;dirty=true}
        f.event={e,_->if(e==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) throw CancellationException("stop")}
        assertThrows(CancellationException::class.java) {f.store.publish(f.store.capture(doc,null,f.view))}
        assertArrayEquals(bytes,original.readBytes());assertSame(original,doc.pdfFile);assertTrue(doc.dirty);assertNull(doc.identity.pdfResource)
    }
    @Test fun heldPdfAdmissionDoesNotTrustAReusedFileStampOrReclassifyACorruptManagedOriginal()=scenario {f->
        val resources=f.storage.pdfResources
        val original=File(f.root,"mutable.pdf").apply {writeText("%PDF-first")}
        val stamp=original.lastModified();val first=resources.admit(original,"first")
        original.writeText("%PDF-other");assertTrue(original.setLastModified(stamp))
        assertEquals(first,resources.admit(original,"cached")) // reproduce the stamp-cache branch
        val held=resources.admitHeld(original,"current")
        assertNotEquals(first,held.file);assertEquals(BundleMigration.hash(original),held.file.nameWithoutExtension)
        assertArrayEquals(original.readBytes(),held.file.readBytes());held.lease.close()
        val before=resources.directory.listFiles()!!.map {it.name}.toSet()
        first.writeText("%PDF-broken")
        assertThrows(com.xnotes.storage.PdfResources.Unavailable::class.java) {resources.admitHeld(first,"must not become a new PDF")}
        assertEquals(before,resources.directory.listFiles()!!.map {it.name}.toSet())
        assertTrue(original.exists());assertTrue(first.exists())
    }
    @Test fun aPortablePdfIsLeasedThroughoutEncodingAndReleasesItsLeaseAfterCancellation()=scenario {f->
        val resources=f.storage.pdfResources
        val original=File(f.root,"live.pdf").apply {writeText("%PDF-live draft")}
        val managed=resources.admit(original,"draft")
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        val doc=Document.blank().apply {pdfFile=original}
        var checkedLease=false
        assertThrows(CancellationException::class.java) {f.store.publish(f.store.capture(doc,null,f.view)) {
            // Once admitted, every codec cancellation poll runs with a live PDF lease.
            val leases=f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();it.getInt(0)}
            if(leases>0) {
                checkedLease=true;resources.collect();assertTrue(managed.isFile);throw CancellationException("during PDF encoding")
            }
        }}
        assertTrue("Cancellation must occur with the encoding lease held",checkedLease)
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();assertEquals("Cancelled encoding must release its PDF lease",0,it.getInt(0))}
        // register/resolve refreshes the resource grace time; age it again to exercise collection.
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        resources.collect();assertFalse("Released cache-only PDF is collectable",managed.exists())
        assertTrue(original.isFile);assertSame(original,doc.pdfFile);assertNull(doc.identity.pdfResource)
    }
    @Test fun anUnmanagedOriginalCannotHideItsCorruptManagedCopyBehindTwoStampCaches()=scenario {f->
        val resources=f.storage.pdfResources
        val original=File(f.root,"good.pdf").apply {writeText("%PDF-good!")}
        val managed=resources.admit(original,"original")
        resources.holdResolved(setOf(managed.nameWithoutExtension)).close()
        val stamp=managed.lastModified();val size=managed.length()
        managed.writeText("%PDF-bad!!");assertEquals(size,managed.length());assertTrue(managed.setLastModified(stamp))
        assertArrayEquals("%PDF-good!".toByteArray(),original.readBytes())
        assertThrows(com.xnotes.storage.PdfResources.Unavailable::class.java) {resources.admitHeld(original,"no cached corruption")}
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();assertEquals(0,it.getInt(0))}
        assertTrue(original.isFile);assertTrue(managed.isFile)
    }
    @Test fun cancellationInterruptsTheActualPdfCopyAndLeavesNoTemporaryResourceOrLease()=scenario {f->
        val resources=f.storage.pdfResources
        val original=File(f.root,"large.pdf").apply {writeBytes(ByteArray(8*1024*1024) {42})}
        var copied=0L
        assertThrows(CancellationException::class.java) {resources.admitHeld(original,"cancel during copy") {
            resources.directory.listFiles().orEmpty().firstOrNull {it.name.startsWith("resource-") && it.length()>0}?.let {
                copied=it.length();throw CancellationException("stop streaming admission")
            }
        }}
        assertTrue("Must interrupt the copy after it starts",copied>0)
        assertTrue("Must not finish reading the PDF before cancellation",copied<original.length())
        assertTrue(resources.directory.listFiles().orEmpty().isEmpty());assertTrue(original.isFile)
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();assertEquals(0,it.getInt(0))}
    }
    @Test fun aReadLeaseKeepsTheOlderPortableBodyUntilItIsReleased()=scenario {f->
        val doc=Document.blank();f.store.publish(f.store.capture(doc,null,f.view));val old=f.store.load()!!;val body=old.body
        doc.bookmarks.add(Bookmark(0,"later"));doc.dirty=true;f.store.publish(f.store.capture(doc,null,f.view))
        assertTrue(body.exists());old.close();assertFalse(body.exists())
    }
    @Test fun aBadViewFailsBeforeSupersedingTheGoodPendingRequest()=scenario {f->
        val doc=Document.blank();val good=f.store.capture(doc,null,f.view)
        for(view in listOf(f.view.copy(zoom=Double.NaN),f.view.copy(zoom=-1.0),f.view.copy(overrides=ViewOverrides(rotation=360)),
            f.view.copy(overrides=ViewOverrides(contrast=201)),f.view.copy(overrides=ViewOverrides(multiply=Rgba(1,2,3,0))))) {
            assertThrows(IllegalArgumentException::class.java) {f.store.capture(doc,null,view)}
        }
        assertTrue(f.store.publish(good).published)
    }
    @Test fun unknownOrBadMetadataCannotBeReadOverwrittenClearedOrAuthorizeCollection() {
        val changes=listOf<(JSONObject)->Unit>(
            {it.put("version",99)}, {it.put("binding","copy_pending")}, {it.remove("dirty")}, {it.put("dirty","false")},
            {it.put("displayName",JSONObject.NULL)}, {it.put("path","content://must-not-bind")},
            {it.getJSONObject("view").put("zoom","2.0")}, {it.getJSONObject("view").put("zoomLocked",JSONObject.NULL)},
            {it.getJSONObject("view").getJSONObject("overrides").put("rotation",45)},
            {it.getJSONObject("view").getJSONObject("overrides").put("mode","future")},
            {it.getJSONObject("view").getJSONObject("overrides").put("invert",101)},
        )
        for(change in changes) scenario {f->
            val doc=Document.blank();f.store.publish(f.store.capture(doc,null,f.view));val held=f.store.load()!!
            // Publish malformed metadata through the generic test fixture while holding the old read.
            val generic=WorkingSessionCache(f.directory,{_,_,_->})
            val head=JSONObject(File(f.directory,"head.json").readText());val metadata=head.getJSONObject("metadata");change(metadata)
            val raw=generic.reserve(Any(),WorkingSessionCache.Kind.NOTE,doc.identity.documentId,99,metadata)
            generic.publish(raw) {out->f.noteCodec.write(doc.snapshot(),out)}
            val oldBody=held.body;val before=f.allBytes()
            assertThrows(WorkingSessionCache.Invalid::class.java) {f.store.load()}
            assertThrows(WorkingSessionCache.Invalid::class.java) {f.store.publish(f.store.capture(doc,null,f.view))}
            assertThrows(WorkingSessionCache.Invalid::class.java) {f.store.publishClear(f.store.reserveClear())}
            held.close();assertTrue(oldBody.exists());assertEquals(before,f.allBytes())
        }
    }
    @Test fun malformedBoundOriginsAreNeverDemotedToUnboundOrCoerced() {
        val changes=listOf<(JSONObject)->Unit>(
            {it.put("source",JSONObject.NULL)}, {it.remove("source")}, {it.getJSONObject("source").put("uri",17)},
            {it.getJSONObject("source").put("locationKey","another location")},
            {it.getJSONObject("source").put("documentId",java.util.UUID.randomUUID().toString())},
            {it.getJSONObject("source").put("kind","xcanvas")}, {it.getJSONObject("source").put("sha256","bad")},
        )
        for(change in changes) scenario {f->
            val (doc,source)=f.bound();f.store.publish(f.store.capture(doc,source,f.view))
            val generic=WorkingSessionCache(f.directory,{_,_,_->});val head=JSONObject(File(f.directory,"head.json").readText())
            val metadata=head.getJSONObject("metadata");change(metadata)
            generic.publish(generic.reserve(Any(),WorkingSessionCache.Kind.NOTE,doc.identity.documentId,88,metadata)) {out->f.noteCodec.write(doc.snapshot(),out)}
            val before=f.allBytes()
            assertThrows(WorkingSessionCache.Invalid::class.java) {f.store.load()}
            assertThrows(WorkingSessionCache.Invalid::class.java) {f.store.publishClear(f.store.reserveClear())}
            assertEquals(before,f.allBytes())
        }
    }
    @Test fun malformedClearEnvelopesCannotHideFutureMetadataOrAuthorizeDeletingBodies() {
        for(text in listOf("""{"version":1,"state":"cleared","metadata":{"version":99}}""",
            """{"version":1,"state":"active","state":"cleared"}""")) scenario {f->
            f.directory.mkdirs()
            val body=File(f.directory,"body-${java.util.UUID.randomUUID()}.zip")
            body.outputStream().use {f.noteCodec.write(Document.blank().snapshot(),it)}
            File(f.directory,"head.json").writeText(text)
            val before=f.allBytes()
            assertThrows(WorkingSessionCache.Invalid::class.java) {f.store.load()}
            assertThrows(WorkingSessionCache.Invalid::class.java) {f.store.publishClear(f.store.reserveClear())}
            assertEquals(before,f.allBytes());assertTrue(body.exists())
        }
    }

}
