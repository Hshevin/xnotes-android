package com.xnotes.pen

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.platform.NativeWorkingSession
import com.xnotes.platform.RecoveryLegacyArtifact
import com.xnotes.platform.RecoveryPreviewLoad
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.Editor
import com.xnotes.ui.Pane
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Editor -> existing slot owner/FIFO -> persisted bundle -> readonly preparation. */
abstract class EditorRecoveryPreviewContract {
    protected abstract fun runPreviewScenario(block:suspend (Context,File)->Unit)

    private class Fixture(val context:Context,val root:File) {
        val editors=mutableListOf<Editor>()
        lateinit var editor:Editor
        val noteCodec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val canvasCodec=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val sourceUri="content://recovery-preview/document/original"
        val sourceFile=File(root,"actual-original.xnote")
        var reads=0
        var writes=0
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read():java.io.InputStream {reads++;return sourceFile.inputStream()}
            override fun write(source:File) {writes++;source.copyTo(sourceFile,overwrite=true)}
        }})
        private val nativeStores=mutableMapOf<Editor,NativeWorkingSession>()
        fun owner(editor:Editor)=Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}
            .get(editor) as LegacySessionQueue.Owner
        fun slot(editor:Editor)=Editor::class.java.getDeclaredField("sessionDir").apply {isAccessible=true}.get(editor) as File
        fun native(editor:Editor)=nativeStores.getOrPut(editor) {
            NativeWorkingSession(slot(editor),noteCodec,canvasCodec,storage.pdfResources)
        }
        fun view()=NativeWorkingSession.NoteView(1.0,0.0,0.0,false,ViewOverrides())
        fun stroke(x:Double)=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,20.0,.5),Sample(x+30,40.0,.75)))
        fun seed(editor:Editor,x:Double) {
            val slot=slot(editor).apply {mkdirs()}
            val note=Document(mutableListOf(Page(500.0,500.0))).apply {pages.single().items.add(stroke(x))}
            val canvas=InfiniteDocument().apply {add(stroke(x+1000))}
            File(slot,"document.xnote").outputStream().use {noteCodec.write(note,it)}
            File(slot,"canvas.xcanvas").outputStream().use {canvasCodec.write(canvas,it)}
            File(slot,"session.json").writeText(JSONObject().put("displayName","Historical note $x")
                .put("path",sourceUri).toString())
            File(slot,"canvas-session.json").writeText(JSONObject().put("display_name","Historical canvas $x")
                .put("path",sourceUri).toString())
        }
        fun legacyBytes(editor:Editor)=slot(editor).listFiles().orEmpty().filter {
            it.name in setOf("document.xnote","canvas.xcanvas","session.json","canvas-session.json") &&
                Files.isRegularFile(it.toPath(),NOFOLLOW_LINKS)
        }.associate {it.name to it.readBytes().toList()}
        fun ownedTemporaryNames()=root.walkTopDown().filter {
            it.name.startsWith("bundle-source-") || it.name.startsWith("native-open-")
        }.map {it.relativeTo(root).path}.toSet()
        suspend fun load(editor:Editor=this.editor,artifact:RecoveryLegacyArtifact?=null,check:()->Unit={}):RecoveryPreviewLoad=
            withContext(Dispatchers.Main) {editor.prepareRecoveryPreview(artifact,check)}
    }
    private suspend fun scenario(context:Context,root:File,block:suspend (Fixture)->Unit) {
        val f=Fixture(context,root)
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=singleton.get(null)
        try {
            withContext(Dispatchers.Main) {singleton.set(null,f.storage);f.editor=Editor(context).also {f.editors.add(it)}}
            withTimeout(15000L) {block(f)}
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    val scopes=f.editors.map {Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(it) as CoroutineScope}
                    scopes.forEach {it.cancel()};scopes.forEach {it.coroutineContext[Job]?.join()}
                    suspendCancellableCoroutine<Unit> {waiter->Handler(Looper.getMainLooper()).post {waiter.resumeWith(Result.success(Unit))}}
                }
                f.editors.forEach {f.owner(it).awaitAccepted()}
                singleton.set(null,previous)
                withContext(Dispatchers.IO) {f.storage.registry.close()}
            }
        }
    }
    private suspend fun awaitLatch(latch:CountDownLatch,label:String)=withContext(Dispatchers.IO) {
        assertTrue(label,latch.await(5,TimeUnit.SECONDS))
    }
    private suspend fun expectCancellation(block:suspend ()->Unit) {
        try {block();fail("A retired/cancelled request must not produce a load result")}
        catch(_:CancellationException) { }
    }
    private fun assertReady(result:RecoveryPreviewLoad)=
        (result as? RecoveryPreviewLoad.Ready)?.prepared ?: error("Expected readonly preparation, got $result")

    @Test fun bothLegacyArtifactsAreExplicitAndSharedSecondaryStillReadsItsOwnPhysicalSlot()=runPreviewScenario {context,root->
        scenario(context,root) {f->
            val second=withContext(Dispatchers.Main) {
                f.editor.newNote();f.editor.view.layout(0,0,1000,700)
                f.editor.secondaryPane().also {s->f.editors.add(s);assertTrue(s.openSharedView(f.editor))
                    s.view.layout(0,0,1000,700);f.editor.focusedPane=Pane.SECONDARY}
            }
            assertSame(second,withContext(Dispatchers.Main) {f.editor.active})
            assertSame(f.editor,withContext(Dispatchers.Main) {second.documentOwner})
            val original=withContext(Dispatchers.Main) {
                f.editor.state.document.dirty=true
                listOf(f.editor,second).associateWith {Triple(it.state.document,it.state.document.dirty,it.readingPosition())}
            }
            withContext(Dispatchers.IO) {f.seed(f.editor,11.0);f.seed(second,77.0)}
            val primaryBytes=f.legacyBytes(f.editor);val secondaryBytes=f.legacyBytes(second)
            for((pane,x) in listOf(f.editor to 11.0,second to 77.0)) {
                val list=f.load(pane) as RecoveryPreviewLoad.Absent
                assertEquals(setOf(RecoveryLegacyArtifact.NOTE,RecoveryLegacyArtifact.CANVAS),list.legacy)
                for(kind in listOf(RecoveryLegacyArtifact.NOTE,RecoveryLegacyArtifact.CANVAS)) {
                    assertReady(f.load(pane,kind)).use {preview->
                        if(kind==RecoveryLegacyArtifact.NOTE) {
                            assertEquals(DocumentKind.NOTE,preview.kind);assertNull(preview.note!!.path)
                            assertEquals(x,(preview.note!!.pages.single().items.single() as Stroke).xAt(0),.001)
                        } else {
                            assertEquals(DocumentKind.CANVAS,preview.kind);assertNull(preview.canvas!!.path)
                            assertEquals(x+1000,(preview.canvas!!.items.single() as Stroke).xAt(0),.001)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {original.forEach {(pane,before)->
                assertSame(before.first,pane.state.document);assertEquals(before.second,pane.state.document.dirty)
                assertEquals(before.third,pane.readingPosition())
            }}
            assertEquals(primaryBytes,f.legacyBytes(f.editor));assertEquals(secondaryBytes,f.legacyBytes(second))
            assertEquals(0,f.reads);assertEquals(0,f.writes)
        }
    }

    @Test fun nativeHeadsNeverFallBackAndBoundMetadataNeverOpensAnOrigin() {
        for(case in listOf("cleared","bad","future","symlink","bound","unbound")) runPreviewScenario {context,root->
            scenario(context,root) {f->
                withContext(Dispatchers.IO) {f.seed(f.editor,13.0)}
                val old=f.legacyBytes(f.editor);val native=f.native(f.editor)
                when(case) {
                    "cleared"->assertTrue(native.publishClear(native.reserveClear()).published)
                    "bad"->File(f.slot(f.editor),"head.json").writeText("broken JSON")
                    "future"->File(f.slot(f.editor),"head.json").writeText("""{"version":99,"state":"cleared"}""")
                    "symlink"->Files.createSymbolicLink(File(f.slot(f.editor),"head.json").toPath(),File(root,"missing-head").toPath())
                    "bound"->{
                        f.sourceFile.outputStream().use {f.noteCodec.write(Document.blank(),it)}
                        val p=f.storage.prepareOpen(f.sourceUri,DocumentKind.NOTE,f.noteCodec,f.canvasCodec,
                            File(root,"pdfs").apply {mkdirs()},File(root,"decoded-source").apply {mkdirs()})
                        val doc=requireNotNull(p.note);doc.path=f.sourceUri;p.protectForAdoption();p.adopt(doc);p.close()
                        val source=p.editSource()
                        try {val write=withContext(Dispatchers.Main) {native.capture(doc,source,f.view())};assertTrue(native.publish(write).published)}
                        finally {source.invalidate()}
                        f.reads=0;f.writes=0
                    }
                    "unbound"->{
                        val doc=Document.blank().apply {pages.single().items.add(f.stroke(345.0));dirty=true}
                        val write=withContext(Dispatchers.Main) {native.capture(doc,null,f.view())}
                        assertTrue(native.publish(write).published)
                    }
                }
                val head=File(f.slot(f.editor),"head.json")
                val headBytes=if(Files.isRegularFile(head.toPath(),NOFOLLOW_LINKS)) head.readBytes() else null
                val result=f.load()
                when(case) {
                    "cleared"->assertTrue(result is RecoveryPreviewLoad.Cleared)
                    "bound"->assertTrue(result is RecoveryPreviewLoad.SourceRecoveryRequired)
                    "unbound"->assertReady(result).use {preview->
                        assertNull(preview.note!!.path);assertTrue(preview.note!!.dirty)
                        assertEquals(345.0,(preview.note!!.pages.single().items.single() as Stroke).xAt(0),.001)
                    }
                    else->{assertTrue(result is RecoveryPreviewLoad.Invalid);assertTrue((result as RecoveryPreviewLoad.Invalid).message.isNotBlank())}
                }
                headBytes?.let {assertArrayEquals(it,head.readBytes())}
                assertTrue(Files.exists(head.toPath(),NOFOLLOW_LINKS))
                assertEquals(old,f.legacyBytes(f.editor));assertEquals(0,f.reads);assertEquals(0,f.writes)
            }
        }
    }

    @Test fun selectingLegacyCannotRedirectToANativeHeadThatAppearsBeforeOrDuringSnapshot() {
        for(duringCopy in listOf(false,true)) runPreviewScenario {context,root->
            scenario(context,root) {f->
                withContext(Dispatchers.IO) {f.seed(f.editor,21.0)}
                val old=f.legacyBytes(f.editor);assertTrue(f.load() is RecoveryPreviewLoad.Absent)
                val native=f.native(f.editor)
                val write=withContext(Dispatchers.Main) {native.capture(Document.blank(),null,f.view())}
                val appeared=AtomicBoolean(false)
                if(!duringCopy) {assertTrue(native.publish(write).published);appeared.set(true)}
                val result=f.load(artifact=RecoveryLegacyArtifact.NOTE,check={
                    if(duringCopy && f.ownedTemporaryNames().any {it.substringAfterLast('/').startsWith("bundle-source-")} &&
                        appeared.compareAndSet(false,true)) assertTrue(native.publish(write).published)
                })
                assertTrue("Native head must really appear at the selected boundary",appeared.get())
                assertTrue("Do not redirect an explicit legacy choice to another recovery",result is RecoveryPreviewLoad.Invalid)
                assertTrue(File(f.slot(f.editor),"head.json").isFile)
                assertTrue(f.ownedTemporaryNames().isEmpty());assertEquals(old,f.legacyBytes(f.editor))
                assertEquals(0,f.reads);assertEquals(0,f.writes)
            }
        }
    }

    @Test fun cancellingAnAcceptedSnapshotAwaitsItsCleanupAndDoesNotPoisonTheExistingQueue()=runPreviewScenario {context,root->
        scenario(context,root) {f->coroutineScope {
            withContext(Dispatchers.IO) {f.seed(f.editor,32.0)}
            val before=f.legacyBytes(f.editor);val baseline=f.ownedTemporaryNames();val owner=f.owner(f.editor)
            val blockerEntered=CompletableDeferred<Unit>();val releasePrefix=CompletableDeferred<Unit>()
            val blocker=requireNotNull(owner.submit {blockerEntered.complete(Unit);releasePrefix.await()})
            blockerEntered.await()
            val prefixField=owner.javaClass.getDeclaredField("lastAccepted").apply {isAccessible=true}
            val initialPrefix=prefixField.get(owner)
            val copying=CountDownLatch(1);val releaseCopy=CountDownLatch(1);val paused=AtomicBoolean(false)
            val load=async(Dispatchers.Main) {f.editor.prepareRecoveryPreview(RecoveryLegacyArtifact.NOTE) {
                if(f.ownedTemporaryNames().any {it.substringAfterLast('/').startsWith("bundle-source-")} && paused.compareAndSet(false,true)) {
                    copying.countDown();check(releaseCopy.await(5,TimeUnit.SECONDS)) {"Snapshot test gate was not released"}
                }
            }}
            try {
                withTimeout(5000L) {
                    while(true) {
                        owner.isCurrent() // Acquire the registration lock before reading its published prefix.
                        if(prefixField.get(owner)!==initialPrefix) break
                        delay(1)
                    }
                }
                assertFalse("Capture must remain behind the existing accepted prefix",load.isCompleted)
                releasePrefix.complete(Unit);blocker.await();awaitLatch(copying,"Actual snapshot never reached its copy phase")
                load.cancel();releaseCopy.countDown();load.join()
                owner.awaitAccepted()
                assertTrue(paused.get());assertEquals(baseline,f.ownedTemporaryNames());assertEquals(before,f.legacyBytes(f.editor))
                val next=requireNotNull(owner.submit {"same FIFO remains usable"}).await()
                assertEquals(LegacySessionQueue.Outcome.Success("same FIFO remains usable"),next)
                assertReady(f.load(artifact=RecoveryLegacyArtifact.NOTE)).close()
                assertEquals(baseline,f.ownedTemporaryNames());assertEquals(0,f.reads);assertEquals(0,f.writes)
            } finally {withContext(NonCancellable) {
                releasePrefix.complete(Unit);releaseCopy.countDown();load.cancel();load.join();blocker.await();owner.awaitAccepted()
            }}
        }}
    }

    @Test fun missingSymlinkOrDirectoryArtifactDoesNotSwitchModesAndRetiredEditorCannotLoad() {
        for(case in listOf("missing","symlink","directory","retired")) runPreviewScenario {context,root->
            scenario(context,root) {f->
                withContext(Dispatchers.IO) {f.seed(f.editor,43.0)}
                val canvas=File(f.slot(f.editor),"canvas.xcanvas").readBytes()
                assertEquals(setOf(RecoveryLegacyArtifact.NOTE,RecoveryLegacyArtifact.CANVAS),(f.load() as RecoveryPreviewLoad.Absent).legacy)
                if(case=="retired") {
                    val old=f.owner(f.editor)
                    withContext(Dispatchers.Main) {f.editors.add(Editor(context))} // Actual replacement owner, not a test attach.
                    assertFalse(old.isCurrent())
                    expectCancellation {f.load(artifact=RecoveryLegacyArtifact.NOTE)}
                } else {
                    val note=File(f.slot(f.editor),"document.xnote");assertTrue(note.delete())
                    when(case) {
                        "symlink"->Files.createSymbolicLink(note.toPath(),File(root,"missing-note").toPath())
                        "directory"->assertTrue(note.mkdir())
                    }
                    assertTrue(f.load(artifact=RecoveryLegacyArtifact.NOTE) is RecoveryPreviewLoad.Invalid)
                }
                assertArrayEquals(canvas,File(f.slot(f.editor),"canvas.xcanvas").readBytes())
                assertTrue(f.ownedTemporaryNames().isEmpty());assertEquals(0,f.reads);assertEquals(0,f.writes)
            }
        }
    }
    @Test fun optionalOversizedLinkedOrMalformedSidecarCannotSupplyAPreviewName() {
        for(case in listOf("oversized","symlink","malformed","wrongType")) runPreviewScenario {context,root->
            scenario(context,root) {f->
                withContext(Dispatchers.IO) {f.seed(f.editor,61.0)}
                val metadata=File(f.slot(f.editor),"session.json")
                val external=File(root,"external-sidecar.json").apply {writeText("""{"displayName":"do not follow"}""")}
                withContext(Dispatchers.IO) {
                    when(case) {
                        "oversized"->metadata.writeText(JSONObject().put("displayName","x".repeat(70000)).toString())
                        "symlink"->{assertTrue(metadata.delete());Files.createSymbolicLink(metadata.toPath(),external.toPath())}
                        "malformed"->metadata.writeText("broken JSON")
                        else->metadata.writeText("""{"displayName":{"path":"not a title"}}""")
                    }
                }
                val body=File(f.slot(f.editor),"document.xnote").readBytes();val outside=external.readBytes()
                assertReady(f.load(artifact=RecoveryLegacyArtifact.NOTE)).use {preview->
                    assertTrue("Optional metadata must be bounded, unlinked and a plain string",preview.displayName==null)
                    assertEquals(61.0,(preview.note!!.pages.single().items.single() as Stroke).xAt(0),.001)
                }
                assertArrayEquals(body,File(f.slot(f.editor),"document.xnote").readBytes());assertArrayEquals(outside,external.readBytes())
                assertTrue(f.ownedTemporaryNames().isEmpty());assertEquals(0,f.reads);assertEquals(0,f.writes)
            }
        }
    }
}
