package com.xnotes.storage

import com.xnotes.format.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class NativeSaveContractTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private val page="00000000-0000-0000-0000-000000000003"
    private fun scenario(block:(File)->Unit) {
        val root=Files.createTempDirectory("native-save-contract-").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    private fun info(root:File,id:String?=a,pages:Boolean=false,format:String="xnote",pdf:String?=null,external:Boolean=false):BundleReferenceInfo {
        val file=File.createTempFile("version-",".zip",root)
        ZipOutputStream(file.outputStream()).use {z->
            id?.let {BundleIdentity(it,pdfResource=pdf,externalPdf=external).write(z)}
            if(pages) AnchorMetadata(requireNotNull(id)).write(z)
            val memberId=if(pages) "\"reference_id\":\"$page\"," else ""
            val members=if(format=="xnote") "\"pages\":[{$memberId\"items\":[]}]" else "\"items\":[]"
            z.putNextEntry(ZipEntry("manifest.json"));z.write("""{"format":"$format","version":1,"has_pdf":$external,$members}""".toByteArray());z.closeEntry()
        }
        return BundleReferenceInfo.inspect(file,validatePayload=true)
    }
    private fun verify(original:BundleReferenceInfo,next:BundleReferenceInfo,current:BundleReferenceInfo=original,
                       purpose:MigrationPublication.Purpose=MigrationPublication.Purpose.SAVE_NOTE,
                       expected:ReferenceExpectation?=null) = NativeSaveContract.verify(original,next,current,purpose,expected,expected)
    @Test fun ordinaryAndAlreadyPublishedVersionsKeepMatchingPageContracts() = scenario {root->
        for(pages in listOf(false,true)) {
            val original=info(root,pages=pages);val next=info(root,pages=pages)
            verify(original,next);verify(original,next,current=next,expected=ReferenceExpectation(a,pages))
        }
        val canvas=info(root,format="xcanvas")
        verify(canvas,canvas,purpose=MigrationPublication.Purpose.SAVE_CANVAS)
    }
    @Test fun sameUuidDoesNotAllowCrossFormatOrMismatchedSavePurpose() = scenario {root->
        val note=info(root);val canvas=info(root,format="xcanvas")
        assertThrows(ReferenceContractViolation::class.java) {verify(note,canvas)}
        assertThrows(ReferenceContractViolation::class.java) {verify(note,note,purpose=MigrationPublication.Purpose.SAVE_CANVAS)}
    }
    @Test fun normalSaveCannotAssignNewIdentityActivateOrStripPageMetadata() = scenario {root->
        val legacy=info(root);val anchored=info(root,pages=true)
        for((old,next) in listOf(legacy to info(root,id=b),legacy to info(root,id=null),
            info(root,id=null) to legacy,legacy to anchored,anchored to legacy)) {
            assertThrows(ReferenceContractViolation::class.java) {verify(old,next)}
        }
    }
    @Test fun changedLocationAndPreviouslyDegradedCurrentCannotBeRepairedByTheCandidate() = scenario {root->
        val valid=info(root,pages=true);val degraded=info(root)
        assertThrows(ReferenceContractViolation::class.java) {verify(valid,valid,current=degraded,expected=ReferenceExpectation(a,true))}
        assertThrows(ReferenceContractViolation::class.java) {verify(valid,valid,expected=ReferenceExpectation(b,false))}
    }
    @Test fun ordinarySaveCannotChangeTheImmutableOriginalPdfIdentity() = scenario {root->
        val original=info(root,pdf="a".repeat(64),external=true)
        verify(original,info(root,pdf="a".repeat(64),external=true))
        for(next in listOf(info(root,pdf="b".repeat(64),external=true),info(root),info(root,pdf="a".repeat(64)))) {
            assertThrows(ReferenceContractViolation::class.java) {verify(original,next)}
        }
    }
    @Test fun legacyMetadataOnlyInspectionCannotBeUsedAsAValidatedSaveVersion() = scenario {root->
        val file=File.createTempFile("header-",".zip",root)
        ZipOutputStream(file.outputStream()).use {BundleIdentity(a).write(it)}
        val only=BundleReferenceInfo.legacy(BundleHeader.read(file))
        assertThrows(ReferenceContractViolation::class.java) {verify(only,only)}
    }
}
