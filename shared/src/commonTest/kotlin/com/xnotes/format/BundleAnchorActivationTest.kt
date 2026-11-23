package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.testkit.*
import kotlin.test.Test

/** Narrow activation authority is checked against raw bytes, never a decoded note model. */
class BundleAnchorActivationTest : BundleTestFiles() {
    private val documentId="00000000-0000-4000-8000-000000000001"
    private val first="00000000-0000-4000-8000-000000000011"
    private val second="00000000-0000-4000-8000-000000000012"
    private val identity=""" {"version":1,"documentId":"$documentId","pdfResource":null} """.encodeToByteArray()
    private val marker=AnchorMetadata(documentId).bytes()
    private fun scenario(block:(AssetFile)->Unit) {
        val dir=testDirectory("verify-activation-")
        try {block(dir)} finally {dir.deleteTree()}
    }
    private fun pattern(padding:Int=0)=""" {"format":"xnote","version":1,"has_pdf":true,"pages":[ {"items":[{"kind":"future","samples":[[-0,1.20000e+02,0.1234567890123456789,1e9999]],"reference_id":"nested-only","text":"🖊中\\u4e2d","extension":{"pages":[{}]}}],"unknown":{"keep":true}}@A@, {  @B@} ],"extension":{"pages":[{"reference_id":"not-a-page"}],"raw":"${"x".repeat(padding)}🖊"}}  """
    // The first token follows the closing brace solely to keep insertion sites visible in the fixture.
    private fun sourceManifest(padding:Int=0)=pattern(padding).replace("}@A@","@A@}")
        .replace("@A@","").replace("@B@","")
    private fun activatedManifest(padding:Int=0,a:String=first,b:String=second)=pattern(padding)
        .replace("}@A@","@A@}")
        .replace("@A@",",\"reference_id\":\"$a\"")
        .replace("@B@","\"reference_id\":\"$b\"")
    private fun entries(manifest:String,marked:Boolean)=linkedMapOf(
        "manifest.json" to manifest.encodeToByteArray(),
        BundlePdf.ENTRY to ByteArray(300_001) {(it*17).toByte()},
        "future/unrenderable.bin" to byteArrayOf(0,-1,3,7),
        BundleIdentity.ENTRY to identity,
    ).apply {if(marked) put(AnchorMetadata.ENTRY,marker)}
    private fun bundle(dir:AssetFile,data:Map<String,ByteArray>)=temporaryAsset("bundle-",".xnote",dir).also {file->
        bundleWriter(file.fileSink(),comment="future container metadata").use {z->
            for((name,bytes) in data) {z.putDeflated(name,bytes)}
        }
    }
    private fun contents(file:AssetFile)=bundleDirectory(file).use {z->
        z.entries.associateTo(linkedMapOf()) {entry->entry.name to entry.open().buffer().use {it.readByteArray()}}
    }
    private fun rejects(original:AssetFile,candidate:AssetFile) {
        val before=original.fileBytes();val after=candidate.fileBytes()
        try {BundleAnchors.verifyActivation(original,candidate);fail("Not a narrow page-ID activation")}
        catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} catch(_:JsonPullException) {}
        assertArrayEquals(before,original.fileBytes());assertArrayEquals(after,candidate.fileBytes())
    }
    @Test fun verifiesTheActualPreparedCandidateAgainAfterItsRetainedFileIsRenamedWithoutWritingEitherFile()=scenario {dir->
        val original=bundle(dir,entries(sourceManifest(),false));val raw=original.fileBytes()
        val prepared=BundleAnchors.prepare(original,dir)
        val retained=dir.child("retained-after-restart.xnote")
        assertTrue(runCatching {atomicReplace(prepared.file.assetPath(),retained.assetPath())}.isSuccess);val candidateBytes=retained.fileBytes()
        repeat(2) {BundleAnchors.verifyActivation(original,retained)}
        assertArrayEquals(raw,original.fileBytes());assertArrayEquals(candidateBytes,retained.fileBytes())
        val old=contents(original);val next=contents(retained)
        assertEquals(old.keys+AnchorMetadata.ENTRY,next.keys)
        for((name,bytes) in old) if(name!="manifest.json") assertTrue(name,bytes.contentEquals(next.getValue(name)))
        assertEquals(2,AnchorManifest.inspectSource({next.getValue("manifest.json").asSource()},true).identities.size)
    }
    @Test fun acceptsChosenActualIdsAndPreservesUnicodeEscapesNumbersUnknownFieldsAndBufferBoundaries()=scenario {dir->
        for(padding in listOf(0,16360,32760)) {
            val original=bundle(dir,entries(sourceManifest(padding),false))
            val candidate=bundle(dir,entries(activatedManifest(padding),true))
            val old=original.fileBytes();val next=candidate.fileBytes()
            BundleAnchors.verifyActivation(original,candidate)
            assertArrayEquals(old,original.fileBytes());assertArrayEquals(next,candidate.fileBytes())
            val ids=AnchorManifest.inspectSource({contents(candidate).getValue("manifest.json").asSource()},true).orderedPageIds
            assertEquals(listOf(first,second),ids)
        }
    }
    @Test fun aCorrectUuidAndMarkerCannotAuthorizeChangedInkUnknownJsonNestedIdsOrNumberAndWhitespaceBytes()=scenario {dir->
        val original=bundle(dir,entries(sourceManifest(),false))
        for(manifest in listOf(
            activatedManifest().replace("1.20000e+02","120.0"),
            activatedManifest().replace("0.1234567890123456789","0.3"),
            activatedManifest().replace("\"keep\":true","\"keep\":false"),
            activatedManifest().replace("nested-only","$first"),
            activatedManifest().replace("not-a-page","$second"),
            activatedManifest().replace("\"has_pdf\":true","\"has_pdf\" :true"),
            activatedManifest().replace("🖊中","🖊文"),
        )) rejects(original,bundle(dir,entries(manifest,true)))
    }
    @Test fun allOriginalPayloadsAndTheirEntryNamesIncludingPdfAndRawIdentityMustStayExact()=scenario {dir->
        val original=bundle(dir,entries(sourceManifest(),false));val valid=entries(activatedManifest(),true)
        for(name in listOf(BundlePdf.ENTRY,"future/unrenderable.bin",BundleIdentity.ENTRY)) {
            val changed=valid.toMutableMap()
            changed[name]=if(name==BundleIdentity.ENTRY) identity.decodeToString().trim().encodeToByteArray() else valid.getValue(name)+byteArrayOf(42)
            rejects(original,bundle(dir,changed))
        }
        for(changed in listOf(
            valid.toMutableMap().apply {remove("future/unrenderable.bin")},
            valid.toMutableMap().apply {put("future/new.bin",byteArrayOf(1))},
            valid.toMutableMap().apply {put("future/renamed.bin",remove("future/unrenderable.bin")!!)},
        )) rejects(original,bundle(dir,changed))
    }
    @Test fun alreadyAnchoredAndUnmarkedOrphanPartialOrDuplicateOriginalIdsCannotGrantActivation()=scenario {dir->
        val candidate=bundle(dir,entries(activatedManifest(),true))
        rejects(candidate,candidate)
        for(manifest in listOf(
            activatedManifest(),
            activatedManifest().replace(",\"reference_id\":\"$first\"",""),
            activatedManifest(a=first,b=first),
        )) rejects(bundle(dir,entries(manifest,false)),candidate)
        // prepare's legacy-compatible orphan behavior does not widen publication authority.
        val orphan=bundle(dir,entries(activatedManifest(),false))
        val prepared=BundleAnchors.prepare(orphan,dir)
        rejects(orphan,prepared.file)
    }
    @Test fun missingDuplicateInvalidOrNonStringCandidateIdsAndChangedPageCountAreRejected()=scenario {dir->
        val original=bundle(dir,entries(sourceManifest(),false))
        for(manifest in listOf(
            activatedManifest().replace(",\"reference_id\":\"$first\"",""),
            activatedManifest(a=first,b=first),
            activatedManifest(a="invalid"),
            activatedManifest().replace("\"reference_id\":\"$first\"","\"reference_id\":null"),
            activatedManifest().replace("\"reference_id\":\"$first\"","\"reference_id\":\"$first\",\"reference_id\":\"$second\""),
            activatedManifest().replace(", {  \"reference_id\":\"$second\"}",""),
            activatedManifest().replace("\"pages\":[ ","\"pages\":[{\"reference_id\":\"00000000-0000-4000-8000-000000000099\"}, "),
            activatedManifest().replace("\"version\":1","\"version\":2"),
        )) rejects(original,bundle(dir,entries(manifest,true)))
    }
    @Test fun onlyTheExactNewValidSameDocumentMarkerIsAllowed()=scenario {dir->
        val original=bundle(dir,entries(sourceManifest(),false));val valid=entries(activatedManifest(),true)
        for(bytes in listOf(
            marker.decodeToString().replace(documentId,first).encodeToByteArray(),
            marker.decodeToString().replace("\"version\":1","\"version\":2").encodeToByteArray(),
            marker.decodeToString().replace("}",",\"unknown\":1}").encodeToByteArray(),
            ByteArray(8192) {32},
        )) rejects(original,bundle(dir,valid.toMutableMap().apply {put(AnchorMetadata.ENTRY,bytes)}))
        rejects(original,bundle(dir,valid.filterKeys {it!=AnchorMetadata.ENTRY}))
        // Same semantic marker but changed raw metadata is outside the actual insertion plan.
        rejects(original,bundle(dir,valid.toMutableMap().apply {put(AnchorMetadata.ENTRY,byteArrayOf(32)+marker)}))
    }
    @Test fun invalidSourceStructureIdentityAndCanvasAreNotRepairableByAnOtherwiseValidCandidate()=scenario {dir->
        val candidate=bundle(dir,entries(activatedManifest(),true))
        for(manifest in listOf(
            sourceManifest().replace("\"version\":1","\"version\":2"),
            """{"format":"xnote","version":1,"has_pdf":true,"pages":[]}""",
            """{"format":"xcanvas","version":1,"items":[]}""",
        )) rejects(bundle(dir,entries(manifest,false)),candidate)
        rejects(bundle(dir,entries(sourceManifest(),false).filterKeys {it!=BundleIdentity.ENTRY}),candidate)
        rejects(bundle(dir,entries(sourceManifest(),false).toMutableMap().apply {
            put(BundleIdentity.ENTRY,identity.decodeToString().replace(documentId,first).encodeToByteArray())
        }),candidate)
    }
    @Test fun immediateAndMidVerificationCancellationNeverDeleteOrModifyEitherRetainedInput()=scenario {dir->
        val original=bundle(dir,entries(sourceManifest(180000),false))
        val candidate=bundle(dir,entries(activatedManifest(180000),true))
        val a=original.fileBytes();val b=candidate.fileBytes();var checks=0
        BundleAnchors.verifyActivation(original,candidate,cancelled={checks++;false})
        assertTrue("Check cancellation throughout long payloads and manifest scanning",checks>20)
        for(at in listOf(1,checks/2,checks)) {
            var observed=0
            assertFailsWith<IllegalStateException> {
                BundleAnchors.verifyActivation(original,candidate,cancelled={++observed>=at})
            }
            assertTrue(observed>=at)
            assertArrayEquals(a,original.fileBytes());assertArrayEquals(b,candidate.fileBytes())
        }
    }
    @Test fun suppliedIdsMustBeCompleteCanonicalAndDistinctAndBothManifestsMustReachEof()=scenario {dir->
        val source=sourceManifest().encodeToByteArray()
        for(ids in listOf(emptyList(),listOf(first),listOf(first,second,documentId),listOf(first,first),
            listOf(first,"AAAAAAAA-0000-4000-8000-000000000012"),listOf(first,"0-0-0-0-12"))) {
            assertFailsWith<IllegalArgumentException> {
                AnchorManifest.inspectActivationSource({source.asSource()},ids)
            }
        }
        val original=bundle(dir,entries(sourceManifest(),false))
        val candidate=bundle(dir,entries(activatedManifest(),true))
        rejects(original,bundle(dir,entries(activatedManifest()+"{}",true)))
        rejects(bundle(dir,entries(sourceManifest()+"{}",false)),candidate)
    }
    @Test fun invalidPdfContainerContractsAreNotNeedsActivationEvenWhenTheCandidatePreservesEveryPayload()=scenario {dir->
        val mutations=listOf<(MutableMap<String,ByteArray>)->Unit>(
            {it.remove(BundlePdf.ENTRY)},
            {it["manifest.json"]=it.getValue("manifest.json").decodeToString().replace("\"has_pdf\":true","\"has_pdf\":\"true\"").encodeToByteArray()},
            {it["manifest.json"]=it.getValue("manifest.json").decodeToString().replace("\"has_pdf\":true","\"has_pdf\":false").encodeToByteArray()},
            {it[BundleIdentity.ENTRY]="""{"version":2,"documentId":"$documentId","pdfResource":"${"a".repeat(64)}"}""".encodeToByteArray()},
        )
        for(mutate in mutations) {
            val original=entries(sourceManifest(),false).apply(mutate)
            val candidate=entries(activatedManifest(),true).apply(mutate)
            // The same defect in A and B must not pass as an otherwise byte-preserving upgrade.
            rejects(bundle(dir,original),bundle(dir,candidate))
        }
    }
}
