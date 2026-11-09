package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.xnotes.storage.NativeSourceSnapshot
import com.xnotes.storage.NotebookStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class MapSourceChoiceProblem { READ,CHANGED,ACTIVATION }
internal data class MapSourceChoice(val requestId:String,val uri:Uri,val offer:MapSourceOffer?=null,
    val busy:Boolean=false,val problem:MapSourceChoiceProblem?=null,val canResume:Boolean=false)

/** UI transaction only: one original picker ticket, one owned job, and immutable observed data. */
internal class MapSourcePickerController(private val context:Context,private val scope:CoroutineScope,
    private val current:(String)->Boolean,private val accept:(String,MapSource)->Boolean,
    private val cancelRequest:(String)->Unit,private val inkBusy:()->Boolean) {
    var state by mutableStateOf<MapSourceChoice?>(null);private set
    private var job:Job?=null
    private fun live(choice:MapSourceChoice)=state===choice && current(choice.requestId)
    fun retire() {job?.cancel();job=null;state=null}
    fun retireRequest(id:String) {if(state?.requestId==id) retire()}
    fun cancel(choice:MapSourceChoice):Boolean {
        if(state!==choice) return false
        retire();cancelRequest(choice.requestId);return true
    }
    fun receive(requestId:String,uri:Uri?):Boolean {
        if(!current(requestId)) return false
        if(uri==null) {retireRequest(requestId);cancelRequest(requestId);return true}
        if(state!=null) return false
        val choice=MapSourceChoice(requestId,uri);state=choice
        read(choice,false) {check->MapSourceAccess.offer(context,uri,check)};return true
    }
    private fun read(choice:MapSourceChoice,activation:Boolean,action:((()->Unit))->MapSourceOffer) {
        val working=choice.copy(busy=true,problem=null);state=working
        lateinit var worker:Job
        worker=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=withContext(Dispatchers.IO) {
                    val coroutine=currentCoroutineContext();val check={coroutine.ensureActive()}
                    try {Result.success(action(check))} catch(error:java.util.concurrent.CancellationException) {throw error}
                    catch(error:Exception) {Result.failure(error)}
                }
                if(!live(working)) return@launch
                val resume=if(result.isFailure) withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    try {NotebookStorage.get(context).hasRecovery(choice.uri.toString())} catch(error:java.util.concurrent.CancellationException) {throw error}
                    catch(_:Exception) {false}
                } else false
                if(!live(working)) return@launch
                state=result.fold({working.copy(offer=it,busy=false,canResume=false)},
                    {working.copy(busy=false,problem=if(activation) MapSourceChoiceProblem.ACTIVATION else MapSourceChoiceProblem.READ,canResume=resume)})
            } finally {if(job===worker) job=null}
        }
        job=worker;worker.start()
    }
    fun activatePages(choice:MapSourceChoice):Boolean {
        if(!live(choice) || choice.busy || choice.offer?.catalog?.pages!=NativeSourceSnapshot.ReferenceCatalog.Pages.NeedsActivation) return false
        read(choice,true) {check->MapSourceAccess.activatePages(context,requireNotNull(choice.offer),check)};return true
    }
    fun completeActivation(choice:MapSourceChoice):Boolean {
        if(!live(choice) || choice.busy || !choice.canResume) return false
        read(choice,true) {check->MapSourceAccess.completeActivation(context,choice.uri,check)};return true
    }
    fun chooseFile(choice:MapSourceChoice)=choose(choice,null)
    fun choosePage(choice:MapSourceChoice,pageId:String)=choose(choice,pageId)
    private fun choose(choice:MapSourceChoice,pageId:String?):Boolean {
        val offer=choice.offer ?: return false
        if(!live(choice) || choice.busy || pageId!=null &&
            (offer.catalog?.pages as? NativeSourceSnapshot.ReferenceCatalog.Pages.Available)?.ids?.contains(pageId)!=true) return false
        val working=choice.copy(busy=true,problem=null);state=working
        lateinit var worker:Job
        worker=scope.launch(start=CoroutineStart.LAZY) {
            try {
                while(live(working)) {
                    snapshotFlow {inkBusy()}.first {!it}
                    if(!live(working)) return@launch
                    val source=withContext(Dispatchers.IO) {
                        val coroutine=currentCoroutineContext()
                        MapSourceAccess.select(context,offer,pageId) {coroutine.ensureActive()}
                    }
                    if(!live(working)) return@launch
                    // If a new contact began during IO, wait again and re-read, never use old proof.
                    if(inkBusy()) continue
                    if(accept(choice.requestId,source)) state=null
                    else if(live(working)) state=working.copy(busy=false,problem=MapSourceChoiceProblem.CHANGED)
                    return@launch
                }
            } catch(error:java.util.concurrent.CancellationException) {throw error}
            catch(error:Exception) {if(live(working)) state=working.copy(busy=false,problem=MapSourceChoiceProblem.CHANGED)}
            finally {if(job===worker) job=null}
        }
        job=worker;worker.start();return true
    }
}
