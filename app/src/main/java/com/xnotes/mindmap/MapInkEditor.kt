package com.xnotes.mindmap

import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.settings.ToolbarPosition
import com.xnotes.ui.Label
import com.xnotes.ui.DropdownMenu
import com.xnotes.ui.LocalEmbeddedToolbar
import com.xnotes.ui.LocalToolbarFocused
import com.xnotes.ui.LocalToolbarLook
import com.xnotes.ui.PopupTitle
import com.xnotes.ui.Separator
import com.xnotes.ui.SliderRow
import com.xnotes.ui.ToolbarFrame
import com.xnotes.ui.ToolbarIcon
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.*

/** Viewing mode only. Each content gesture commits directly to the same document history. */
@Composable
internal fun MapInkEditor(session:MapSession,node:MapNode,close:()->Unit) {
  CompositionLocalProvider(LocalInkBusy provides session.inkContact) {
    val takeFocus=LocalMapActionFocus.current
    Surface(Modifier.fillMaxSize(),color=LocalPalette.current.panel.toComposeColor()) {
      Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // The enlarged editor covers the structure rows; its own docked bar keeps Back always visible.
            MapInkToolbar(session,node,floating=false,leading={
                ToolbarIcon(XnotesIcons.prev,stringResource(R.string.map_close)) {if(!session.inkContact && takeFocus()) close()}
                Label(node.text,Modifier.widthIn(max=MindMapInkTitleWidth))
            })
            HorizontalDivider()
            // Compose does not clip an AndroidView's canvas to its bounds; without it the pad's
            // background paints over the bar above.
            AndroidView(factory={InkPadView(it).apply {isFocusableInTouchMode=true;requestFocus()}},modifier=Modifier.fillMaxWidth().weight(1f).clipToBounds(),update={v->
                v.nodeWidth=node.width;v.nodeHeight=node.height;v.ink=node.ink
                v.mode=session.inkMode;v.penWidth=session.inkWidth
                v.onContact={session.inkContact=it};v.onLimit=session::reportInkLimit
                v.onCommitChecked={old,next->session.commitInk(node.id,old,next).also { accepted ->
                    if(accepted) session.document?.nodes?.firstOrNull {it.id==node.id}?.let {v.ink=it.ink}
                }}
            })
            Text(stringResource(R.string.ink_live_hint),fontSize=TextSize.caption,maxLines=2,modifier=Modifier.padding(Space.s8))
        }
        session.problemText()?.let { message ->
            Text(stringResource(message),fontSize=TextSize.body,modifier=Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(LocalPalette.current.panel.toComposeColor()).padding(Space.s12))
        }
      }
    }
  }
}

/**
 * The ordinary note toolbar's frame and buttons, driving only what a map ink node supports:
 * pen, stroke eraser and lasso, the pen width, and the shared map history. Tools never change
 * while a stroke is in contact ([LocalInkBusy]); re-tapping the armed pen opens its width, as on a note.
 */
@Composable
internal fun MapInkToolbar(session:MapSession,node:MapNode,floating:Boolean,leading:(@Composable ()->Unit)?=null) {
    val look=LocalToolbarLook.current
    // The map pane owns this bar and always lays it across its own area: docked along the top of the
    // enlarged editor, or as a pill floating along the bottom of the graph, its tips opening inward.
    CompositionLocalProvider(LocalToolbarLook provides look.copy(position=if(floating) ToolbarPosition.BOTTOM else ToolbarPosition.TOP,floating=floating),
        LocalEmbeddedToolbar provides false) {
        val busy=LocalInkBusy.current
        // Stroke contact alone keeps the bar's look steady and is refused in each click instead,
        // so the icons do not flash disabled for every stroke; other busy states disable them.
        val idle=!busy || session.inkContact
        val focused=LocalToolbarFocused.current
        val takeFocus=LocalMapActionFocus.current
        var widthOpen by remember {mutableStateOf(false)}
        // A stroke closes the width popup for good rather than hiding it until pen-up.
        LaunchedEffect(busy) {if(busy) widthOpen=false}
        ToolbarFrame(armed=session.inkMode,leading=leading) {
            @Composable fun tool(mode:InkMode,icon:ImageVector,label:Int) {
                val armed=focused && session.inkMode==mode
                ToolbarIcon(icon,stringResource(label),active=armed,enabled=idle,glideKey=mode,
                    hint=if(armed && mode==InkMode.PEN) stringResource(R.string.tip_tap_for_settings) else null) {
                    // An unfocused split pane first claims focus; a refused claim changes nothing here.
                    if(!session.inkContact && takeFocus()) {
                        if(armed && mode==InkMode.PEN) widthOpen=true else {session.inkMode=mode;widthOpen=false}
                    }
                }
            }
            Box {
                tool(InkMode.PEN,ImageVector.vectorResource(R.drawable.ic_stroke_regular),R.string.map_pen)
                if(widthOpen && !busy) DropdownMenu(expanded=true,onDismissRequest={widthOpen=false}) {
                    Column(Modifier.width(MindMapPenSliderWidth).padding(horizontal=Space.s16,vertical=Space.s8)) {
                        PopupTitle(stringResource(R.string.map_pen))
                        SliderRow(stringResource(R.string.map_pen_width),session.inkWidth.toFloat(),1f..8f,enabled=!busy) {
                            if(!session.inkContact) session.inkWidth=it.toDouble()
                        }
                    }
                }
            }
            tool(InkMode.ERASER,XnotesIcons.eraser,R.string.map_eraser)
            tool(InkMode.LASSO,XnotesIcons.lasso,R.string.map_lasso)
            Separator()
            ToolbarIcon(XnotesIcons.fit,stringResource(R.string.ink_fit_content),enabled=idle) {
                if(!session.inkContact && takeFocus()) session.operate {it.update(node.id) {n->n.fitInkContent()}}
            }
            Separator()
            ToolbarIcon(XnotesIcons.undo,stringResource(R.string.undo),enabled=idle && session.canUndo) {
                if(!session.inkContact && takeFocus()) session.undo()
            }
            ToolbarIcon(XnotesIcons.redo,stringResource(R.string.redo),enabled=idle && session.canRedo) {
                if(!session.inkContact && takeFocus()) session.redo()
            }
        }
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
    textEditConflict -> R.string.map_text_conflict
    limitError -> R.string.map_limit
    conflict -> R.string.map_conflict
    saveFailed -> R.string.map_recovery_notice
    sourceFailed -> R.string.graph_source_error
    recovered -> R.string.map_recovered
    copySaved -> R.string.map_copy_saved
    else -> null
}
