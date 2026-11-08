package com.xnotes.mindmap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Successful callbacks advance synchronously; each row patches only its own current source. */
internal class SourceListEditor(private val session:MapSession,initial:SourceEditSnapshot) {
    private var expected=initial
    private var active=true
    var conflict by mutableStateOf(false);private set
    fun close() {active=false}
    fun sync(displayed:SourceEditSnapshot) {
        if(!active) return
        val actual=session.sourceEditSnapshot(expected.node.id) ?: return close()
        if(actual.epoch!=expected.epoch || actual.documentId!=expected.documentId || displayed.node.id!=expected.node.id) return close()
        // Do not rewind an already successful callback when an older composition finishes.
        if(displayed.epoch==actual.epoch && displayed.documentId==actual.documentId && displayed.node.sources==actual.node.sources &&
            displayed.node.primarySource==actual.node.primarySource) expected=actual
    }
    private fun apply(next:MapNode):Boolean {
        if(!active) return false
        if(!session.editSources(expected,next)) {conflict=true;return false}
        session.sourceEditSnapshot(expected.node.id)?.let {expected=it} ?: close()
        conflict=false;return true
    }
    fun note(id:String,value:String):Boolean {
        val node=expected.node
        if(node.sources.none {it.id==id}) return false
        return apply(node.copy(sources=node.sources.map {if(it.id==id) it.copy(note=value) else it}))
    }
    fun primary(id:String):Boolean {
        val node=expected.node
        if(node.sources.none {it.id==id}) return false
        return apply(node.copy(primarySource=id))
    }
    fun move(id:String,delta:Int):Boolean {
        val node=expected.node;val index=node.sources.indexOfFirst {it.id==id}
        if(index<0 || !active) return false
        val target=index+delta
        if(target !in node.sources.indices) return true
        val sources=node.sources.toMutableList();java.util.Collections.swap(sources,index,target)
        return apply(node.copy(sources=sources.toList()))
    }
    fun remove(id:String):Boolean {
        val node=expected.node
        if(node.sources.none {it.id==id}) return false
        return apply(node.copy(sources=node.sources.filterNot {it.id==id},primarySource=node.primarySource.takeUnless {it==id}))
    }
}
