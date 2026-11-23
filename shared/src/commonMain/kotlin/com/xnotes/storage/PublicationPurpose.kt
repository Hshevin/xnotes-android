package com.xnotes.storage

import com.xnotes.core.util.DocumentKind

enum class PublicationPurpose(val key: String) {
    SAVE_NOTE("save-note"), SAVE_CANVAS("save-canvas"), CREATE_NOTE("create-note"), CREATE_CANVAS("create-canvas"), ACTIVATE_PAGES("activate-pages");

    val ordinarySave get() = this == SAVE_NOTE || this == SAVE_CANVAS
    val creation get() = this == CREATE_NOTE || this == CREATE_CANVAS
}

fun NativeSaveContract.verify(original: ReferenceInfo, candidate: ReferenceInfo, current: ReferenceInfo,
                              purpose: PublicationPurpose, atLocation: ReferenceExpectation?, byIdentity: ReferenceExpectation?) {
    val kind = when (purpose) {
        PublicationPurpose.SAVE_NOTE -> DocumentKind.NOTE
        PublicationPurpose.SAVE_CANVAS -> DocumentKind.CANVAS
        PublicationPurpose.CREATE_NOTE, PublicationPurpose.CREATE_CANVAS, PublicationPurpose.ACTIVATE_PAGES ->
            throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    }
    verify(original, candidate, current, kind, atLocation, byIdentity)
}
