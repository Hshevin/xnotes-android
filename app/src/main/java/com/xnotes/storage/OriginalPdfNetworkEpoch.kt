package com.xnotes.storage

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read-observation retirement only. Never an original-body or native-write permit. */
internal class OriginalPdfNetworkEpoch private constructor() {
    private val gate=Any()
    private var count=0
    private var version=0L
    private val changes=MutableStateFlow(OriginalPdfCatalog.NetworkEpoch(0,false))
    val flow:StateFlow<OriginalPdfCatalog.NetworkEpoch> = changes.asStateFlow()
    fun stamp():()->Boolean {
        val value=changes.value
        check(!value.busy) {"Original metadata mutation in progress"}
        return {changes.value.let {!it.busy && it.version==value.version}}
    }
    fun begin():AutoCloseable {
        synchronized(gate) {
            check(version<Long.MAX_VALUE);version++;count++
            changes.value=OriginalPdfCatalog.NetworkEpoch(version,true)
        }
        var closed=false
        return AutoCloseable {synchronized(gate) {
            if(!closed) {closed=true;check(count>0);count--;changes.value=OriginalPdfCatalog.NetworkEpoch(version,count>0)}
        }}
    }
    companion object {
        private val states=ConcurrentHashMap<String,OriginalPdfNetworkEpoch>()
        fun forDatabase(path:String)=states.computeIfAbsent(File(path).canonicalPath) {OriginalPdfNetworkEpoch()}
    }
}
