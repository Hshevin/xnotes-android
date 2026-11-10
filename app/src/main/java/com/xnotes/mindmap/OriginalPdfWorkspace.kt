package com.xnotes.mindmap

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.canvas.CanvasState
import com.xnotes.storage.OriginalPdfAccess
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Retained read-only data and resource protection. No Entry owns a View, Activity or native Editor. */
@MainThread
internal class OriginalPdfWorkspace(context:Context,private val scope:CoroutineScope,
    internal val access:OriginalPdfAccess=OriginalPdfAccess(context.applicationContext)) {
    internal sealed interface Return {
        data class Map(val entry:MapWorkspace.Entry,val bookmark:MapSourceReturnBookmark,
            val nativeIdentity:com.xnotes.ui.NativePresentationIdentity?=null):Return
        data object Library:Return
    }
    internal class Entry internal constructor(internal val read:OriginalPdfAccess.Read,val returnTo:Return) {
        val target get()=read.target
        val title get()=read.observation.record.title
        val pageCount get()=read.observation.record.pageCount
        var pageIndex by mutableStateOf(read.pageIndex);private set
        internal var camera:CanvasState.ViewSnapshot?=null
        internal var cameraPage=read.pageIndex
        @Volatile internal var alive=true;private set
        private val readers=java.lang.Object()
        private var readerCount=0
        private var readerEpoch=0L
        private var releasing:Job?=null
        fun current()=alive && read.current()
        fun movePage(delta:Int):Boolean {
            if(!current() || pageIndex+delta !in 0 until pageCount) return false
            pageIndex+=delta;camera=null;cameraPage=pageIndex;return true
        }
        /** A detached host drains its own accepted jobs before relinquishing this read protection. */
        internal class Reader internal constructor(private val valid:()->Boolean,private val release:()->Unit):AutoCloseable {
            private val closed=AtomicBoolean(false)
            fun current()=!closed.get() && valid()
            override fun close() {if(closed.compareAndSet(false,true)) release()}
        }
        internal fun retainReader():Reader?=synchronized(readers) {
            if(!current()) return@synchronized null
            readerCount++;val token=++readerEpoch
            Reader({synchronized(readers) {alive && readerEpoch==token} && read.current()},
                {synchronized(readers) {readerCount--;readers.notifyAll()}})
        }
        /** Retire callbacks at the host boundary, before a replacement reader has even been retained. */
        internal fun invalidateReaderHost() {synchronized(readers) {readerEpoch++}}
        internal fun saveCamera(reader:Reader,index:Int,view:CanvasState.ViewSnapshot) {
            if(reader.current() && pageIndex==index) {camera=view;cameraPage=index}
        }
        internal fun retire():Job {
            releasing?.let {return it}
            synchronized(readers) {alive=false}
            return cleanup.launch {
                var interrupted=false
                try {
                    synchronized(readers) {while(readerCount>0) try {readers.wait()} catch(_:InterruptedException) {interrupted=true}}
                    read.closeAndAwait()
                } finally {if(interrupted) Thread.currentThread().interrupt()}
            }.also {releasing=it}
        }
    }
    private var active=true
    private var host:Any?=null
    var current by mutableStateOf<Entry?>(null);private set
    var visible by mutableStateOf(false);private set
    var resumeRequested by mutableStateOf(false);private set
    fun isHostCurrent(binding:Any)=active && host===binding
    fun current(binding:Any,entry:Entry)=isHostCurrent(binding) && current===entry && entry.current()
    fun bindHost(binding:Any) {
        if(host===binding) return
        current?.invalidateReaderHost()
        if(visible) resumeRequested=true
        visible=false;host=binding
    }
    fun unbindHost(binding:Any):Boolean {
        if(host!==binding) return false
        current?.invalidateReaderHost()
        if(visible) resumeRequested=true
        visible=false;host=null;return true
    }
    internal fun admit(binding:Any,read:OriginalPdfAccess.Read,returnTo:Return,beforeAdopt:()->Boolean={true}):Entry? {
        if(!isHostCurrent(binding) || visible || !read.current()) return null
        if(!beforeAdopt()) return null
        val previous=current
        val next=Entry(read,returnTo)
        current=next;visible=true;resumeRequested=false
        previous?.retire()
        return next
    }
    internal fun show(binding:Any,entry:Entry,beforeAdopt:()->Boolean={true}):Boolean {
        if(!current(binding,entry)) return false
        if(!beforeAdopt()) return false
        visible=true;resumeRequested=false;return true
    }
    internal fun close(binding:Any,entry:Entry,beforeAdopt:()->Boolean={true}):Boolean {
        if(!isHostCurrent(binding) || current!==entry) return false
        if(!beforeAdopt()) return false
        current=null;visible=false;resumeRequested=false;entry.retire();return true
    }
    fun dispose():Job {
        active=false;host=null;visible=false;resumeRequested=false
        val entry=current;current=null
        return entry?.retire() ?: cleanup.launch {}
    }
    private companion object {val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)}
}
