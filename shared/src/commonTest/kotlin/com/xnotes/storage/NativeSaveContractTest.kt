package com.xnotes.storage

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.openRead
import okio.*
import kotlin.test.assertFailsWith
import com.xnotes.format.*
import com.xnotes.core.testkit.*
import kotlin.test.Test

class NativeSaveContractTest : BundleTestFiles() {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private val page="00000000-0000-0000-0000-000000000003"
    private fun scenario(block:(AssetFile)->Unit) {
        val root=testDirectory("native-save-contract-")
        try {block(root)} finally {root.deleteTree()}
    }
    private fun info(root:AssetFile,id:String?=a,pages:Boolean=false,format:String="xnote",pdf:String?=null,external:Boolean=false):BundleReferenceInfo {
        val file=temporaryAsset("version-",".zip",root)
        bundleWriter(file.fileSink()).use {z->
            id?.let {z.addIdentity(BundleIdentity(it,pdfResource=pdf,externalPdf=external))}
            if(pages) z.addMarker(AnchorMetadata(requireNotNull(id)))
            val memberId=if(pages) "\"reference_id\":\"$page\"," else ""
            val members=if(format=="xnote") "\"pages\":[{$memberId\"items\":[]}]" else "\"items\":[]"
            z.putDeflated("manifest.json","""{"format":"$format","version":1,"has_pdf":$external,$members}""".encodeToByteArray())
        }
        return BundleReferenceInfo.inspect(file,validatePayload=true)
    }
    private fun verify(original:BundleReferenceInfo,next:BundleReferenceInfo,current:BundleReferenceInfo=original,
                       purpose:PublicationPurpose=PublicationPurpose.SAVE_NOTE,
                       expected:ReferenceExpectation?=null) = NativeSaveContract.verify(original,next,current,purpose,expected,expected)
    @Test fun ordinaryAndAlreadyPublishedVersionsKeepMatchingPageContracts() = scenario {root->
        for(pages in listOf(false,true)) {
            val original=info(root,pages=pages);val next=info(root,pages=pages)
            verify(original,next);verify(original,next,current=next,expected=ReferenceExpectation(a,pages))
        }
        val canvas=info(root,format="xcanvas")
        verify(canvas,canvas,purpose=PublicationPurpose.SAVE_CANVAS)
    }
    @Test fun sameUuidDoesNotAllowCrossFormatOrMismatchedSavePurpose() = scenario {root->
        val note=info(root);val canvas=info(root,format="xcanvas")
        assertFailsWith<ReferenceContractViolation> {verify(note,canvas)}
        assertFailsWith<ReferenceContractViolation> {verify(note,note,purpose=PublicationPurpose.SAVE_CANVAS)}
    }
    @Test fun normalSaveCannotAssignNewIdentityActivateOrStripPageMetadata() = scenario {root->
        val legacy=info(root);val anchored=info(root,pages=true)
        for((old,next) in listOf(legacy to info(root,id=b),legacy to info(root,id=null),
            info(root,id=null) to legacy,legacy to anchored,anchored to legacy)) {
            assertFailsWith<ReferenceContractViolation> {verify(old,next)}
        }
    }
    @Test fun changedLocationAndPreviouslyDegradedCurrentCannotBeRepairedByTheCandidate() = scenario {root->
        val valid=info(root,pages=true);val degraded=info(root)
        assertFailsWith<ReferenceContractViolation> {verify(valid,valid,current=degraded,expected=ReferenceExpectation(a,true))}
        assertFailsWith<ReferenceContractViolation> {verify(valid,valid,expected=ReferenceExpectation(b,false))}
    }
    @Test fun ordinarySaveCannotChangeTheImmutableOriginalPdfIdentity() = scenario {root->
        val original=info(root,pdf="a".repeat(64),external=true)
        verify(original,info(root,pdf="a".repeat(64),external=true))
        for(next in listOf(info(root,pdf="b".repeat(64),external=true),info(root),info(root,pdf="a".repeat(64)))) {
            assertFailsWith<ReferenceContractViolation> {verify(original,next)}
        }
    }
    @Test fun legacyMetadataOnlyInspectionCannotBeUsedAsAValidatedSaveVersion() = scenario {root->
        val file=temporaryAsset("header-",".zip",root)
        bundleWriter(file.fileSink()).use {it.addIdentity(BundleIdentity(a))}
        val only=BundleReferenceInfo.legacy(BundleHeader.read(file))
        assertFailsWith<ReferenceContractViolation> {verify(only,only)}
    }
}
