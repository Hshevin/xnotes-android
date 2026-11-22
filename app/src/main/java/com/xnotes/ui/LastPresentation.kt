package com.xnotes.ui

import com.xnotes.core.model.NativeRestoreHint
/** One pane's complete persisted note view; a null [zoomLocked] is an older record that never stored the lock. */
internal data class ColdNativeView(val position:Editor.ReadingPosition,val fitWidth:Boolean,val zoomLocked:Boolean?=null)
internal data class ColdCanvasView(val centerX:Double,val centerY:Double,val zoom:Double)
internal data class ColdNativeLayout(val focusedPane:Pane,val ratio:Float=.5f,
                                    val sideBySide:Boolean?=null,val reversed:Boolean=false)
internal sealed interface ColdNativeSlot {
    val pane:Pane
    data class Note(override val pane:Pane,val hint:NativeRestoreHint,val view:ColdNativeView?=null,val reopenFromFile:Boolean=false):ColdNativeSlot
    data class Canvas(override val pane:Pane,val hint:NativeRestoreHint,val view:ColdCanvasView?=null,val reopenFromFile:Boolean=false):ColdNativeSlot
    data class Home(override val pane:Pane):ColdNativeSlot
    data class Shared(override val pane:Pane,val ownerPane:Pane,val view:ColdNativeView?=null):ColdNativeSlot
}
internal sealed interface LastPresentation {
    data object Home:LastPresentation
    class Native(slots:List<ColdNativeSlot>,val layout:ColdNativeLayout):LastPresentation {
        val slots:List<ColdNativeSlot> = slots.toList()
        fun copy(slots:List<ColdNativeSlot> = this.slots,layout:ColdNativeLayout = this.layout)=Native(slots,layout)
        override fun equals(other:Any?)=other is Native && slots==other.slots && layout==other.layout
        override fun hashCode()=31*slots.hashCode()+layout.hashCode()
        override fun toString()="Native(slots=$slots, layout=$layout)"
    }
}
internal sealed interface LastPresentationRead {
    data object Absent:LastPresentationRead
    data object Invalid:LastPresentationRead
    data class Ready(val value:LastPresentation):LastPresentationRead
}
internal enum class LastPresentationPrepare {Ready,PreservedInvalid,Changed,Failed}
internal enum class LastPresentationWrite {Written,Changed,PreservedInvalid,Failed}
