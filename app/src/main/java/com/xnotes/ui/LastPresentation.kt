package com.xnotes.ui

import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.mindmap.MapPresentationCamera
import com.xnotes.mindmap.MapSplitLayout

/** A description of local recovery, never permission to read or save either original source. */
internal data class ColdMixedPresentation(
    val map:SplitSourceRef,
    val nativeHint:MapNativeRestoreHint,
    val layout:MapSplitLayout,
    val mapCamera:MapPresentationCamera?=null,
    val nativePosition:Editor.ReadingPosition?=null,
)
internal sealed interface LastPresentation {
    data object Home:LastPresentation
    data class Mixed(val value:ColdMixedPresentation):LastPresentation
}
internal sealed interface LastPresentationRead {
    data object Absent:LastPresentationRead
    data object Invalid:LastPresentationRead
    data class Ready(val value:LastPresentation):LastPresentationRead
}
internal enum class LastPresentationPrepare {Ready,PreservedInvalid,Changed,Failed}
internal enum class LastPresentationWrite {Written,Changed,PreservedInvalid,Failed}
