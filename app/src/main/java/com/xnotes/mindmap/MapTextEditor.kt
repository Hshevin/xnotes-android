package com.xnotes.mindmap

import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.canvas.EditingField
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.ui.TextEditHost
import com.xnotes.ui.TextEditorOverlay
import com.xnotes.ui.theme.monochromePalette
import com.xnotes.ui.theme.toComposeColor

/** Adapts the existing notebook text overlay; typed values are draft data until explicit Done. */
internal class MapTextHost(
    private val session:MapSession,
    private val token:String,
    private val graph:MindMapView,
    dark:Boolean,
):TextEditHost {
    override val overlayPalette=monochromePalette(dark) // selected node keeps its own paper
    override val editingBackground=overlayPalette.panel.toComposeColor()
    override val textBar:com.xnotes.canvas.TextBar?=null
    override val editingValue get()=session.textDraft?.takeIf {it.token==token}?.value
    private var manualPan=false
    private var hostWidth=graph.width
    private var hostHeight=graph.height
    override fun acceptsEditingText(text:String):Boolean {
        if(session.textDraft?.token!=token) return false
        if(text.length>20000) {session.reportInkLimit();return false}
        return true
    }
    override fun updateEditingValue(value:TextFieldValue) {manualPan=false;session.updateTextDraft(token,value)}
    override fun updateEditingText(text:String) {updateEditingValue(TextFieldValue(text))}
    override fun panWhileEditing(dxFinger:Float,dyFinger:Float) {manualPan=true;graph.panText(dxFinger,dyFinger)}
    override fun editingCaretChanged(bounds:Rect) {
        if(session.textDraft?.token!=token) return
        if(hostWidth!=graph.width || hostHeight!=graph.height) {hostWidth=graph.width;hostHeight=graph.height;manualPan=false}
        if(!manualPan) graph.revealCaret(bounds)
    }
    // Graph nodes currently use a fixed body font and do not expose a text-style bar.
    override fun setTextFace(face:FontFace) {}
    override fun setTextPointSize(size:Double) {}
    override fun commitText(text:String?) {
        if(text!=null) updateEditingText(text)
        session.finishTextDraft(token)
    }
}
@Composable
internal fun MapTextEditor(session:MapSession,draft:MapTextDraft,graph:MindMapView,dark:Boolean,revision:Int) {
    val node=session.document?.nodes?.firstOrNull {it.id==draft.nodeId && it.kind==NodeKind.TEXT} ?: return
    val painter=remember {MapPainter()}
    val contentY=node.y+maxOf(12.0,(node.height-painter.textHeight(draft.initialText,node.width))/2)
    val position=graph.screenPoint(Pt(node.x+16,contentY))
    val field=EditingField(position.x,position.y,node.width-32,maxOf(node.height-24,painter.textHeight(draft.value.text,node.width)),18.0,
        graph.contentScale(),FontFace.SANS,if(dark) Rgba(255,255,255,255) else Rgba(0,0,0,255),draft.value.text)
    val host=remember(draft.token,graph,dark) {MapTextHost(session,draft.token,graph,dark)}
    key(draft.token) {TextEditorOverlay(host,field,caretGeometryRevision=revision)}
}
