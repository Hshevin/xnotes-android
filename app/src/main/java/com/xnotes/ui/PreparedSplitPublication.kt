package com.xnotes.ui

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** A prepared arrangement owns files only; it never retains an Editor or a source save proof. */
internal class PreparedSplitPublication internal constructor(
    private val filesDir:File,
    private val folder:File,
    private val stage:Path,
    private val stem:String,
    private val fileOps:SplitArrangementStore.FileOps,
) {
    private var consumed=false
    private var closed=false
    private var published:File?=null
    val publishedFile:File? get()=synchronized(this) {published}

    /** publishNew is the commit. No cleanup or subsequent caller cancellation can revoke it. */
    fun commit():File?=synchronized(this) {
        if(consumed || closed) return@synchronized null
        consumed=true
        try {
            require(stage.toFile().canonicalFile.parentFile==folder && Files.isRegularFile(stage,NOFOLLOW_LINKS))
            for(sequence in 0 until 256) {
                require(SplitArrangementPaths.directory(filesDir,create=false)==folder)
                val candidate=File(folder,stem+(if(sequence==0) "" else "_$sequence")+".xsplit")
                try {
                    fileOps.publishNew(candidate.toPath(),stage)
                    published=candidate
                    return@synchronized candidate
                } catch(_:FileAlreadyExistsException) {continue}
            }
            null
        } catch(error:Exception) {
            // Only the failure type: a platform refusal must stay distinguishable from a source check.
            runCatching {android.util.Log.w("xnotes.split","Arrangement publication failed: ${error::class.java.simpleName}")}
            null
        }
    }

    fun close() {
        val cleanup=synchronized(this) {if(closed) false else {closed=true;true}}
        if(cleanup) try {fileOps.deleteIfExists(stage)} catch(_:Exception) {}
    }
}
