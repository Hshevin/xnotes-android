package com.xnotes.mindmap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.ui.Pane
import com.xnotes.core.util.DocumentKind

/** A hint for verifying an existing per-slot working session, never authority to save or reopen a file. */
internal data class MapNativeRestoreHint(val documentId:String,val path:String?,val kind:DocumentKind)

/** Logical slots only: retaining this layout must never retain a native Editor or View. */
internal data class MapSplitLayout(
    val mapPane:Pane,
    val focusedPane:Pane,
    val ratio:Float,
    val sideBySide:Boolean?,
    val reversed:Boolean,
    val nativeHome:Boolean=false,
) {
    val nativePane:Pane get()=if(mapPane==Pane.PRIMARY) Pane.SECONDARY else Pane.PRIMARY
    internal fun normalized()=copy(ratio=ratio.takeIf {it.isFinite()}?.coerceIn(.18f,.82f) ?: .5f)
}

internal sealed interface MapOpenPresentation {
    data object Single:MapOpenPresentation
    data class Split(val layout:MapSplitLayout):MapOpenPresentation
    data object RestoreSplit:MapOpenPresentation
}

/** Configuration-retained presentation, independent of document history and source membership. */
internal class MapSplitPresentation {
    var layout by mutableStateOf<MapSplitLayout?>(null);private set
    var returnLayout by mutableStateOf<MapSplitLayout?>(null);private set
    var generation by mutableStateOf(0L);private set
    var nativeHint by mutableStateOf<MapNativeRestoreHint?>(null);private set

    internal fun present(presentation:MapOpenPresentation) {
        when(presentation) {
            MapOpenPresentation.Single->{layout=null;returnLayout=null;nativeHint=null}
            is MapOpenPresentation.Split->{layout=presentation.layout.normalized();returnLayout=null}
            MapOpenPresentation.RestoreSplit->{layout=returnLayout;returnLayout=null}
        }
        generation++
    }
    internal fun park() {
        layout?.let {returnLayout=it;layout=null;generation++}
    }
    /** The default Canvas route may adopt into either physical native slot and closes old notes. */
    internal fun prepareCanvasReturn(canvasPane:Pane) {
        val old=layout ?: returnLayout ?: return
        val mapWasFirst=(old.mapPane==Pane.PRIMARY)!=old.reversed
        val mapPane=if(canvasPane==Pane.PRIMARY) Pane.SECONDARY else Pane.PRIMARY
        returnLayout=old.copy(mapPane=mapPane,focusedPane=mapPane,nativeHome=true,
            reversed=(mapPane==Pane.PRIMARY)!=mapWasFirst)
        layout=null;nativeHint=null;generation++
    }
    internal fun clear() {if(layout!=null || returnLayout!=null || nativeHint!=null) {layout=null;returnLayout=null;nativeHint=null;generation++}}
    fun rememberNativeHint(hint:MapNativeRestoreHint?):Boolean {
        if(layout==null && returnLayout==null) return false
        nativeHint=hint;return true
    }
    fun nativeRestoreFailed():Boolean {
        if(layout==null && returnLayout==null) return false
        layout=layout?.copy(nativeHome=true);returnLayout=returnLayout?.copy(nativeHome=true)
        generation++;return true
    }
    fun updateLayout(transform:(MapSplitLayout)->MapSplitLayout):Boolean {
        val current=layout ?: return false
        val next=transform(current).normalized()
        if(next.mapPane!=current.mapPane) return false
        if(next!=current) {layout=next;generation++}
        return true
    }
}
