package com.xnotes.storage

import com.xnotes.format.BundleReferenceInfo

internal fun NativeSaveContract.verify(original:BundleReferenceInfo,candidate:BundleReferenceInfo,current:BundleReferenceInfo,
    purpose:MigrationPublication.Purpose,atLocation:ReferenceExpectation?,byIdentity:ReferenceExpectation?) {
    verify(original,candidate,current,purpose.core,atLocation,byIdentity)
}
