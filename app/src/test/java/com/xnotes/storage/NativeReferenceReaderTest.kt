package com.xnotes.storage

import com.xnotes.format.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class NativeReferenceReaderTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private val page="00000000-0000-0000-0000-000000000003"
    private fun scenario(block:(File)->Unit) {
        val root=Files.createTempDirectory("native-reference-read-").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    private fun bundle(root:File,id:String?=a,anchored:Boolean=false,comment:Boolean=false,
                       pages:String="""[{"reference_id":"$page","items":[]}]""",format:String="xnote"):File =
        File.createTempFile("input-",".zip",root).also {file->
            ZipOutputStream(file.outputStream()).use {z->
                if(comment) z.setComment("provider archive")
                id?.let {BundleIdentity(it).write(z)}
                if(anchored) AnchorMetadata(requireNotNull(id)).write(z)
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("""{"format":"$format","version":1,"pages":$pages}""".toByteArray());z.closeEntry()
            }
        }
    private fun read(root:File,source:File,fast:BundleHeader?=null,check:(BundleHeader)->Unit={}) =
        NativeReferenceReader.read(root,{fast},{source.inputStream()},check=check)

    @Test fun supportedLegacyHeaderNeedsNoSnapshotAndRunsGuard() = scenario {root->
        val input=bundle(root);val header=BundleHeader.read(input);var checks=0
        val info=NativeReferenceReader.read(root,{header},{error("must not open a second source")}) {checks++;assertEquals(a,it.identity!!.documentId)}
        assertEquals(a,info.header.identity!!.documentId);assertTrue(info.pageIds.isEmpty());assertEquals(1,checks)
        assertEquals(listOf(input.name),root.list()!!.toList())
    }
    @Test fun unsupportedCommentedBundleReadsOneFreshStreamAndValidatesPages() = scenario {root->
        val input=bundle(root,anchored=true,comment=true);val bytes=input.readBytes();var opened=0;var closed=false
        val info=NativeReferenceReader.read(root,{null},{opened++;object:java.io.FilterInputStream(input.inputStream()) {
            override fun close() {closed=true;super.close()}
        }}) {}
        assertEquals(setOf(page),info.pageIds);assertEquals(a,info.header.identity!!.documentId)
        assertEquals(1,opened);assertTrue(closed);assertArrayEquals(bytes,input.readBytes());assertEquals(1,root.list()!!.size)
    }
    @Test fun anchoredFastHeaderCannotSubstituteForFullPageValidation() = scenario {root->
        for(pages in listOf("[]","[{\"items\":[]}]","[{\"reference_id\":\"bad\"}]",
            "[{\"reference_id\":\"$page\"},{\"reference_id\":\"$page\"}]",
            "[{\"reference_id\":\"$page\"},{}]")) {
            val input=bundle(root,anchored=true,pages=pages);var checked=false
            assertThrows(IllegalArgumentException::class.java) {read(root,input,BundleHeader.read(input)) {checked=true}}
            assertFalse(checked)
        }
        assertTrue(root.list()!!.all {it.startsWith("input-")})
    }
    @Test fun fastHeaderErrorsNeverTurnIntoAnAbsentIdentityOrFallback() = scenario {root->
        val parse=IllegalArgumentException("bad metadata")
        assertSame(parse,assertThrows(IllegalArgumentException::class.java) {
            NativeReferenceReader.read(root,{throw parse},{error("must not fallback")}) {}
        })
        assertTrue(root.list()!!.isEmpty())
    }
    @Test fun changedIdentityOrLostMarkerBetweenFastReadAndSnapshotIsRejected() = scenario {root->
        val original=bundle(root,anchored=true);val header=BundleHeader.read(original)
        for(replaced in listOf(bundle(root,id=b,anchored=true),bundle(root,anchored=false))) {
            var checked=false
            assertThrows(IllegalStateException::class.java) {read(root,replaced,header) {checked=true}}
            assertFalse(checked)
        }
    }
    @Test fun observedLocationAndMovedUuidContractsRunBeforeReturningIdentity() = scenario {root->
        val input=bundle(root);val header=BundleHeader.read(input)
        val missing=assertThrows(ReferenceContractViolation::class.java) {read(root,input,header) {
            ReferenceContract.verifyObserved(null,ReferenceExpectation(a,true),it)
        }}
        assertEquals(StorageMsg.REFERENCE_PAGE_METADATA_MISSING,missing.reason)
        val replaced=assertThrows(ReferenceContractViolation::class.java) {read(root,input,header) {
            ReferenceContract.verifyObserved(ReferenceExpectation(b,false),ReferenceExpectation(a,false),it)
        }}
        assertEquals(StorageMsg.REFERENCE_IDENTITY_MISMATCH,replaced.reason)
    }
    @Test fun failedSnapshotAndGuardReleaseTheirStreamAndPrivateFiles() = scenario {root->
        var closed=false
        val io=IOException("provider read failed")
        assertSame(io,assertThrows(IOException::class.java) {
            NativeReferenceReader.read(root,{null},{object:java.io.InputStream() {
                override fun read():Int=throw io
                override fun close() {closed=true}
            }}) {}
        });assertTrue(closed);assertTrue(root.list()!!.isEmpty())
        val input=bundle(root,anchored=true);val cancelled=CancellationException("cancel")
        assertSame(cancelled,assertThrows(CancellationException::class.java) {read(root,input) {throw cancelled}})
        assertEquals(1,root.list()!!.size)
    }
    @Test fun duplicatePayloadEntriesCannotHideBehindAValidFastMetadataHeader() = scenario {root->
        val input=File(root,"duplicate.zip")
        ZipOutputStream(input.outputStream()).use {z->
            BundleIdentity(a).write(z);AnchorMetadata(a).write(z)
            for(name in listOf("manifest.json","duplicate.bin")) {
                z.putNextEntry(ZipEntry(name))
                z.write("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page"}]}""".toByteArray())
                z.closeEntry()
            }
        }
        // Independent malformed fixture: both local and directory names become duplicates.
        val bytes=input.readBytes();val from="duplicate.bin".toByteArray();val to="manifest.json".toByteArray()
        assertEquals(from.size,to.size)
        for(at in 0..bytes.size-from.size) if(from.indices.all {bytes[at+it]==from[it]}) to.copyInto(bytes,at)
        input.writeBytes(bytes)
        val hint=BundleHeader.read(input)
        assertThrows(IllegalArgumentException::class.java) {read(root,input,hint)}
        assertEquals(1,root.list()!!.size)
    }
    @Test fun cancellationDuringRegistryGuardCannotReturnEitherHeaderOrSnapshotResult() = scenario {root->
        for(anchored in listOf(false,true)) {
            val input=bundle(root,anchored=anchored);var cancelled=false
            val signal=CancellationException("cancelled during registry query")
            assertSame(signal,assertThrows(CancellationException::class.java) {
                NativeReferenceReader.read(root,{BundleHeader.read(input)},{input.inputStream()},
                    checkActive={if(cancelled) throw signal}) {cancelled=true}
            })
        }
        assertTrue(root.list()!!.all {it.startsWith("input-")})
    }
    @Test fun cancellationDuringSnapshotStopsReadingClosesInputAndDeletesPartialFile() = scenario {root->
        val input=bundle(root,anchored=true);var cancelled=false;var closed=false
        val signal=CancellationException("interrupted reference read")
        assertSame(signal,assertThrows(CancellationException::class.java) {
            NativeReferenceReader.read(root,{null},{object:java.io.FilterInputStream(input.inputStream()) {
                override fun read(b:ByteArray,off:Int,len:Int):Int {
                    val n=super.read(b,off,len);cancelled=true;return n
                }
                override fun close() {closed=true;super.close()}
            }},checkActive={if(cancelled) throw signal}) {error("must not return cancelled source")}
        })
        assertTrue(closed);assertEquals(1,root.list()!!.size)
    }
    @Test fun legacyCanvasRemainsReadableButCannotClaimPageAnchorMetadata() = scenario {root->
        val canvas=bundle(root,format="xcanvas",pages="[]")
        assertEquals(a,read(root,canvas).header.identity!!.documentId)
        val wrong=bundle(root,format="xcanvas",pages="[]",anchored=true)
        assertThrows(IllegalArgumentException::class.java) {read(root,wrong)}
    }
}
