package com.xnotes.storage

import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.core.util.DocumentKind
import com.xnotes.kio.Uuids

interface ReferenceHeader {
    val identity: BundleIdentity?
    val anchors: AnchorMetadata?
}

interface ReferenceInfo {
    val header: ReferenceHeader
    val kind: DocumentKind?
    val pageIds: Set<String>
    val originalPdfHash: String?
}
