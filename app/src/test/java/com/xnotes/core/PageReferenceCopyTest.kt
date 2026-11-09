package com.xnotes.core

import com.xnotes.core.history.DeletePage
import com.xnotes.core.history.History
import com.xnotes.core.model.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class PageReferenceCopyTest {
    private val target="00000000-0000-4000-8000-000000000002"
    private val third="00000000-0000-4000-8000-000000000003"
    private fun note(count:Int=2)=Document.blank(count).also {it.enableReferenceAnchors()}
    private fun ids(doc:Document)=doc.pages.map {it.referenceIdentity.id}
    private fun rejects(block:()->Unit) {try {block();fail("Must reject invalid copy state")} catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {}}

    @Test fun projectionChangesOnlyDocumentAndPageIdentityAndKeepsOldContainers() {
        val doc=note();val p=doc.pages.first();val oldContainer=p.referenceIdentity
        val samples=listOf(Sample(-3.0,4.0,.2,13.0),Sample(7.0,8.0,.9,21.0))
        val ink=Stroke(Tool.PEN,ToolConfig(),samples).also {p.items.add(it)}
        val stored=ink.samples.toList() // Native packed storage rounds pressure to float before this operation.
        p.pdfPage=7;p.pdfTurn=1;p.cover=true
        doc.identity=doc.identity.copy(pdfResource="a".repeat(64),externalPdf=true)
        doc.pdfFile=File("shared-source.pdf");doc.dirty=true
        val before=doc.snapshot();val oldIds=ids(doc);val copy=PageReferenceCopy.prepare(doc,target)
        val projected=copy.project(before)
        assertEquals(target,projected.identity.documentId);assertEquals(doc.identity.pdfResource,projected.identity.pdfResource)
        assertEquals(doc.identity.externalPdf,projected.identity.externalPdf);assertSame(doc.pdfFile,projected.pdfFile)
        assertTrue(projected.hasReferenceAnchors);assertTrue(projected.dirty)
        assertTrue(oldIds.toSet().intersect(ids(projected).toSet()).isEmpty())
        assertEquals(oldIds,ids(doc));assertEquals(oldIds,ids(before));assertSame(oldContainer,p.referenceIdentity)
        val out=projected.pages.first();assertNotSame(p,out);assertSame(ink,out.items.single());assertEquals(stored,ink.samples)
        assertEquals(7,out.pdfPage);assertEquals(1,out.pdfTurn);assertTrue(out.cover)
        assertEquals(oldIds.first(),oldContainer.id)
    }
    @Test fun repeatedProjectionReorderAndParallelWorkersUseTheSameMapping() {
        val doc=note(3);val first=doc.snapshot();val copy=PageReferenceCopy.prepare(doc,target)
        val expected=ids(copy.project(first));doc.pages.reverse();val reverse=doc.snapshot()
        assertEquals(expected.reversed(),ids(copy.project(reverse)))
        val results=ConcurrentLinkedQueue<List<String?>>()
        val workers=(0 until 8).map {Thread {results+=ids(copy.project(first))}.apply {start()}}
        workers.forEach {it.join()};assertEquals(8,results.size);assertTrue(results.all {it==expected})
        assertEquals(expected,ids(copy.project(copy.project(first).snapshot())))
    }
    @Test fun chainedCopiesTranslateOldQueuedSnapshotsAndDoNotRollBackToAnAncestor() {
        val doc=note();val a=doc.snapshot();val ab=PageReferenceCopy.prepare(doc,target)
        val b=ab.project(a);ab.adopt(doc)
        val bc=PageReferenceCopy.prepare(doc,third);val c=bc.project(doc.snapshot())
        assertEquals(ids(c),ids(bc.project(a)));assertEquals(ids(c),ids(bc.project(b)))
        bc.adopt(doc);assertEquals(ids(c),ids(doc));rejects {ab.adopt(doc)}
        rejects {PageReferenceCopy.prepare(doc,target)}
        rejects {PageReferenceCopy.prepare(doc,a.identity.documentId)}
    }
    @Test fun adoptionPreservesLivePagesHistoryAndPagesDeletedBeforeAndDuringPublication() {
        val doc=note(3);val early=doc.pages[0];val during=doc.pages[1];val retained=doc.pages[2]
        val history=History();val delEarly=DeletePage(doc,early,0);delEarly.redo();history.push(delEarly)
        val copy=PageReferenceCopy.prepare(doc,target);val frozen=doc.snapshot();val expected=ids(copy.project(frozen))
        val delDuring=DeletePage(doc,during,0);delDuring.redo();history.push(delDuring)
        val added=doc.addPage(100.0,100.0);val retainedUid=retained.uid
        copy.adopt(doc)
        assertSame(retained,doc.pages[0]);assertSame(added,doc.pages[1]);assertEquals(retainedUid,retained.uid)
        assertEquals(expected[1],retained.referenceIdentity.id);assertTrue(history.canUndo)
        history.undo();history.undo();doc.prepareReferenceAnchors()
        assertSame(early,doc.pages[0]);assertSame(during,doc.pages[1]);assertEquals(expected[0],during.referenceIdentity.id)
        val stable=ids(doc);assertEquals(4,stable.toSet().size)
        history.redo();history.redo();history.undo();history.undo();assertEquals(stable,ids(doc.snapshot()))
        assertFalse(history.canUndo);assertTrue(history.canRedo)
    }
    @Test fun pageCopiesAfterAdoptionGetIndependentIdsAndNewPagesDoNotRekeyAgain() {
        val doc=note();val copy=PageReferenceCopy.prepare(doc,target);copy.adopt(doc)
        val p=doc.pages.first();val duplicate=p.deepCopy(FakeTextMeasurer());doc.pages.add(duplicate)
        val added=doc.addPage(10.0,20.0);doc.prepareReferenceAnchors();val now=ids(doc)
        assertNotEquals(p.referenceIdentity.id,duplicate.referenceIdentity.id);assertNotNull(added.referenceIdentity.id)
        assertEquals(now,ids(copy.project(doc.snapshot())))
        copy.adopt(doc);assertEquals(now,ids(doc))
    }
    @Test fun wrongOwnerSiblingContextAndUnfrozenInputCannotPartiallyAdopt() {
        val doc=note();val original=ids(doc);val a=PageReferenceCopy.prepare(doc,target)
        val sibling=PageReferenceCopy.prepare(doc,third)
        val other=note().also {it.identity=doc.identity}
        rejects {a.adopt(other)};assertEquals(original,ids(doc));rejects {a.project(other.snapshot())}
        rejects {a.project(doc)}
        a.adopt(doc);val adopted=ids(doc);rejects {sibling.adopt(doc)};assertEquals(adopted,ids(doc))
    }
    @Test fun duplicatePagesOrIdsDuringAdoptionLeaveEveryContainerAndDocumentUnchanged() {
        val doc=note();val copy=PageReferenceCopy.prepare(doc,target);val original=ids(doc);val identity=doc.identity
        val first=doc.pages[0].referenceIdentity;val second=doc.pages[1].referenceIdentity
        doc.pages.add(doc.pages.first());rejects {copy.adopt(doc)}
        assertEquals(identity,doc.identity);assertSame(first,doc.pages[0].referenceIdentity);assertSame(second,doc.pages[1].referenceIdentity)
        doc.pages.removeAt(2)
        doc.pages.add(Page(1.0,1.0).also {it.referenceIdentity.restore(original[0]!!)})
        rejects {copy.adopt(doc)};assertEquals(identity,doc.identity);assertEquals(original,ids(doc).take(2))
    }
    @Test fun restoredAncestorCollisionIsRejectedBeforeAnyOtherPageIsPrepared() {
        val doc=note();val queued=doc.snapshot();val old=doc.pages.removeAt(0)
        val copy=PageReferenceCopy.prepare(doc,target);val collision=copy.project(queued).pages.first().referenceIdentity.id!!
        copy.adopt(doc)
        val fresh=Page(1.0,1.0);doc.pages.add(0,fresh)
        val impostor=Page(1.0,1.0).also {
            it.referenceBinding=PageReferenceBinding(ReferenceIdentity().also {id->id.restore(collision)},copy.scope)
        }
        doc.pages.add(impostor);doc.pages.add(old)
        val before=doc.pages.map {it.referenceIdentity};rejects {doc.prepareReferenceAnchors()}
        assertEquals(before,doc.pages.map {it.referenceIdentity});assertNull(fresh.referenceIdentity.id)
    }
    @Test fun changedDocumentUuidAfterExplicitAdoptionCannotBeWrittenOrPrepared() {
        val doc=note();val copy=PageReferenceCopy.prepare(doc,target);copy.adopt(doc)
        doc.identity=doc.identity.copy(documentId=third);val old=ids(doc)
        rejects {doc.prepareReferenceAnchors()};rejects {ReferenceAnchors.validateForWrite(doc)}
        assertEquals(old,ids(doc))
    }
    @Test fun legacyCopyDoesNotActivatePageReferences() {
        val doc=Document.blank(2);val copy=PageReferenceCopy.prepare(doc,target)
        val out=copy.project(doc.snapshot());assertFalse(out.hasReferenceAnchors);assertTrue(ids(out).all {it==null})
        copy.adopt(doc);assertFalse(doc.hasReferenceAnchors);assertEquals(target,doc.identity.documentId)
        assertTrue(ids(doc).all {it==null});doc.snapshot();assertTrue(ids(doc).all {it==null})
    }
    @Test fun projectionUsesFrozenLayerMembershipAndDocumentMetadata() {
        val doc=note();val p=doc.pages.first();val ink=Stroke(Tool.PEN,ToolConfig(),listOf(Sample(2.0,3.0,.5,8.0)))
        p.items.add(ink);doc.bookmarks.add(Bookmark(0,"before"));doc.bodyStartPage=1
        val frozen=doc.snapshot();val membership=frozen.pages.first().layerIdForWrite(ink)
        val copy=PageReferenceCopy.prepare(doc,target)
        ink.layerId=p.layers.create("Later").id;doc.bookmarks.first().label="after";doc.bodyStartPage=0
        val out=copy.project(frozen)
        assertEquals(membership,out.pages.first().layerIdForWrite(ink))
        assertEquals(frozen.pages.first().layers.entries,out.pages.first().layers.entries)
        assertEquals("before",out.bookmarks.single().label);assertEquals(1,out.bodyStartPage)
        assertEquals(ink.layerId,p.items.single().layerId)
    }
    @Test fun invalidTargetDoesNotAssignANewPageBeforeRejecting() {
        val doc=note();val fresh=Page(1.0,1.0).also {doc.pages.add(it)};val before=ids(doc)
        rejects {PageReferenceCopy.prepare(doc,"bad-uuid")};assertEquals(before,ids(doc))
        rejects {PageReferenceCopy.prepare(doc,doc.identity.documentId)};assertNull(fresh.referenceIdentity.id)
    }
    @Test fun sourceTargetIntersectionAndMalformedFrozenInputAreRejectedWithoutMutation() {
        val doc=note();val first=doc.pages.first();val copy=PageReferenceCopy.prepare(doc,target)
        val frozen=doc.snapshot();val derived=copy.project(frozen).pages.first().referenceIdentity.id!!
        // Page 2's source ID is deliberately the computed target ID of page 1.
        val collision=Page(1.0,1.0).also {
            it.referenceBinding=PageReferenceBinding(ReferenceIdentity().also {id->id.restore(derived)},first.referenceBinding.scope)
        }
        doc.pages[1]=collision;val queued=doc.snapshot();val original=ids(doc)
        rejects {copy.project(queued)};assertEquals(original,ids(doc));assertEquals(original,ids(queued))
        rejects {copy.adopt(doc)};assertNotEquals(target,doc.identity.documentId)
        val frozenIds=ids(frozen);val duplicate=frozen.copyForTestDuplicatePage()
        rejects {copy.project(duplicate)};assertEquals(frozenIds,ids(frozen))
    }
    private fun Document.copyForTestDuplicatePage()=snapshot().also {it.pages.add(it.pages.first())}

    @Test fun anIdentityInsertedWithoutProvenanceDuringPublicationIsNotGuessed() {
        val doc=note();val copy=PageReferenceCopy.prepare(doc,target);val before=ids(doc)
        val opaque=Page(1.0,1.0).also {it.referenceIdentity.restore(UUID.randomUUID().toString());doc.pages.add(it)}
        rejects {copy.adopt(doc)};assertNotEquals(target,doc.identity.documentId)
        assertEquals(before,ids(doc).dropLast(1));assertNull(opaque.referenceBinding.scope)
    }
    @Test fun mixedOrSiblingPageScopesCannotBeProjectedAsOneFrozenDocument() {
        val doc=note();val copy=PageReferenceCopy.prepare(doc,target);val frozen=doc.snapshot()
        val projected=copy.project(frozen)
        frozen.pages[0].referenceBinding=projected.pages[0].referenceBinding
        val idsBefore=ids(frozen);rejects {copy.project(frozen)};assertEquals(idsBefore,ids(frozen))
        val sibling=PageReferenceCopy.prepare(doc,third);val wrong=sibling.project(doc.snapshot())
        rejects {copy.project(wrong)}
    }
    @Test fun sharedUnassignedContainerCannotCausePartialAdoption() {
        val doc=note();val copy=PageReferenceCopy.prepare(doc,target)
        val a=Page(1.0,1.0);val b=Page(1.0,1.0).also {it.referenceBinding=a.referenceBinding}
        doc.pages.addAll(listOf(a,b));val bindings=doc.pages.map {it.referenceBinding};val identity=doc.identity;val scope=doc.pageReferenceScope
        rejects {copy.adopt(doc)}
        assertEquals(bindings,doc.pages.map {it.referenceBinding});assertNull(a.referenceIdentity.id)
        assertEquals(identity,doc.identity);assertSame(scope,doc.pageReferenceScope)
    }
    @Test fun sharedUnassignedContainerCannotPartiallyEnableReferences() {
        val doc=Document.blank(2);doc.pages[1].referenceBinding=doc.pages[0].referenceBinding
        val bindings=doc.pages.map {it.referenceBinding};rejects {doc.enableReferenceAnchors()}
        assertEquals(bindings,doc.pages.map {it.referenceBinding});assertTrue(ids(doc).all {it==null})
        assertFalse(doc.hasReferenceAnchors);assertNull(doc.pageReferenceScope)
    }
    @Test fun anUnpreparedCopiedPageCannotBeAssignedByTheWriter() {
        val doc=note();PageReferenceCopy.prepare(doc,target).adopt(doc)
        val newPage=Page(1.0,1.0).also {doc.pages.add(it)}
        rejects {ReferenceAnchors.validateForWrite(doc)};assertNull(newPage.referenceIdentity.id)
        doc.prepareReferenceAnchors();ReferenceAnchors.validateForWrite(doc)
    }
    @Test fun codecRoundTripKeepsBothFilesIndependentAndReadPagesCarryUndoProvenance() {
        val codec=com.xnotes.format.DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
        fun bytes(doc:Document)=java.io.ByteArrayOutputStream().also {codec.write(doc,it)}.toByteArray()
        val original=note(3);val oldIds=ids(original);val originalBytes=bytes(original.snapshot())
        val loaded=codec.read(originalBytes.inputStream());val removed=loaded.pages.removeAt(0)
        val copy=PageReferenceCopy.prepare(loaded,target);val candidate=copy.project(loaded.snapshot())
        val copyBytes=bytes(candidate);copy.adopt(loaded);loaded.pages.add(0,removed)
        val restored=loaded.snapshot();assertNotEquals(oldIds[0],restored.pages[0].referenceIdentity.id)
        assertEquals(oldIds,ids(codec.read(originalBytes.inputStream())))
        val reopened=codec.read(copyBytes.inputStream());assertEquals(target,reopened.identity.documentId)
        assertEquals(ids(candidate),ids(reopened));assertTrue(ids(reopened).toSet().intersect(oldIds.toSet()).isEmpty())
        val cd=PageReferenceCopy.prepare(loaded,third)
        assertEquals(ids(cd.project(candidate)),ids(cd.project(restored)).drop(1))
    }
    @Test fun uuidV5AgreesWithRfc9562AppendixA4() {
        assertEquals("2ed6657d-e927-568b-95e1-2665a8aea6a2",PageReferenceScope.uuidV5(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),"www.example.com"))
    }
}
