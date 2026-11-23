package com.xnotes.storage

import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleReferenceInfo

internal fun NativeSaveContract.verify(original:BundleReferenceInfo,candidate:BundleReferenceInfo,current:BundleReferenceInfo,
    purpose:MigrationPublication.Purpose,atLocation:ReferenceExpectation?,byIdentity:ReferenceExpectation?) {
    val expected=when(purpose) {
            MigrationPublication.Purpose.SAVE_NOTE -> DocumentKind.NOTE
            MigrationPublication.Purpose.SAVE_CANVAS -> DocumentKind.CANVAS
            MigrationPublication.Purpose.CREATE_NOTE,MigrationPublication.Purpose.CREATE_CANVAS,MigrationPublication.Purpose.ACTIVATE_PAGES -> throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        }
    verify(original,candidate,current,expected,atLocation,byIdentity)
}
