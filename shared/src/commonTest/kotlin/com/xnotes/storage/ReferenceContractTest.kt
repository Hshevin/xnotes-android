package com.xnotes.storage

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.openRead
import okio.*
import kotlin.test.assertFailsWith
import com.xnotes.format.*
import com.xnotes.core.testkit.*
import kotlin.test.Test

class ReferenceContractTest : BundleTestFiles() {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    private fun header(id:String?=a,pages:Boolean=false,markerId:String?=id):BundleHeader {
        val dir=testDirectory("reference-contract-")
        try {
            val file=dir.child("source.zip")
            bundleWriter(file.fileSink()).use {z->
                id?.let {z.addIdentity(BundleIdentity(it))}
                if(pages) z.addMarker(AnchorMetadata(requireNotNull(markerId)))
            }
            return BundleHeader.read(file)
        } finally {dir.deleteTree()}
    }
    @Test fun observedContractUsesBothKnownLocationAndIndependentUuidEvidence() {
        ReferenceContract.verifyObserved(ReferenceExpectation(a,false),ReferenceExpectation(a,true),header(pages=true))
        assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyObserved(null,ReferenceExpectation(a,true),header())}
        assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyObserved(ReferenceExpectation(a,false),ReferenceExpectation(b,false),header(b))}
    }
    @Test fun ordinaryWriteCannotSilentlyRepairAnAlreadyDegradedCurrentFileOrAnotherDocument() {
        val valid=header(pages=true)
        for(current in listOf(header(),header(b,pages=true),header(id=null))) {
            assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyWrite(ReferenceExpectation(a,true),ReferenceExpectation(a,true),current,valid)}
        }
    }
    @Test fun candidateCannotChangeIdentityOrStripCurrentPageMetadataEvenWithoutAnIndexEntry() {
        val current=header(pages=true)
        assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyWrite(null,null,current,header())}
        assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyWrite(null,null,current,header(b,pages=true))}
        assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyWrite(null,null,current,header(id=null))}
    }
    @Test fun matchingLegacyAndPageHeadersPassButUnidentifiedExistingFilesNeedExplicitUpgrade() {
        ReferenceContract.verifyWrite(null,null,header(),header())
        ReferenceContract.verifyWrite(ReferenceExpectation(a,true),ReferenceExpectation(a,true),header(pages=true),header(pages=true))
        ReferenceContract.verifyObserved(null,null,header(id=null))
        assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyWrite(null,null,header(id=null),header())}
    }
    @Test fun failuresKeepDistinctReasonsAndWrongMarkerBindingNeverReachesTheGuard() {
        val missing=assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyObserved(null,ReferenceExpectation(a,true),header())}
        assertEquals(StorageMsg.REFERENCE_PAGE_METADATA_MISSING,missing.reason)
        val changed=assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyObserved(ReferenceExpectation(a,false),null,header(b))}
        assertEquals(StorageMsg.REFERENCE_IDENTITY_MISMATCH,changed.reason)
        val noIdentity=assertFailsWith<ReferenceContractViolation> {ReferenceContract.verifyWrite(null,null,header(id=null),header())}
        assertEquals(StorageMsg.REFERENCE_IDENTITY_MISSING,noIdentity.reason)
        assertFailsWith<IllegalArgumentException> {header(pages=true,markerId=b)}
    }
}
