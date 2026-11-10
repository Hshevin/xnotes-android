package com.xnotes.ui

import com.xnotes.core.util.DocumentKind

/** Pure runtime identities only. A parked reader must not retain any native owner or model. */
internal class NativePresentationIdentity internal constructor(slots:List<NativePresentationSlotIdentity>) {
    internal val slots:List<NativePresentationSlotIdentity> = java.util.Collections.unmodifiableList(ArrayList(slots))
}

internal data class NativePresentationSlotIdentity(
    val pane:Pane,
    val runtimeToken:Any,
    val ownerToken:Any,
    val modelToken:Any,
    val ownerModelToken:Any,
    val open:Boolean,
    val kind:DocumentKind,
    val documentId:String,
    val path:String?,
    val adoptionGeneration:Long,
    val ownerAdoptionGeneration:Long,
    val current:Boolean,
)
