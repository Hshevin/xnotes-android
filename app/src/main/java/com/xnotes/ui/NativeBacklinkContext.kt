package com.xnotes.ui

/** Page capability for a read-only reverse lookup. Unavailable pages never receive temporary IDs. */
internal enum class BacklinkPageIdentity { AVAILABLE,NOT_ENABLED,INVALID }

/** An actual native reading context; no body, PDF holder, source version or write authority. */
internal class NativeBacklinkContext private constructor(
    val documentId:String,val pageId:String?,val pageNumber:Int?,val pageIdentity:BacklinkPageIdentity,
    roots:List<String>,private val valid:()->Boolean,
) {
    val libraryRoots:List<String> = java.util.Collections.unmodifiableList(ArrayList(roots))
    fun current():Boolean=valid()
    companion object {
        internal fun observe(documentId:String,pageId:String?,pageNumber:Int?,identity:BacklinkPageIdentity,
            roots:List<String>,valid:()->Boolean)=NativeBacklinkContext(documentId,pageId,pageNumber,identity,roots,valid)
    }
}
