package com.xnotes.mindmap

import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.ui.theme.*

/** Viewing mode only. Each content gesture commits directly to the same document history. */
@Composable
internal fun MapInkEditor(session:MapSession,node:MapNode,close:()->Unit) {
  CompositionLocalProvider(LocalInkBusy provides session.inkContact) {
    Surface(Modifier.fillMaxSize(),color=LocalPalette.current.panel.toComposeColor()) {
      Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                MapButton(R.string.map_close,onClick=close)
                Text(node.text,fontSize=TextSize.title,modifier=Modifier.padding(Space.s12))
                MapButton(R.string.undo,enabled=session.canUndo){session.undo()}
                MapButton(R.string.redo,enabled=session.canRedo){session.redo()}
                MapButton(R.string.ink_fit_content){session.operate {it.update(node.id) {n->n.fitInkContent()}}}
            }
            MapInkTools(session)
            AndroidView(factory={InkPadView(it).apply {isFocusableInTouchMode=true;requestFocus()}},modifier=Modifier.fillMaxWidth().weight(1f),update={v->
                v.nodeWidth=node.width;v.nodeHeight=node.height;v.ink=node.ink
                v.mode=session.inkMode;v.penWidth=session.inkWidth
                v.onContact={session.inkContact=it};v.onLimit=session::reportInkLimit
                v.onCommitChecked={old,next->session.commitInk(node.id,old,next).also { accepted ->
                    if(accepted) session.document?.nodes?.firstOrNull {it.id==node.id}?.let {v.ink=it.ink}
                }}
            })
            Text(stringResource(R.string.ink_live_hint),fontSize=TextSize.caption,modifier=Modifier.padding(Space.s8))
        }
        session.problemText()?.let { message ->
            Text(stringResource(message),fontSize=TextSize.body,modifier=Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(LocalPalette.current.panel.toComposeColor()).padding(Space.s12))
        }
      }
    }
  }
}
@Composable
internal fun MapInkTools(session:MapSession) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        MapButton(R.string.map_pen,selected=session.inkMode==InkMode.PEN){session.inkMode=InkMode.PEN}
        MapButton(R.string.map_eraser,selected=session.inkMode==InkMode.ERASER){session.inkMode=InkMode.ERASER}
        MapButton(R.string.map_lasso,selected=session.inkMode==InkMode.LASSO){session.inkMode=InkMode.LASSO}
        Text(stringResource(R.string.map_pen_width),fontSize=TextSize.body,modifier=Modifier.padding(Space.s12))
        Slider(value=session.inkWidth.toFloat(),onValueChange={session.inkWidth=it.toDouble()},valueRange=1f..8f,
            enabled=!session.inkContact,modifier=Modifier.width(MindMapPenSliderWidth).heightIn(min=MinTouchTarget))
    }
}

/** Adjust paper around raw ink, translating its local origin while leaving world-space ink fixed. */
internal fun MapNode.fitInkContent():MapNode {
    if(ink.isEmpty()) return this
    val left=minOf(0.0,ink.minOf {s->s.points.minOf {it.x}-s.width/2-8})
    val top=minOf(0.0,ink.minOf {s->s.points.minOf {it.y}-s.width/2-8})
    val right=maxOf(width,ink.maxOf {s->s.points.maxOf {it.x}+s.width/2+8})
    val bottom=maxOf(height,ink.maxOf {s->s.points.maxOf {it.y}+s.width/2+8})
    require(right-left<=4096 && bottom-top<=4096)
    return copy(x=x+left,y=y+top,width=right-left,height=bottom-top,
        ink=if(left==0.0 && top==0.0) ink else ink.map {s->s.copy(points=s.points.map {it.copy(x=it.x-left,y=it.y-top)})})
}

/** Shared error selection; overlays must not resize the paper during an active gesture. */
internal fun MapSession.problemText():Int? = when {
    limitError -> R.string.map_limit
    conflict -> R.string.map_conflict
    saveFailed -> R.string.map_recovery_notice
    sourceFailed -> R.string.graph_source_error
    recovered -> R.string.map_recovered
    copySaved -> R.string.map_copy_saved
    else -> null
}
