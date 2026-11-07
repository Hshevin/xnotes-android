package com.xnotes.storage

import com.xnotes.format.BundleHeader
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.BundleSnapshot
import java.io.File
import java.io.InputStream

/** Fast metadata absence is distinct from an unsupported layout and from a parse/permission failure. */
internal object NativeReferenceReader {
    fun read(directory:File,tryHeader:()->BundleHeader?,open:()->InputStream,
             checkActive:()->Unit={},check:(BundleHeader)->Unit):BundleReferenceInfo {
        checkActive()
        val hint=tryHeader() // Exceptions propagate; only an explicit null requests a fresh stream.
        checkActive()
        if(hint!=null && hint.anchors==null) {
            check(hint);checkActive()
            return BundleReferenceInfo.legacy(hint)
        }
        val cancelled={checkActive();false}
        return BundleSnapshot.fromInput(open(),directory,cancelled).use {snapshot->
            val info=BundleReferenceInfo.inspect(snapshot.file,validatePayload=false,cancelled=cancelled)
            if(hint!=null) kotlin.check(hint.identity==info.header.identity && hint.anchors==info.header.anchors) {
                "Source reference identity changed while reading"
            }
            checkActive();check(info.header);checkActive()
            info
        }
    }
}
