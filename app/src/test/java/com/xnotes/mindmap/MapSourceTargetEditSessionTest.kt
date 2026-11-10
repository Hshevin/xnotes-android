package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.BundleHeader
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual native bundles prepare opaque choices; actual map actors own target/history/PNG transactions. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapSourceTargetEditSessionTest {
    private fun png():ByteArray {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(name:String,body:ByteArray) {
            val type=name.toByteArray(Charsets.US_ASCII);val crc=CRC32().apply {update(type);update(body)}
            out.writeInt(body.size);out.write(type);out.write(body);out.writeInt(crc.value.toInt())
        }
        val header=ByteArrayOutputStream();DataOutputStream(header).use {it.writeInt(2);it.writeInt(2);it.write(byteArrayOf(8,6,0,0,0))}
        chunk("IHDR",header.toByteArray());val compressed=ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use {z->repeat(2) {z.write(0);z.write(ByteArray(8) {-1})}}
        chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf());return bytes.toByteArray()
    }
    private inner class Fixture(val base:MapBacklinkLibraryTest.Fixture) {
        val oneDocument=Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0))).apply {enableReferenceAnchors()}
        val otherDocument=Document(mutableListOf(Page(500.0,500.0))).apply {enableReferenceAnchors()}
        val asset=MapPreviewAsset.fromPng(png())
        val first=MapSource(documentId=oneDocument.identity.documentId,fileName="Cached source name",scope=SourceScope.PAGE,
            pageId=oneDocument.pages[0].referenceIdentity.id,region=Rect(10.0,20.0,100.0,120.0),lastKnownPage=1,note="Keep source remark",
            status=SourceStatus.CURRENT,sourceVersion="a".repeat(64),observedVersion="native-sha256:${"a".repeat(64)}",acceptedPreviewVersion="native-sha256:${"a".repeat(64)}")
        val source=first.copy(previewKey=asset.key,lastPreview=MapLastPreview(asset.key,first.target,"native-sha256:${"a".repeat(64)}",1))
        val other=MapSource(documentId=otherDocument.identity.documentId,fileName="Other.xnote",note="Other remark")
        val initial=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="n",parent="r",kind=NodeKind.INK,
            text="Independent body",ink=listOf(MapInk(listOf(InkPoint(12.0,18.0,.4,9.0),InkPoint(32.0,48.0,.8,12.0)))),
            sources=listOf(source,other),primarySource=source.id)),previewAssets=mapOf(asset.key to asset))
        lateinit var file:File
        lateinit var session:MapSession
        lateinit var panel:SourceListEditor
        val access=MapSourceTargetAccess(base.context,storageOverride=base.storage)
        suspend fun start() {
            withContext(Dispatchers.IO) {
                val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
                for((name,model) in listOf("source.xnote" to oneDocument,"other.xnote" to otherDocument)) {
                    val file=File(base.notes,name);file.outputStream().use {codec.write(model.fixedWriteSnapshot(),it)}
                    base.storage.registry.registerObserved(BundleHeader.read(file),"xnote",name,base.uri(file).toString())
                }
                file=base.graph("target-edit.xmap",initial)
            }
            val lease=base.acquire(base.uri(file));session=lease.session
            withContext(Dispatchers.Main) {panel=SourceListEditor(session,session.sourceEditSnapshot("n")!!)}
        }
        fun current()=session.document!!.node("n").sources.single {it.id==source.id}
        suspend fun choice(document:Document=oneDocument,page:Int=1,scope:SourceRangeScope=SourceRangeScope.REGION,
            region:Rect?=Rect(30.0,40.0,150.0,160.0),block:suspend (PreparedSourceTargetChoice,SourceRangeRead)->Unit) {
            val read=withContext(Dispatchers.IO) {access.openCurrent(MapSourceTarget(document.identity.documentId,SourceScope.FILE))}
            try {
                val prepared=withContext(Dispatchers.IO) {val frame=read.frame(pageId=document.pages[page].referenceIdentity.id)
                    read.prepareChoice(frame,scope,region)}
                block(prepared,read)
            } finally {withContext(NonCancellable+Dispatchers.IO) {read.closeAndAwait()}}
        }
        fun apply(ticket:MapSourceTargetEditTicket,choice:PreparedSourceTargetChoice):MapSourceTargetEditResult =
            session.applySourceTargetEdit(ticket,choice).also {session.cancelPendingAutosave()}
    }
    private fun scenario(block:suspend (Fixture)->Unit)=MapBacklinkLibraryTest().scenario {base->
        val f=Fixture(base);f.start();try {block(f)} finally {withContext(Dispatchers.Main) {f.panel.close();f.session.cancelPendingAutosave()}}
    }
    @Test fun oneTargetPatchPreservesConcurrentBodyInkNotesOrderingPrimaryAndUnrelatedSources()=scenario {f->
        val ticket=withContext(Dispatchers.Main) {f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.RANGE)!!}
        val oldPreview=withContext(Dispatchers.Main) {f.session.captureSourcePreview("n",f.source.id)!!}
        val oldNavigation=withContext(Dispatchers.Main) {f.session.captureSourceNavigation("n",f.source.id)!!}
        val third=MapSource(documentId=UUID.randomUUID().toString(),note="Concurrent attachment")
        lateinit var latest:MindMapDocument
        withContext(Dispatchers.Main) {
            assertTrue(f.session.operate {doc->doc.update("n") {node->node.copy(text="Latest body",x=345.0,
                ink=node.ink+MapInk(listOf(InkPoint(9.0,10.0,.7,21.0))),
                sources=listOf(node.sources[1].copy(note="Latest other remark"),third,node.sources[0].copy(note="Latest target remark")),
                primarySource=f.other.id)}})
            f.session.cancelPendingAutosave();latest=f.session.document!!
        }
        f.choice {choice,_->withContext(Dispatchers.Main) {
            val beforeRevision=f.session.contentRevision
            assertEquals(MapSourceTargetEditResult.Applied(true),f.apply(ticket,choice))
            val node=f.session.document!!.node("n");val source=f.current()
            assertEquals(latest.node("n").copy(sources=node.sources),node)
            assertEquals(listOf(f.other.id,third.id,f.source.id),node.sources.map {it.id});assertEquals(f.other.id,node.primarySource)
            assertEquals("Latest target remark",source.note);assertEquals(choice.target,source.target);assertEquals(f.source.id,source.id)
            assertEquals(choice.displayName,source.fileName);assertEquals(choice.rawVersion,source.sourceVersion)
            assertNull(source.observedVersion);assertNull(source.acceptedPreviewVersion);assertEquals(SourceStatus.STALE,source.status)
            assertEquals(f.source.lastPreview,source.lastPreview);assertEquals(f.source.previewKey,source.previewKey)
            assertArrayEquals(f.asset.pngBytes(),f.session.document!!.previewAssets.getValue(f.asset.key).pngBytes())
            assertTrue(source.hasHistoricalPreview);assertEquals(beforeRevision+1,f.session.contentRevision)
            assertNull(f.session.currentSourcePreview(oldPreview));assertNull(f.session.currentSourceNavigation(oldNavigation))
            assertTrue(ticket.accepted);assertTrue(f.session.sourceTargetEditCurrent(ticket))
            f.session.undo();f.session.cancelPendingAutosave();assertEquals(latest,f.session.document)
            assertFalse(f.session.sourceTargetEditCurrent(ticket));assertNull(f.session.currentSourceNavigation(oldNavigation))
            f.session.redo();f.session.cancelPendingAutosave();assertEquals(choice.target,f.current().target)
            assertFalse(f.session.sourceTargetEditCurrent(ticket))
        }}
    }
    @Test fun noOpAndDuplicateAcceptanceAreConsumedWithoutHistoryAndCannotRetargetTwice()=scenario {f->
        f.choice(page=0,region=f.source.region) {same,_->withContext(Dispatchers.Main) {
            val ticket=f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.RANGE)!!
            val before=f.session.document;val revision=f.session.contentRevision
            assertEquals(MapSourceTargetEditResult.Applied(false),f.apply(ticket,same))
            assertTrue(ticket.accepted);assertSame(before,f.session.document);assertEquals(revision,f.session.contentRevision);assertFalse(f.session.canUndo)
            assertEquals(MapSourceTargetEditResult.Applied(false),f.apply(ticket,same));assertEquals(revision,f.session.contentRevision)
        }}
        f.choice {changed,read->
            val ticket=withContext(Dispatchers.Main) {f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.RANGE)!!}
            withContext(Dispatchers.Main) {assertEquals(MapSourceTargetEditResult.Applied(true),f.apply(ticket,changed))}
            withContext(Dispatchers.IO) {read.closeAndAwait()}
            withContext(Dispatchers.Main) {
                assertFalse(changed.current());val revision=f.session.contentRevision
                assertTrue(f.session.sourceTargetEditCurrent(ticket))
                assertEquals(MapSourceTargetEditResult.Applied(false),f.apply(ticket,changed));assertEquals(revision,f.session.contentRevision)
            }
            f.choice(page=0,scope=SourceRangeScope.WHOLE_PAGE,region=null) {second,_->withContext(Dispatchers.Main) {
                assertEquals(MapSourceTargetEditResult.Changed,f.apply(ticket,second));assertEquals(changed.target,f.current().target)
            }}
        }
    }
    @Test fun rangeRejectsAnotherDocumentWhileExplicitReplacementKeepsTheSameSourceAndOldPng()=scenario {f->
        f.choice(document=f.otherDocument,page=0,scope=SourceRangeScope.WHOLE_FILE,region=null) {replacement,_->withContext(Dispatchers.Main) {
            val range=f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.RANGE)!!
            assertEquals(MapSourceTargetEditResult.Invalid,f.apply(range,replacement));assertFalse(range.accepted);assertFalse(f.session.canUndo)
            val ticket=f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.REPLACE)!!
            assertEquals(MapSourceTargetEditResult.Applied(true),f.panel.applyTargetEdit(ticket,replacement));f.session.cancelPendingAutosave()
            assertEquals(f.source.id,f.current().id);assertEquals(f.source.note,f.current().note);assertEquals(replacement.target,f.current().target)
            assertEquals(f.source.lastPreview,f.current().lastPreview);assertEquals(f.source.id,f.session.document!!.node("n").primarySource)
            assertTrue(f.panel.note(f.source.id,"Panel remains current after successful replacement"));f.session.cancelPendingAutosave()
        }}
    }
    @Test fun staleRowsDeletionUndoReloadAndFileCardRolesCannotReuseAnOldTargetEditTicket()=scenario {f->
        withContext(Dispatchers.Main) {
            assertNull(f.panel.captureTargetEdit(f.source.id,f.source.target.copy(region=Rect(99.0,20.0,100.0,120.0)),SourceTargetEditMode.RANGE))
            val deleted=f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.RANGE)!!
            assertTrue(f.session.operate {it.update("n") {node->node.copy(sources=node.sources.filterNot {it.id==f.source.id},primarySource=f.other.id)}})
            f.session.cancelPendingAutosave();f.session.undo();f.session.cancelPendingAutosave()
            assertFalse(f.session.sourceTargetEditCurrent(deleted))
            val alias=MapSource(documentId=f.source.documentId)
            assertTrue(f.session.operate {it.copy(nodes=it.nodes+MapNode(id="alias",parent=null,free=true,kind=NodeKind.REFERENCE,sources=listOf(alias),primarySource=alias.id))})
            f.session.cancelPendingAutosave()
            val role=f.session.captureSourceTargetEdit("alias",alias.id,alias.target,SourceTargetEditMode.REPLACE)!!
            assertTrue(f.session.operate {it.update("alias") {node->node.copy(fileCardId=alias.documentId)}});f.session.cancelPendingAutosave()
            assertFalse(f.session.sourceTargetEditCurrent(role));assertNull(f.session.captureSourceTargetEdit("alias",alias.id,alias.target,SourceTargetEditMode.REPLACE))
        }
        val beforeReload=withContext(Dispatchers.Main) {f.session.captureSourceTargetEdit("n",f.source.id,f.source.target,SourceTargetEditMode.REPLACE)!!}
        withContext(Dispatchers.Main) {f.session.useFileVersion()}
        withTimeout(10000) {while(withContext(Dispatchers.Main) {f.session.replacingDocument || f.session.saving}) delay(1)}
        withContext(Dispatchers.Main) {assertFalse(f.session.sourceTargetEditCurrent(beforeReload))}
    }
    @Test fun busyOrClosedOwnersDoNotConsumeOrApplyPendingChoices()=scenario {f->
        f.choice {choice,_->withContext(Dispatchers.Main) {
            val ticket=f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.RANGE)!!
            val before=f.session.document;f.session.inkContact=true
            assertTrue(f.session.sourceTargetEditCurrent(ticket));assertEquals(MapSourceTargetEditResult.Busy,f.apply(ticket,choice))
            assertSame(before,f.session.document);assertFalse(ticket.accepted);assertTrue(f.session.inkContact)
            f.session.inkContact=false;f.panel.close()
            assertEquals(MapSourceTargetEditResult.Changed,f.panel.applyTargetEdit(ticket,choice));assertSame(before,f.session.document)
        }}
    }
    @Test fun acceptedReplacementSurvivesReadClosureAndSaveConflictWithoutApplyingAgain()=scenario {f->
        val originalBytes=f.file.readBytes()
        f.choice(document=f.otherDocument,page=0,scope=SourceRangeScope.WHOLE_FILE,region=null) {choice,read->
            lateinit var ticket:MapSourceTargetEditTicket
            lateinit var accepted:MindMapDocument
            withContext(Dispatchers.Main) {
                ticket=f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.REPLACE)!!
                assertEquals(MapSourceTargetEditResult.Applied(true),f.apply(ticket,choice));accepted=f.session.document!!
            }
            withContext(Dispatchers.IO) {read.closeAndAwait();f.file.writeBytes(MapCodec.encode(f.initial.update("r") {it.copy(text="External") }))}
            withContext(Dispatchers.Main) {
                assertTrue(f.session.saveAttempt().await() is MapSaveOutcome.Conflict)
                val revision=f.session.contentRevision
                assertTrue(f.session.sourceTargetEditCurrent(ticket));assertEquals(MapSourceTargetEditResult.Applied(false),f.apply(ticket,choice))
                assertEquals(revision,f.session.contentRevision);assertEquals(accepted,f.session.document)
            }
            withContext(Dispatchers.IO) {f.file.writeBytes(originalBytes)}
            withContext(Dispatchers.Main) {assertTrue(f.session.saveAttempt().await() is MapSaveOutcome.Written)}
            val cold=MapCodec.decode(f.file.readBytes());assertEquals(accepted,cold)
            assertEquals(f.source.id,cold.node("n").sources.single {it.id==f.source.id}.id)
            assertEquals(f.source.lastPreview,cold.node("n").sources.single {it.id==f.source.id}.lastPreview)
        }
    }
    @Test fun aClosedPreparationCannotApplyOrConsumeItsStillCurrentSourceTicket()=scenario {f->
        f.choice {choice,read->
            val ticket=withContext(Dispatchers.Main) {f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.RANGE)!!}
            withContext(Dispatchers.IO) {read.closeAndAwait()}
            withContext(Dispatchers.Main) {
                val before=f.session.document;val revision=f.session.contentRevision
                assertFalse(choice.current());assertTrue(f.session.sourceTargetEditCurrent(ticket))
                assertEquals(MapSourceTargetEditResult.Changed,f.apply(ticket,choice))
                assertFalse(ticket.accepted);assertSame(before,f.session.document);assertEquals(revision,f.session.contentRevision);assertFalse(f.session.canUndo)
            }
        }
    }
    @Test fun anExistingAddSourceSlotPreventsCapturingACompetingTargetEditor()=scenario {f->withContext(Dispatchers.Main) {
        val pending=f.session.beginExistingSource("n")!!
        assertTrue(f.session.sourceTargetEditPickerBusy)
        assertNull(f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.REPLACE))
        f.session.cancelSourceRequest(pending)
        assertFalse(f.session.sourceTargetEditPickerBusy)
        assertNotNull(f.panel.captureTargetEdit(f.source.id,f.source.target,SourceTargetEditMode.REPLACE))
        assertFalse(f.session.canUndo)
    }}

}
