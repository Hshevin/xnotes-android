package com.xnotes.pen

import android.provider.DocumentsContract
import com.xnotes.core.history.AddItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundlePdf
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.NativeReferenceIssue
import com.xnotes.storage.NativeReferenceTarget
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferencePreparation
import com.xnotes.ui.NativeReferenceRequest
import com.xnotes.ui.NativeReferenceResult
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual native source identity, model owners, publication and legacy-session restoration. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeReferenceHostTest {
    private fun ready(value:NativeReferencePreparation):NativeReferenceRequest {
        assertTrue("Expected a frozen source request, got $value",value is NativeReferencePreparation.Ready)
        return (value as NativeReferencePreparation.Ready).request
    }
    private fun opened(value:NativeReferenceResult):NativeReferenceResult.Opened {
        assertTrue("Expected actual source adoption, got $value",value is NativeReferenceResult.Opened)
        return value as NativeReferenceResult.Opened
    }
    private fun add(editor:Editor,x:Double) {
        val page=editor.state.document.pages.first()
        val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,120.0,.5),Sample(x+20,150.0,.75)))
        page.items+=stroke;editor.history.push(AddItem(page,stroke));editor.state.document.dirty=true
    }
    private suspend fun drain(editor:Editor) {
        repeat(3) {
            parkingDispatch()
            for(pane in listOfNotNull(editor,editor.secondary)) {
                val jobs=withContext(Dispatchers.Main) {listOf("noteWriteJob","canvasWriteJob","sessionWriteJob").mapNotNull {
                    Editor::class.java.getDeclaredField(it).apply {isAccessible=true}.get(pane) as? Job}}
                jobs.forEach {it.join()}
                (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(pane) as LegacySessionQueue.Owner).awaitAccepted()
            }
        }
    }
    private fun rewrite(file:File,edit:(String,ByteArray)->ByteArray?) {
        val entries=ZipFile(file).use {zip->zip.entries().asSequence().map {it.name to zip.getInputStream(it).use {input->input.readBytes()}}.toList()}
        val temp=File(file.parentFile,"${file.name}.replacement")
        ZipOutputStream(temp.outputStream()).use {zip->for((name,bytes) in entries) edit(name,bytes)?.let {
            zip.putNextEntry(ZipEntry(name));zip.write(it);zip.closeEntry()
        }}
        temp.copyTo(file,overwrite=true);temp.delete()
    }

    @Test fun liveSecondaryKeepsItsOwnerDirtyHistoryAndSinglePaneSessionAfterTheOtherOwnersSaveAs()=runParkingScenario {f->
        val source=openParkingPanes(f)
        val model=source.state.document;val page=model.pages[1];val history=source.history
        val target=NativeReferenceTarget(model.identity.documentId,page.referenceIdentity.id!!)
        withContext(Dispatchers.Main) {add(source,330.0);add(f.editor,110.0)}
        val request=withContext(Dispatchers.Main) {ready(f.editor.prepareReference(target))}
        assertSame(source,request.keptOwner);assertEquals(listOf(f.editor),request.guardOwners)
        val done=CompletableDeferred<Boolean>();val primaryModel=f.editor.state.document
        val retryUri=DocumentsContract.buildDocumentUri(f.authority,"retry").toString()
        withContext(Dispatchers.Main) {f.editor.saveAsToThen(retryUri,"saved-other.xnote") {done.complete(it)}}
        assertTrue(done.await())
        withContext(Dispatchers.Main) {
            assertSame(primaryModel,f.editor.state.document);assertTrue("Another owner's same-model SaveAs must not retarget the source",request.current())
            var committed=0
            val result=opened(f.editor.openReference(request,onOpened={actual->
                committed++;assertSame(source,actual.pane);assertFalse(f.editor.noteOpen);assertTrue(source.noteOpen)
            }))
            assertEquals(1,committed);assertSame(source,result.pane);assertFalse(f.editor.inSplit)
            assertEquals(listOf(source),f.editor.openPanes);assertSame(model,source.state.document);assertSame(page,model.pages[1])
            assertSame(history,source.history);assertTrue(history.canUndo);assertTrue(model.dirty)
            assertFalse(f.editor.noteOpen);assertEquals(target.documentId,source.visibleDocumentId)
            f.editor.persist()
        }
        drain(f.editor)
        val oldRoot=f.editor
        withContext(Dispatchers.Main) {
            for(pane in listOfNotNull(oldRoot,oldRoot.secondary)) {
                val scope=Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(pane) as CoroutineScope
                scope.cancel();scope.coroutineContext[Job]?.join()
            }
            f.editor=Editor(f.context)
            f.editor.restoreSession();f.editor.restoreSplitWorkspace()
            assertFalse("Closed primary must not resurrect as a MissingDocument split",f.editor.noteOpen)
            assertNull(f.editor.missingDocument);assertEquals(1,f.editor.openPanes.size)
            val restored=requireNotNull(f.editor.secondary)
            assertTrue(restored.noteOpen);assertSame(restored,f.editor.active)
            assertEquals(target.documentId,restored.visibleDocumentId)
            assertEquals(target.pageId,restored.state.document.pages[1].referenceIdentity.id)
            assertEquals(1,restored.state.document.pages.first().items.filterIsInstance<Stroke>().size)
        }
    }

    @Test fun liveDeletedOrDuplicatePermanentPagesNeverFallBackToTheStillValidDiskPage() {
        for(duplicate in listOf(false,true)) runParkingScenario {f->
            val source=openParkingPanes(f);val doc=source.state.document;val id=doc.pages[1].referenceIdentity.id!!
            val bytes=f.target.readBytes();val primary=f.editor.state.document
            withContext(Dispatchers.Main) {
                if(duplicate) {doc.pages.add(doc.pages[1]);doc.dirty=true} else source.deletePages(listOf(1))
                val result=f.editor.prepareReference(NativeReferenceTarget(doc.identity.documentId,id))
                assertTrue("duplicate=$duplicate result=$result pages=${doc.pages.size}",result is NativeReferencePreparation.Failed)
                assertEquals(if(duplicate) NativeReferenceIssue.UNAVAILABLE else NativeReferenceIssue.DELETED,(result as NativeReferencePreparation.Failed).issue)
                assertSame(doc,source.state.document);assertSame(primary,f.editor.state.document)
                assertTrue(source.noteOpen);assertTrue(f.editor.noteOpen);assertTrue(doc.dirty)
            }
            assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }

    @Test fun oneOpenLiveOwnerCannotBypassKnownDuplicateLocationsAtPreparationOrFinalAdoption() {
        for(late in listOf(false,true)) runParkingScenario {f->
            withContext(Dispatchers.IO) {f.seed()}
            val model=withContext(Dispatchers.Main) {f.editor.openAsync(f.sourceUri,"source.xnote");add(f.editor,120.0);f.editor.state.document}
            val target=NativeReferenceTarget(model.identity.documentId,model.pages[1].referenceIdentity.id!!)
            val original=f.source.readBytes()
            val request=if(late) withContext(Dispatchers.Main) {ready(f.editor.prepareReference(target))} else null
            withContext(Dispatchers.IO) {
                f.source.copyTo(f.target,overwrite=true)
                f.storage.registry.registerObserved(com.xnotes.format.BundleHeader.read(f.target),"xnote","duplicate.xnote",f.targetUri)
            }
            withContext(Dispatchers.Main) {
                if(request==null) {
                    val result=f.editor.prepareReference(target)
                    assertTrue(result is NativeReferencePreparation.Failed)
                    assertEquals(NativeReferenceIssue.DUPLICATE,(result as NativeReferencePreparation.Failed).issue)
                } else {
                    var commits=0
                    val result=f.editor.openReference(request,onOpened={commits++})
                    assertTrue(result is NativeReferenceResult.Failed)
                    assertEquals(NativeReferenceIssue.DUPLICATE,(result as NativeReferenceResult.Failed).issue)
                    assertEquals(0,commits)
                }
                assertSame(model,f.editor.state.document);assertTrue(model.dirty);assertTrue(f.editor.history.canUndo)
                assertEquals(listOf(f.editor),f.editor.openPanes)
            }
            assertArrayEquals(original,f.source.readBytes());assertArrayEquals(original,f.target.readBytes())
            assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        }
    }

    @Test fun storedMissingOrForeignIdentityAndBadPageContractsNeverMigrateOrDiscardTheOutgoingDirtyModel() {
        for(mode in listOf("missingIdentity","foreignIdentity","missingMarker","duplicatePage")) runParkingScenario {f->
            withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
            val info=BundleReferenceInfo.inspectStrict(f.target);val target=NativeReferenceTarget(info.header.identity!!.documentId,info.orderedPageIds[1])
            when(mode) {
                "missingIdentity"->rewrite(f.target) {name,bytes->if(name==BundleIdentity.ENTRY) null else bytes}
                "foreignIdentity"->f.seed(f.target)
                "missingMarker"->rewrite(f.target) {name,bytes->if(name==AnchorMetadata.ENTRY) null else bytes}
                else->rewrite(f.target) {name,bytes->if(name=="manifest.json") bytes.toString(Charsets.UTF_8)
                    .replace(info.orderedPageIds[1],info.orderedPageIds[0]).toByteArray() else bytes}
            }
            val original=f.target.readBytes();val primary=withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"source.xnote");add(f.editor,110.0);f.editor.state.document
            }
            withContext(Dispatchers.Main) {
                val prepared=f.editor.prepareReference(target,selectedUri=f.targetUri)
                if(prepared is NativeReferencePreparation.Ready) {
                    var commits=0
                    val result=f.editor.openReference(prepared.request,setOf(f.editor.discardForOpen()),onOpened={commits++})
                    assertTrue(mode,result is NativeReferenceResult.Failed);assertEquals(0,commits)
                } else assertTrue(mode,prepared is NativeReferencePreparation.Failed)
                assertSame(primary,f.editor.state.document);assertTrue(primary.dirty);assertTrue(f.editor.history.canUndo);assertTrue(f.editor.noteOpen)
            }
            assertArrayEquals(original,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
            assertFalse(f.storage.hasRecovery(f.targetUri))
        }
    }

    @Test fun storedOpenUsesTheActualDisplayNameAndPreservesTheKnownNameWhenTheProviderHasNone() {
        for(named in listOf(true,false)) runParkingScenario {f->
            withContext(Dispatchers.IO) {
                f.seed();f.seed(f.target)
                f.storage.registry.registerObserved(com.xnotes.format.BundleHeader.read(f.target),"xnote","remembered-source.xnote",f.targetUri)
            }
            val info=BundleReferenceInfo.inspectStrict(f.target)
            val target=NativeReferenceTarget(requireNotNull(info.header.identity).documentId,info.orderedPageIds[1])
            f.targetDisplayName=if(named) "Actual readable source.xnote" else ""
            val expected=if(named) f.targetDisplayName else "remembered-source.xnote"
            withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"source.xnote")
                val request=ready(f.editor.prepareReference(target,selectedUri=f.targetUri))
                opened(f.editor.openReference(request))
                assertEquals(expected,f.editor.state.document.displayName)
            }
            assertEquals(expected,f.storage.registry.nameForId(target.documentId))
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }

    @Test fun storedLateReadsRejectAChangedIdentityAndActuallyAdoptAValidReorderedPageById() {
        for(foreign in listOf(true,false)) runParkingScenario {f->
            withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
            val original=BundleReferenceInfo.inspectStrict(f.target);val target=NativeReferenceTarget(original.header.identity!!.documentId,original.orderedPageIds[1])
            val old=withContext(Dispatchers.Main) {f.editor.openAsync(f.sourceUri,"source.xnote");f.editor.view.layout(0,0,1000,1000);f.editor.state.document}
            val request=withContext(Dispatchers.Main) {ready(f.editor.prepareReference(target,selectedUri=f.targetUri))}
            val entered=CompletableDeferred<Unit>();val reads=AtomicInteger()
            f.beforeTargetRead={if(reads.incrementAndGet()==2) {entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}}
            coroutineScope {
                val opening=async(Dispatchers.Main) {f.editor.openReference(request)}
                entered.await()
                withContext(Dispatchers.IO) {
                    if(foreign) f.seed(f.target) else {
                        val changed=f.target.inputStream().use {f.codec.read(it)}
                        val page=changed.pages.removeAt(1);changed.pages.add(0,page)
                        f.target.outputStream().use {f.codec.write(changed.fixedWriteSnapshot(),it)}
                    }
                }
                f.release.countDown();val result=opening.await()
                withContext(Dispatchers.Main) {
                    if(foreign) {assertTrue(result is NativeReferenceResult.Failed);assertSame(old,f.editor.state.document)}
                    else {
                        assertSame(f.editor,opened(result).pane);assertNotSame(old,f.editor.state.document)
                        assertEquals(target.documentId,f.editor.visibleDocumentId)
                        assertEquals(target.pageId,f.editor.state.document.pages.first().referenceIdentity.id)
                        assertEquals(0,f.editor.state.currentPageIndex())
                        assertEquals(BundleMigration.hash(f.target),f.editor.lastReadSourceVersion()?.sha256)
                    }
                }
            }
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }

    @Test fun anIdentifiedEmbeddedPdfV1SourceOpensWithoutCompactionAndItsStrictEditedSaveKeepsThePdfBytes()=runParkingScenario {f->
        val bytes=minimalPdf();val input=File(f.root,"original.pdf").apply {writeBytes(bytes)}
        withContext(Dispatchers.IO) {
            f.seed()
            val portable=Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0)),pdfFile=input).apply {enableReferenceAnchors()}
            f.target.outputStream().use {f.codec.write(portable.fixedWriteSnapshot(),it,embedPdf=true)}
        }
        val original=f.target.readBytes();val info=BundleReferenceInfo.inspectStrict(f.target)
        val identity=requireNotNull(info.header.identity)
        assertFalse(identity.externalPdf)
        val target=NativeReferenceTarget(identity.documentId,info.orderedPageIds[1])
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            val request=ready(f.editor.prepareReference(target,selectedUri=f.targetUri))
            opened(f.editor.openReference(request));assertArrayEquals(original,f.target.readBytes())
            assertEquals(0,f.writes.get());assertEquals(info.header.identity,f.editor.state.document.identity)
            add(f.editor,110.0)
            assertTrue("A strict embedded-v1 source must save without silently changing its packaging contract",f.editor.flushSplitDocument())
            assertFalse(f.editor.state.document.dirty);assertEquals(info.header.identity,f.editor.state.document.identity)
        }
        val saved=BundleReferenceInfo.inspectStrict(f.target)
        assertEquals(info.header.identity,saved.header.identity);assertEquals(info.embeddedPdfHash,saved.embeddedPdfHash)
        assertEquals(info.orderedPageIds,saved.orderedPageIds)
        ZipFile(f.target).use {zip->assertArrayEquals(bytes,zip.getInputStream(zip.getEntry(BundlePdf.ENTRY)).readBytes())}
        assertArrayEquals(bytes,input.readBytes());assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        // This exercises byte/lease/save contracts, not PDFium painting or target-GPU rendering.
    }
    @Test fun aDetachedSourceWaitsForItsSameSizeActualHostAndStartsItsMarkerLifetimeThere()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            val activity=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
            val holder=android.widget.FrameLayout(activity.get())
            fun layout() {
                holder.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY))
                holder.layout(0,0,1000,1000)
            }
            try {
                f.editor.openAsync(f.sourceUri,"source.xnote")
                activity.get().setContentView(holder)
                // Keep Editor's real canvas/surface hierarchy; GPU drawing is outside this View lifecycle oracle.
                f.editor.pad.visibility=android.view.View.GONE
                holder.addView(f.editor.surfaces,android.widget.FrameLayout.LayoutParams(-1,-1));layout()
                assertTrue("The real native canvas is attached to the actual Window",f.editor.view.isAttachedToWindow)
                val doc=f.editor.state.document;val page=doc.pages[1];add(f.editor,120.0)
                f.editor.state.verticalScroll=false;f.editor.state.goToPage(0)
                val revision=doc.bodyRevision;val history=f.editor.history;val before=doc.dirty
                holder.removeView(f.editor.surfaces);assertFalse(f.editor.view.isAttachedToWindow)
                val request=ready(f.editor.prepareReference(NativeReferenceTarget(doc.identity.documentId,page.referenceIdentity.id!!)))
                opened(f.editor.openReference(request))
                assertEquals("Detached old dimensions must not consume the new navigation",0,f.editor.state.currentPageIndex())
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(com.xnotes.ui.theme.ReferenceLocatorStyle.DurationMillis+1))
                val reattached=f.editor.surfacesForAttach()
                f.editor.pad.visibility=android.view.View.GONE
                holder.addView(reattached,android.widget.FrameLayout.LayoutParams(-1,-1));layout()
                repeat(3) {f.editor.view.viewTreeObserver.dispatchOnPreDraw()}
                assertEquals(1,f.editor.state.currentPageIndex())
                val locator=(Editor::class.java.getDeclaredField("referenceLocator\$delegate").apply {isAccessible=true}.get(f.editor) as Lazy<*>).value as com.xnotes.ui.NativeReferenceLocator
                fun outlines():Int {var count=0;locator.draw(object:com.xnotes.core.pal.Renderer by com.xnotes.core.FakeRenderer() {
                    override fun strokeRect(rect:com.xnotes.core.geometry.Rect,pen:com.xnotes.core.pal.Pen) {count++}
                });return count}
                assertEquals("Lifetime begins at the newly visible host, not the old parked dimensions",1,outlines())
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(com.xnotes.ui.theme.ReferenceLocatorStyle.DurationMillis+1))
                assertEquals(0,outlines());assertSame(history,f.editor.history);assertEquals(revision,doc.bodyRevision);assertEquals(before,doc.dirty)
            } finally {holder.removeAllViews();activity.pause().stop().destroy()}
        }
    }

    @Test fun aFreshNativePenDownRetiresPendingLocationWithoutMovingTheActiveStrokeCamera()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            val activity=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
            val holder=android.widget.FrameLayout(activity.get())
            try {
                f.editor.openAsync(f.sourceUri,"source.xnote")
                activity.get().setContentView(holder)
                val host=f.editor.surfacesForAttach();f.editor.pad.visibility=android.view.View.GONE
                holder.addView(host,android.widget.FrameLayout.LayoutParams(-1,-1))
                holder.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY))
                holder.layout(0,0,1000,1000);assertTrue(f.editor.view.isAttachedToWindow)
                f.editor.selectTool(Tool.PEN)
                val doc=f.editor.state.document;val page=doc.pages[1]
                f.editor.state.verticalScroll=false;f.editor.state.goToPage(0)
                val request=ready(f.editor.prepareReference(NativeReferenceTarget(doc.identity.documentId,page.referenceIdentity.id!!)))
                opened(f.editor.openReference(request))
                val camera=f.editor.state.scrollX to f.editor.state.scrollY
                parkingPen(f.editor,android.view.MotionEvent.ACTION_DOWN,com.xnotes.core.geometry.Pt(100.0,100.0))
                repeat(3) {f.editor.view.viewTreeObserver.dispatchOnPreDraw()}
                assertEquals("New pen intent must retire delayed navigation before it can move the active-stroke camera",0,f.editor.state.currentPageIndex())
                assertEquals(camera,f.editor.state.scrollX to f.editor.state.scrollY)
                parkingPen(f.editor,android.view.MotionEvent.ACTION_MOVE,com.xnotes.core.geometry.Pt(140.0,120.0))
                parkingPen(f.editor,android.view.MotionEvent.ACTION_UP,com.xnotes.core.geometry.Pt(170.0,140.0))
                assertEquals(1,doc.pages[0].items.filterIsInstance<Stroke>().size)
                assertTrue(page.items.isEmpty());assertTrue(doc.dirty);assertTrue(f.editor.history.canUndo)
                assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
            } finally {holder.removeAllViews();activity.pause().stop().destroy()}
        }
    }

    @Test fun pageHintsUseTheActualFootprintIncludingNegativeMarginsAndInsertedRoom()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            val doc=f.editor.state.document;val page=doc.pages[1]
            page.margins=com.xnotes.core.model.PageMargins(left=.25)
            page.rooms=listOf(com.xnotes.core.model.Room(250.0,100.0));page.height+=100.0
            doc.dirty=true;f.editor.state.relayout()
            val target=NativeReferenceTarget(doc.identity.documentId,page.referenceIdentity.id!!)
            val history=f.editor.history;val revision=doc.bodyRevision
            for(hint in listOf(com.xnotes.core.geometry.Rect(-100.0,20.0,50.0,30.0),com.xnotes.core.geometry.Rect(20.0,540.0,30.0,40.0))) {
                val request=ready(f.editor.prepareReference(target,hint))
                assertEquals(com.xnotes.ui.ReferenceRegionReveal.SHOWN,opened(f.editor.openReference(request)).region)
            }
            assertSame(history,f.editor.history);assertEquals(revision,doc.bodyRevision);assertTrue(doc.dirty)
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        }
    }

    private fun minimalPdf():ByteArray {
        val out=java.io.ByteArrayOutputStream();fun ascii(value:String) {out.write(value.toByteArray(Charsets.US_ASCII))}
        ascii("%PDF-1.4\n");val objects=listOf("<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 4 0 R >>","<< /Length 4 >>\nstream\nq\nQ\nendstream")
        val offsets=IntArray(objects.size+1)
        objects.forEachIndexed {at,body->offsets[at+1]=out.size();ascii("${at+1} 0 obj\n$body\nendobj\n")}
        val xref=out.size();ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for(at in 1 until offsets.size) ascii(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offsets[at]))
        ascii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n");return out.toByteArray()
    }
}
