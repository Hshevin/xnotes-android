package com.xnotes.storage

import com.xnotes.format.*
import com.xnotes.core.pal.*
import okio.*
import kotlin.test.assertFailsWith
import kotlin.coroutines.cancellation.CancellationException
import com.xnotes.core.testkit.*
import kotlin.test.Test

class NativeReferenceReaderTest : BundleTestFiles() {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private val page="00000000-0000-0000-0000-000000000003"
    private fun scenario(block:(AssetFile)->Unit) {
        val root=testDirectory("native-reference-read-")
        try {block(root)} finally {root.deleteTree()}
    }
    private fun bundle(root:AssetFile,id:String?=a,anchored:Boolean=false,comment:Boolean=false,
                       pages:String="""[{"reference_id":"$page","items":[]}]""",format:String="xnote"):AssetFile =
        temporaryAsset("input-",".zip",root).also {file->
            bundleWriter(file.fileSink(),comment="provider archive".takeIf {comment}).use {z->
                id?.let {z.addIdentity(BundleIdentity(it))}
                if(anchored) z.addMarker(AnchorMetadata(requireNotNull(id)))
                z.putDeflated("manifest.json","""{"format":"$format","version":1,"pages":$pages}""".encodeToByteArray())
            }
        }
    private fun read(root:AssetFile,source:AssetFile,fast:BundleHeader?=null,check:(BundleHeader)->Unit={}) =
        NativeReferenceReader.readSource(root,{fast},{source.input()},check=check)

    @Test fun supportedLegacyHeaderNeedsNoSnapshotAndRunsGuard() = scenario {root->
        val input=bundle(root);val header=BundleHeader.read(input);var checks=0
        val info=NativeReferenceReader.readSource(root,{header},{error("must not open a second source")}) {checks++;assertEquals(a,it.identity!!.documentId)}
        assertEquals(a,info.header.identity!!.documentId);assertTrue(info.pageIds.isEmpty());assertEquals(1,checks)
        assertEquals(listOf(input.assetPath().name),root.fileNames().toList())
    }
    @Test fun unsupportedCommentedBundleReadsOneFreshStreamAndValidatesPages() = scenario {root->
        val input=bundle(root,anchored=true,comment=true);val bytes=input.fileBytes();var opened=0;var closed=false
        val info=NativeReferenceReader.readSource(root,{null},{opened++;object:ForwardingSource(input.input()) {
            override fun close() {closed=true;super.close()}
        }}) {}
        assertEquals(setOf(page),info.pageIds);assertEquals(a,info.header.identity!!.documentId)
        assertEquals(1,opened);assertTrue(closed);assertArrayEquals(bytes,input.fileBytes());assertEquals(1,root.fileNames().size)
    }
    @Test fun anchoredFastHeaderCannotSubstituteForFullPageValidation() = scenario {root->
        for(pages in listOf("[]","[{\"items\":[]}]","[{\"reference_id\":\"bad\"}]",
            "[{\"reference_id\":\"$page\"},{\"reference_id\":\"$page\"}]",
            "[{\"reference_id\":\"$page\"},{}]")) {
            val input=bundle(root,anchored=true,pages=pages);var checked=false
            assertFailsWith<IllegalArgumentException> {read(root,input,BundleHeader.read(input)) {checked=true}}
            assertFalse(checked)
        }
        assertTrue(root.fileNames().all {it.startsWith("input-")})
    }
    @Test fun fastHeaderErrorsNeverTurnIntoAnAbsentIdentityOrFallback() = scenario {root->
        val parse=IllegalArgumentException("bad metadata")
        assertSame(parse,assertFailsWith<IllegalArgumentException> {
            NativeReferenceReader.readSource(root,{throw parse},{error("must not fallback")}) {}
        })
        assertTrue(root.fileNames().isEmpty())
    }
    @Test fun changedIdentityOrLostMarkerBetweenFastReadAndSnapshotIsRejected() = scenario {root->
        val original=bundle(root,anchored=true);val header=BundleHeader.read(original)
        for(replaced in listOf(bundle(root,id=b,anchored=true),bundle(root,anchored=false))) {
            var checked=false
            assertFailsWith<IllegalStateException> {read(root,replaced,header) {checked=true}}
            assertFalse(checked)
        }
    }
    @Test fun observedLocationAndMovedUuidContractsRunBeforeReturningIdentity() = scenario {root->
        val input=bundle(root);val header=BundleHeader.read(input)
        val missing=assertFailsWith<ReferenceContractViolation> {read(root,input,header) {
            ReferenceContract.verifyObserved(null,ReferenceExpectation(a,true),it)
        }}
        assertEquals(StorageMsg.REFERENCE_PAGE_METADATA_MISSING,missing.reason)
        val replaced=assertFailsWith<ReferenceContractViolation> {read(root,input,header) {
            ReferenceContract.verifyObserved(ReferenceExpectation(b,false),ReferenceExpectation(a,false),it)
        }}
        assertEquals(StorageMsg.REFERENCE_IDENTITY_MISMATCH,replaced.reason)
    }
    @Test fun failedSnapshotAndGuardReleaseTheirStreamAndPrivateFiles() = scenario {root->
        var closed=false
        val io=IOException("provider read failed")
        assertSame(io,assertFailsWith<IOException> {
            NativeReferenceReader.readSource(root,{null},{object:Source by Buffer() {
                override fun read(sink:Buffer,byteCount:Long):Long=throw io
                override fun close() {closed=true}
            }}) {}
        });assertTrue(closed);assertTrue(root.fileNames().isEmpty())
        val input=bundle(root,anchored=true);val cancelled=CancellationException("cancel")
        assertSame(cancelled,assertFailsWith<CancellationException> {read(root,input) {throw cancelled}})
        assertEquals(1,root.fileNames().size)
    }
    @Test fun duplicatePayloadEntriesCannotHideBehindAValidFastMetadataHeader() = scenario {root->
        val input=root.child("duplicate.zip")
        bundleWriter(input.fileSink()).use {z->
            z.addIdentity(BundleIdentity(a));z.addMarker(AnchorMetadata(a))
            for(name in listOf("manifest.json","duplicate.bin")) {
                z.putDeflated(name,"""{"format":"xnote","version":1,"pages":[{"reference_id":"$page"}]}""".encodeToByteArray())
            }
        }
        // Independent malformed fixture: both local and directory names become duplicates.
        val bytes=input.fileBytes();val from="duplicate.bin".encodeToByteArray();val to="manifest.json".encodeToByteArray()
        assertEquals(from.size,to.size)
        for(at in 0..bytes.size-from.size) if(from.indices.all {bytes[at+it]==from[it]}) to.copyInto(bytes,at)
        input.setBytes(bytes)
        val hint=BundleHeader.read(input)
        assertFailsWith<IllegalArgumentException> {read(root,input,hint)}
        assertEquals(1,root.fileNames().size)
    }
    @Test fun cancellationDuringRegistryGuardCannotReturnEitherHeaderOrSnapshotResult() = scenario {root->
        for(anchored in listOf(false,true)) {
            val input=bundle(root,anchored=anchored);var cancelled=false
            val signal=CancellationException("cancelled during registry query")
            assertSame(signal,assertFailsWith<CancellationException> {
                NativeReferenceReader.readSource(root,{BundleHeader.read(input)},{input.input()},
                    checkActive={if(cancelled) throw signal}) {cancelled=true}
            })
        }
        assertTrue(root.fileNames().all {it.startsWith("input-")})
    }
    @Test fun cancellationDuringSnapshotStopsReadingClosesInputAndDeletesPartialFile() = scenario {root->
        val input=bundle(root,anchored=true);var cancelled=false;var closed=false
        val signal=CancellationException("interrupted reference read")
        assertSame(signal,assertFailsWith<CancellationException> {
            NativeReferenceReader.readSource(root,{null},{object:ForwardingSource(input.input()) {
                override fun read(sink:Buffer,byteCount:Long):Long {
                    val n=super.read(sink,byteCount);cancelled=true;return n
                }
                override fun close() {closed=true;super.close()}
            }},checkActive={if(cancelled) throw signal}) {error("must not return cancelled source")}
        })
        assertTrue(closed);assertEquals(1,root.fileNames().size)
    }
    @Test fun legacyCanvasRemainsReadableButCannotClaimPageAnchorMetadata() = scenario {root->
        val canvas=bundle(root,format="xcanvas",pages="[]")
        assertEquals(a,read(root,canvas).header.identity!!.documentId)
        val wrong=bundle(root,format="xcanvas",pages="[]",anchored=true)
        assertFailsWith<IllegalArgumentException> {read(root,wrong)}
    }
}
