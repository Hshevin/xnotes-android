package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.*

internal enum class OriginalPdfLibraryIssue { UNAVAILABLE, CHANGED }

/** Retained library state has no Activity, View, native Editor, or writable source URI. */
@MainThread
internal class OriginalPdfLibraryState(context:Context,private val scope:CoroutineScope,
    private val access:OriginalPdfAccess=OriginalPdfAccess(context),
    private val catalog:OriginalPdfCatalog=OriginalPdfCatalog.get(context)) {
    internal class RestoreRequest internal constructor(val id:String,val expected:OriginalPdfCatalog.Observation,
        internal val lifetime:Long)
    // Preserve wrapped storage roots in host tests without retaining a real Activity.
    private val resolver=context.contentResolver
    private val cache=context.cacheDir
    @Volatile private var alive=true
    @Volatile private var lifetime=0L
    @Volatile private var restoreId:String?=null
    private var work:Job?=null
    var visible by mutableStateOf(false);private set
    var busy by mutableStateOf(false);private set
    var records by mutableStateOf<List<OriginalPdfCatalog.Observation>>(emptyList());private set
    var limited by mutableStateOf(false);private set
    var failure by mutableStateOf<OriginalPdfLibraryIssue?>(null);private set
    var restoreRequest by mutableStateOf<RestoreRequest?>(null);private set
    var needsRestoreFile by mutableStateOf(false);private set

    fun open():Boolean {
        if(!alive || visible) return false
        lifetime++;visible=true;failure=null;records=emptyList();refresh();return true
    }
    fun close() {
        lifetime++;visible=false;work?.cancel();work=null;busy=false
        restoreId=null;restoreRequest=null;needsRestoreFile=false;failure=null
    }
    fun current()=alive && visible
    fun captureOpen(expected:OriginalPdfCatalog.Observation):(() -> Boolean)? {
        if(!current() || busy || restoreRequest!=null || expected.record.deleted || !expected.current()) return null
        val token=lifetime
        return {alive && lifetime==token && visible && !busy && restoreRequest==null && expected.current()}
    }
    private fun check(token:Long) {
        if(!alive || lifetime!=token) throw CancellationException("Original library request retired")
    }
    private fun check(request:RestoreRequest) {
        check(request.lifetime)
        if(restoreId!=request.id) throw CancellationException("Original relink request retired")
    }
    private fun start(block:suspend (Long)->Unit):Boolean {
        if(!current() || busy) return false
        val token=lifetime;failure=null;busy=true
        work=scope.launch(start=CoroutineStart.LAZY) {
            try {block(token)}
            catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {if(alive && lifetime==token) {
                if(restoreRequest?.expected?.current()==false) {
                    restoreId=null;restoreRequest=null;needsRestoreFile=false
                    try {reload(token)} catch(_:Exception) { /* Keep existing visible rows on unreadable catalog data. */ }
                }
                failure=OriginalPdfLibraryIssue.UNAVAILABLE
            }}
            finally {if(alive && lifetime==token) {busy=false;work=null}}
        }.also {it.start()}
        return true
    }
    private suspend fun reload(token:Long) {
        val listing=withContext(Dispatchers.IO) {check(token);catalog.list()}
        check(token);records=listing.records;limited=listing.limited
    }
    fun refresh():Boolean {
        if(restoreRequest!=null) return false
        return start {reload(it)}
    }
    /** UI confirms the exact observation before invoking this operation. */
    fun delete(expected:OriginalPdfCatalog.Observation):Boolean {
        if(restoreRequest!=null || expected.record.deleted || !expected.current()) return false
        return start {token->
            withContext(Dispatchers.IO) {check(token);catalog.delete(expected)}
            reload(token)
        }
    }
    /** A new explicit restore/relink action signs one request, retained across picker recreation. */
    fun requestRestore(expected:OriginalPdfCatalog.Observation):RestoreRequest? {
        if(!current() || busy || restoreRequest!=null || !expected.current()) return null
        val request=RestoreRequest(UUID.randomUUID().toString(),expected,lifetime)
        restoreRequest=request;restoreId=request.id;needsRestoreFile=false
        if(!start {token->
            val restored=withContext(Dispatchers.IO) {
                check(request)
                val candidate=try {access.candidate(expected.record.sha256,expected.record.title) {check(request)}}
                    catch(cancelled:CancellationException) {throw cancelled}
                    catch(_:Exception) {return@withContext false}
                try {check(request);catalog.restore(expected,candidate);true}
                finally {candidate.closeAndAwait()}
            }
            check(token)
            if(restored) {restoreId=null;restoreRequest=null;needsRestoreFile=false;reload(token)}
            else if(expected.current()) needsRestoreFile=true
            else {restoreId=null;restoreRequest=null;failure=OriginalPdfLibraryIssue.CHANGED}
        }) {restoreId=null;restoreRequest=null;return null}
        return request
    }
    fun cancelRestore(id:String) {
        val request=restoreRequest?.takeIf {it.id==id} ?: return
        if(busy) return
        check(request);restoreId=null;restoreRequest=null;needsRestoreFile=false
    }
    fun receiveRestoreFile(id:String,uri:Uri?):Boolean {
        val request=restoreRequest?.takeIf {it.id==id && needsRestoreFile && it.lifetime==lifetime} ?: return false
        if(!current() || busy || !request.expected.current()) return false
        if(uri==null) {cancelRestore(id);return true}
        return start {token->
            withContext(Dispatchers.IO) {
                check(request)
                val temp=File.createTempFile("original-relink-",".pdf",cache)
                try {
                    requireNotNull(resolver.openInputStream(uri)).use {input->FileOutputStream(temp).use {out->
                        val bytes=ByteArray(65536)
                        while(true) {check(request);val n=input.read(bytes);if(n<0) break;out.write(bytes,0,n)}
                        out.fd.sync()
                    }}
                    check(request)
                    val candidate=access.relink(temp,request.expected,request.expected.record.title) {check(request)}
                    try {check(request);catalog.restore(request.expected,candidate)} finally {candidate.closeAndAwait()}
                } finally {temp.delete()}
            }
            check(token);restoreId=null;restoreRequest=null;needsRestoreFile=false;reload(token)
        }
    }
    fun reportChanged() {failure=OriginalPdfLibraryIssue.CHANGED}
    fun reportUnavailable() {failure=OriginalPdfLibraryIssue.UNAVAILABLE}
    fun clearFailure() {failure=null}
    fun dispose() {if(!alive) return;close();alive=false}
}
