package com.xnotes.storage

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class RecoveryCopyTest {
    private val owner="00000000-0000-0000-0000-000000000001"
    private val p1="00000000-0000-0000-0000-000000000002"
    private val p2="00000000-0000-0000-0000-000000000003"
    private class Fixture(val native:CreationPendingFixture) {
        val root get()=native.root
        val storage get()=native.storage
        val temp=File(root,"copy-temp").apply {mkdir()}
        val assets=File(root,"preview-assets").apply {mkdir()}
        val pdfs=File(root,"preview-pdfs").apply {mkdir()}
        val noteCodec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
        val canvasCodec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
    }
    private data class Body(val source:File,val bytes:ByteArray,val held:File,val preview:PreparedRecoveryPreview,val pattern:String)
    private fun scenario(block:(Fixture)->Unit) {
        val f=Fixture(CreationPendingFixture(Files.createTempDirectory("recovery-copy-").toFile()))
        try {block(f)} finally {f.native.close()}
    }
    private fun body(f:Fixture,kind:DocumentKind=DocumentKind.NOTE,pdf:ByteArray?=null):Body {
        val future="""{"kind":"future-ink","raw":{"pressure":[0.1234567890123456789,-0],"time":[1.2500e+02,17]},"oldPage":"$p1"}"""
        val pattern=if(kind==DocumentKind.NOTE)
            """ {"format":"xnote","version":1,"has_pdf":${pdf!=null},"pages":[{"reference_id" : "@FIRST@","items":[$future]},{"reference_id":"@SECOND@","items":[]}],"future":{"reference_id":"$p2"}} """
        else """ {"format":"xcanvas","version":1,"items":[$future]} """
        val manifest=pattern.replace("@FIRST@",p2).replace("@SECOND@",p1)
        val digest=pdf?.let {MessageDigest.getInstance("SHA-256").digest(it).joinToString("") {b->"%02x".format(b)}}
        val source=File.createTempFile("historical-",".zip",f.root)
        ZipOutputStream(source.outputStream()).use {zip->
            fun entry(name:String,bytes:ByteArray) {zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}
            BundleIdentity(owner,digest).write(zip)
            if(kind==DocumentKind.NOTE) AnchorMetadata(owner).write(zip)
            entry("manifest.json",manifest.toByteArray(Charsets.UTF_8));entry("future/original.bin",byteArrayOf(0,1,2,-1))
            pdf?.let {entry(BundlePdf.ENTRY,it)}
        }
        val snapshot=BundleSnapshot.take(source,f.root);val held=snapshot.file
        val prepared=PreparedRecoveryPreview.readLegacy(snapshot,kind,f.storage.pdfResources,f.noteCodec,f.canvasCodec,f.pdfs,f.assets,"Historical name")
        return Body(source,source.readBytes(),held,prepared,pattern)
    }
    private fun entry(file:File,name:String)=ZipFile(file).use {zip->zip.getInputStream(zip.getEntry(name)).use {it.readBytes()}}
    private fun localized(result:RecoveryCopyResult.Failed) {
        assertTrue("Failure text must come from StorageText",StorageMsg.entries.any {StorageText.t(it)==result.message})
    }

    @Test fun heldRawNotePdfAndCanvasPublishVerifiedIndependentCopiesWithoutChangingThePreview() {
        for(kind in listOf(DocumentKind.NOTE,DocumentKind.CANVAS)) scenario {f->
            val pdf=if(kind==DocumentKind.NOTE) "%PDF-1.7 original\u0000bytes".toByteArray() else null
            val b=body(f,kind,pdf)
            b.preview.use {prepared->
                val modelIdentity=prepared.note?.identity ?: prepared.canvas!!.identity
                val reads=f.native.reads;val writes=f.native.writes
                val request=RecoveryCopyRequest.create(f.storage,prepared,f.temp)
                assertEquals(kind,request.kind);assertEquals("Historical name",request.displayName)
                assertEquals(reads,f.native.reads);assertEquals(writes,f.native.writes);assertTrue(f.temp.listFiles()!!.isEmpty())
                val saved=request.publish(f.native.uri,"Independent copy") as RecoveryCopyResult.Saved
                assertEquals(f.native.uri,saved.uri);assertEquals("Independent copy",saved.name);assertEquals(1,f.native.writes)
                val actual=BundleReferenceInfo.inspectStrict(f.native.current)
                assertNotEquals(owner,actual.header.identity!!.documentId)
                f.storage.openSource(f.native.alias,kind).use {source->assertEquals(actual.header.identity.documentId,source.version.documentId)}
                if(kind==DocumentKind.NOTE) {
                    val raw=entry(f.native.current,"manifest.json");val pages=JSONObject(String(raw,Charsets.UTF_8)).getJSONArray("pages")
                    val ids=(0 until pages.length()).map {pages.getJSONObject(it).getString("reference_id")}
                    assertEquals(2,ids.toSet().size);assertTrue(ids.none {it==p1 || it==p2})
                    assertArrayEquals(b.pattern.replace("@FIRST@",ids[0]).replace("@SECOND@",ids[1]).toByteArray(Charsets.UTF_8),raw)
                    assertEquals(actual.header.identity.documentId,actual.header.anchors!!.documentId)
                    assertArrayEquals(pdf,entry(f.native.current,BundlePdf.ENTRY));assertNull(prepared.note!!.path)
                    assertFalse(prepared.note!!.dirty);assertEquals(listOf(p2,p1),prepared.note!!.pages.map {it.referenceIdentity.id})
                } else {
                    assertArrayEquals(entry(b.source,"manifest.json"),entry(f.native.current,"manifest.json"));assertNull(actual.header.anchors)
                    assertNull(prepared.canvas!!.path);assertFalse(prepared.canvas!!.dirty)
                }
                assertEquals(modelIdentity,prepared.note?.identity ?: prepared.canvas!!.identity)
                assertArrayEquals(entry(b.source,"future/original.bin"),entry(f.native.current,"future/original.bin"))
                assertArrayEquals(b.bytes,b.source.readBytes());prepared.ensureOpen();assertTrue(b.held.isFile)
                assertTrue(f.temp.listFiles()!!.isEmpty());assertFalse(f.native.marker.exists())
            }
            assertFalse(b.held.exists())
        }
    }

    @Test fun nonemptyClosedRepeatedChangedAndCancelledRequestsCannotWriteTheWrongTarget() {
        for(case in listOf("nonempty","closed","double","changed","permission","cancel")) scenario {f->
            val b=body(f);val request=RecoveryCopyRequest.create(f.storage,b.preview,f.temp)
            try {
                when(case) {
                    "nonempty"->{f.native.current.writeText("unrelated target");val bytes=f.native.current.readBytes()
                        localized(request.publish(f.native.uri,"copy") as RecoveryCopyResult.Failed);assertArrayEquals(bytes,f.native.current.readBytes());assertEquals(0,f.native.writes)}
                    "closed"->{b.preview.close();assertThrows(IllegalStateException::class.java) {RecoveryCopyRequest.create(f.storage,b.preview,f.temp)}
                        localized(request.publish(f.native.uri,"copy") as RecoveryCopyResult.Failed);assertEquals(0,f.native.writes)}
                    "double"->{assertTrue(request.publish(f.native.uri,"copy") is RecoveryCopyResult.Saved);val bytes=f.native.current.readBytes()
                        localized(request.publish(f.native.uri,"second") as RecoveryCopyResult.Failed);assertArrayEquals(bytes,f.native.current.readBytes());assertEquals(1,f.native.writes)}
                    "changed"->{b.held.appendText("private snapshot changed")
                        localized(request.publish(f.native.uri,"copy") as RecoveryCopyResult.Failed);assertEquals(0L,f.native.current.length());assertEquals(0,f.native.writes)}
                    "permission"->{f.native.beforeRead={throw SecurityException("private English path must not be displayed")}
                        localized(request.publish(f.native.uri,"copy") as RecoveryCopyResult.Failed);assertEquals(0,f.native.writes)}
                    else->{val cancelled=CancellationException("private English cancellation detail")
                        assertSame(cancelled,assertThrows(CancellationException::class.java) {request.publish(f.native.uri,"copy") {throw cancelled}})
                        localized(request.publish(f.native.uri,"second") as RecoveryCopyResult.Failed);assertEquals(0,f.native.writes)}
                }
                assertArrayEquals(b.bytes,b.source.readBytes());assertTrue(f.temp.listFiles()!!.isEmpty());assertFalse(f.native.marker.exists())
            } finally {b.preview.close()}
        }
    }

    @Test fun aWriteThenFailureReturnsItsTypedAttemptAndCompletionNeedsNeitherPreviewNorReencoding()=scenario {f->
        val b=body(f,pdf="%PDF-retained original".toByteArray())
        val request=RecoveryCopyRequest.create(f.storage,b.preview,f.temp);f.native.failAfterWrite=true
        val result=request.publish(f.native.uri,"Retained copy") as RecoveryCopyResult.Retained
        assertTrue(StorageMsg.entries.any {StorageText.t(it)==result.message});assertEquals(1,f.native.writes)
        val published=f.native.current.readBytes();val hash=BundleMigration.hash(f.native.current)
        val ids=BundleReferenceInfo.inspectStrict(f.native.current).orderedPageIds
        assertTrue(f.native.marker.isFile);assertTrue(f.temp.listFiles()!!.isEmpty());b.preview.ensureOpen()
        b.preview.close();assertFalse(b.held.exists());f.native.failAfterWrite=false
        val retainedFiles=f.native.retainedBytes()
        f.native.beforeRead={throw SecurityException("temporary provider permission loss")}
        localized(RecoveryCopyRequest.complete(f.storage,result.pending) as RecoveryCopyResult.Failed)
        assertTrue(f.native.marker.isFile);assertEquals(1,f.native.writes)
        assertArrayEquals(published,f.native.current.readBytes())
        val afterFailure=f.native.retainedBytes();assertEquals(retainedFiles.keys,afterFailure.keys)
        for((name,bytes) in retainedFiles) assertArrayEquals(bytes,afterFailure.getValue(name))
        f.native.beforeRead=null
        assertTrue(RecoveryCopyRequest.complete(f.storage,result.pending) is RecoveryCopyResult.Saved)
        assertEquals(1,f.native.writes);assertArrayEquals(published,f.native.current.readBytes());assertEquals(hash,BundleMigration.hash(f.native.current))
        assertEquals(ids,BundleReferenceInfo.inspectStrict(f.native.current).orderedPageIds);assertFalse(f.native.marker.exists())
        assertArrayEquals(b.bytes,b.source.readBytes())
    }

    @Test fun onlyTheExplicitSelectedTargetRouteCanCompleteAfterTheRuntimeIssuerAndSourceAreGone()=scenario {f->
        val b=body(f);f.native.failAfterWrite=true
        val retained=RecoveryCopyRequest.create(f.storage,b.preview,f.temp).publish(f.native.uri,"Selected copy") as RecoveryCopyResult.Retained
        val bytes=f.native.current.readBytes();b.preview.close();assertFalse(b.held.exists());f.native.failAfterWrite=false
        val restarted=NotebookStorage(f.native.context,targetOverride={f.native.backend()})
        try {
            localized(RecoveryCopyRequest.complete(restarted,retained.pending) as RecoveryCopyResult.Failed)
            assertTrue(f.native.marker.isFile);assertEquals(1,f.native.writes)
            val saved=RecoveryCopyRequest.completeSelected(restarted,f.native.alias,"Selected copy") as RecoveryCopyResult.Saved
            assertEquals(f.native.alias,saved.uri);assertEquals("Selected copy",saved.name)
            assertArrayEquals(bytes,f.native.current.readBytes());assertEquals(1,f.native.writes);assertFalse(f.native.marker.exists())
            assertArrayEquals(b.bytes,b.source.readBytes())
        } finally {restarted.registry.close()}
    }

    @Test fun cancellationAfterStagingIsNotReportedAsSavedOrClaimedAndKeepsTheFixedCandidate()=scenario {f->
        val b=body(f);val request=RecoveryCopyRequest.create(f.storage,b.preview,f.temp)
        val cancelled=CancellationException("stop before first target publication")
        f.native.beforeRead={if(f.native.marker.exists()) throw cancelled}
        assertSame(cancelled,assertThrows(CancellationException::class.java) {request.publish(f.native.uri,"Interrupted copy")})
        assertTrue(f.native.marker.isFile);assertEquals(0,f.native.writes);assertEquals(0L,f.native.current.length())
        val kept=f.native.retainedBytes();assertTrue(f.temp.listFiles()!!.isEmpty());b.preview.ensureOpen();assertTrue(b.held.isFile)
        assertArrayEquals(b.bytes,b.source.readBytes());f.native.beforeRead=null;b.preview.close()
        assertTrue(RecoveryCopyRequest.completeSelected(f.storage,f.native.alias,"Interrupted copy") is RecoveryCopyResult.Saved)
        assertEquals(1,f.native.writes);assertArrayEquals(kept.getValue("candidate.zip"),f.native.current.readBytes());assertFalse(f.native.marker.exists())
    }
}
