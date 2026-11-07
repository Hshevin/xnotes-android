package com.xnotes.storage

import com.xnotes.format.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ReferenceContractTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private fun header(id:String?=a,pages:Boolean=false,markerId:String?=id):BundleHeader {
        val dir=Files.createTempDirectory("reference-contract-").toFile()
        try {
            val file=File(dir,"source.zip")
            ZipOutputStream(file.outputStream()).use {z->
                id?.let {BundleIdentity(it).write(z)}
                if(pages) AnchorMetadata(requireNotNull(markerId)).write(z)
            }
            return BundleHeader.read(file)
        } finally {dir.deleteRecursively()}
    }
    @Test fun observedContractUsesBothKnownLocationAndIndependentUuidEvidence() {
        ReferenceContract.verifyObserved(ReferenceExpectation(a,false),ReferenceExpectation(a,true),header(pages=true))
        assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyObserved(null,ReferenceExpectation(a,true),header())}
        assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyObserved(ReferenceExpectation(a,false),ReferenceExpectation(b,false),header(b))}
    }
    @Test fun ordinaryWriteCannotSilentlyRepairAnAlreadyDegradedCurrentFileOrAnotherDocument() {
        val valid=header(pages=true)
        for(current in listOf(header(),header(b,pages=true),header(id=null))) {
            assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyWrite(ReferenceExpectation(a,true),ReferenceExpectation(a,true),current,valid)}
        }
    }
    @Test fun candidateCannotChangeIdentityOrStripCurrentPageMetadataEvenWithoutAnIndexEntry() {
        val current=header(pages=true)
        assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyWrite(null,null,current,header())}
        assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyWrite(null,null,current,header(b,pages=true))}
        assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyWrite(null,null,current,header(id=null))}
    }
    @Test fun matchingLegacyAndPageHeadersPassButUnidentifiedExistingFilesNeedExplicitUpgrade() {
        ReferenceContract.verifyWrite(null,null,header(),header())
        ReferenceContract.verifyWrite(ReferenceExpectation(a,true),ReferenceExpectation(a,true),header(pages=true),header(pages=true))
        ReferenceContract.verifyObserved(null,null,header(id=null))
        assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyWrite(null,null,header(id=null),header())}
    }
    @Test fun failuresKeepDistinctReasonsAndWrongMarkerBindingNeverReachesTheGuard() {
        val missing=assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyObserved(null,ReferenceExpectation(a,true),header())}
        assertEquals(StorageMsg.REFERENCE_PAGE_METADATA_MISSING,missing.reason)
        val changed=assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyObserved(ReferenceExpectation(a,false),null,header(b))}
        assertEquals(StorageMsg.REFERENCE_IDENTITY_MISMATCH,changed.reason)
        val noIdentity=assertThrows(ReferenceContractViolation::class.java) {ReferenceContract.verifyWrite(null,null,header(id=null),header())}
        assertEquals(StorageMsg.REFERENCE_IDENTITY_MISSING,noIdentity.reason)
        assertThrows(IllegalArgumentException::class.java) {header(pages=true,markerId=b)}
    }
}
