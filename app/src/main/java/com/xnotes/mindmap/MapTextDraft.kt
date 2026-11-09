package com.xnotes.mindmap

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import java.util.UUID

/** Editing identity outlives recomposition/IME resize; no draft keystrokes enter content history. */
data class MapTextDraft(
    val token:String=UUID.randomUUID().toString(),
    val nodeId:String,
    val initialText:String,
    val value:TextFieldValue=TextFieldValue(initialText,TextRange(initialText.length)),
    val viewport:MindMapView.Viewport,
    val viewWidth:Int,
    val viewHeight:Int,
    val previousSelection:String?,
    val viewDensity:Float=1f,
)
data class MapTextRestore(val token:String,val viewport:MindMapView.Viewport,val width:Int,val height:Int,val selection:String?,val density:Float=1f)
