package com.xnotes.mindmap

import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import kotlin.math.hypot

/** One native surface for the graph; no nested AndroidViews or wet-ink Surface reattachment. */
class MindMapView(context: Context) : View(context) {
    var document = MindMapDocument()
        set(value) { if (field != value) {
            if(connectionFrom!=null && (value.id!=connectionDocumentId || value.visible().none {it.id==connectionFrom})) cancelConnection()
            val old=field.nodes.firstOrNull {it.id==inkOwner}
            val new=value.nodes.firstOrNull {it.id==inkOwner}
            if(inkInput.active && (old==null || new==null || old.bounds!=new.bounds || old.kind!=new.kind || old.ink!=new.ink)) inkInput.cancel()
            field=value
            if(new!=null) inkInput.ink=new.ink
            painter.retainInk(value.nodes.flatMap {it.ink});invalidate()
        } }
    var selected: String? = null
        set(value) { field = value; invalidate() }
    var dark = false
        set(value) { field = value; invalidate() }
    var onViewportChanged:()->Unit={}
    /** Last host-local report from the native text field, also used by platform host regressions. */
    internal var editingCaretBounds:com.xnotes.core.geometry.Rect?=null; private set
    var editingText:String?=null
        set(value) {if(field!=value) {field=value;editingCaretBounds=null;invalidate()}}
    var onSelect: (String?) -> Unit = {}
    var onSources: (String) -> Unit = {}
    var onEdit: (String) -> Unit = {}
    var onChange: (MindMapDocument) -> Unit = {}
    val inkInput=InkGestureController()
    var onInk:((String,List<MapInk>,List<MapInk>)->Boolean)?=null
    var onInkContact:(Boolean)->Unit={}
    var onInkLimit:()->Unit={}
    private var inkOwner:String?=null
    private var suppressRemainingTouch=false
    private var inputReady=false
    private var pressedStream=false
    private var connectionDrain=false
    private var connectionEnterHeld=false
    var connectionFrom:String?=null;private set
    var connectionTarget:String?=null;private set
    internal var connectionPreview:Pt?=null;private set
    var onConnectionModeChanged:(String?)->Unit={}
    var onConnectionTarget:(String,String)->Unit={_,_->}
    var onConnectionBlank:((String,Pt)->Unit)?=null
    private var connectionDocumentId:String?=null
    private var connectionPointer=-1
    private var connectionPen=false
    private var connectionByPort=false
    private var connectionPortOrigin:Pt?=null
    private var connectionPortDragged=false
    private val portPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var savedQuickScale:Boolean?=null
    private val painter = MapPainter()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var initialized = false
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var dragging = false
    private var cancelled = false
    private var pointer = -1
    private var dragged: String? = null
    private var before: MindMapDocument? = null
    private var preview: MindMapDocument? = null
    private var dropTarget: String? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if(connectionFrom!=null) return false
            scaleAt(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            hit(e.x, e.y)?.let { onEdit(it.id) }
            cancelled = true; preview = null
            return true
        }
        override fun onLongPress(e: MotionEvent) { if (!dragging) hit(e.x, e.y)?.takeUnless {it.kind==NodeKind.INK}?.let { onEdit(it.id) }; cancelGesture() }
    })
    init {
        inkInput.onCommit={old,next ->
            val id=inkOwner
            val current=document.nodes.firstOrNull {it.id==id}
            if(id==null || current==null || current.ink!=old) false
            else onInk?.invoke(id,old,next) ?: run {onChange(document.update(id) {it.copy(ink=next)});true}
        }
        inkInput.onContact={onInkContact(it);parent?.requestDisallowInterceptTouchEvent(it)}
        inkInput.onLimit={onInkLimit()}
        painter.sourceLabel={ resources.getString(com.xnotes.R.string.graph_sources_count,it) }
        painter.collapsedLabel={resources.getString(com.xnotes.R.string.graph_hidden_nodes_count,it)}
        isFocusable = true;isFocusableInTouchMode=true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES;inputReady=true }
    override fun performClick(): Boolean { super.performClick(); return true }
    private val scale get() = resources.displayMetrics.density * zoom
    private fun point(x: Float, y: Float) = Pt(((x - panX) / scale).toDouble(), ((y - panY) / scale).toDouble())
    private fun hit(x: Float, y: Float, doc: MindMapDocument = document) = doc.visible().lastOrNull { it.bounds.contains(point(x, y)) }
    fun fit() {
        if (width == 0 || height == 0 || inkInput.active || connectionPen) return
        val b = painter.drawingBounds(document)
        val s = minOf(width / b.w, height / b.h).toFloat().coerceAtMost(resources.displayMetrics.density * 1.5f)
        zoom = (s / resources.displayMetrics.density).coerceIn(.05f, 4f)
        panX = width / 2f - b.centerX.toFloat() * scale
        panY = height / 2f - b.centerY.toFloat() * scale
        initialized = true; invalidate();onViewportChanged()
    }
    fun centerOn(id: String) {
        if(inkInput.active || connectionPen) return
        document.nodes.firstOrNull { it.id == id }?.bounds?.let {
            panX = width / 2f - it.centerX.toFloat() * scale
            panY = height / 2f - it.centerY.toFloat() * scale
            invalidate();onViewportChanged()
        }
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if(keyCode==android.view.KeyEvent.KEYCODE_ENTER && connectionEnterHeld) {
            if(event.repeatCount>0) return true
            connectionEnterHeld=false // A new independent press may follow a lost UP.
        }
        if(connectionFrom!=null && keyCode==android.view.KeyEvent.KEYCODE_ESCAPE) {cancelConnection();return true}
        if(connectionPen) return true
        if(!isEnabled || inkInput.active) return false
        if(connectionFrom!=null && keyCode==android.view.KeyEvent.KEYCODE_ENTER) {
            if(!pressedStream) selected?.takeIf {it!=connectionFrom && document.visible().any {n->n.id==it}}?.let {target->
                val from=requireNotNull(connectionFrom);connectionEnterHeld=true;cancelConnection();onConnectionTarget(from,target)
            }
            return true
        }
        val visible = document.visible()
        val at = visible.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                val next = if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) at + 1 else at - 1
                selected = visible[next.coerceIn(0, visible.lastIndex)].id
                onSelect(selected); centerOn(requireNotNull(selected)); return true
            }
            android.view.KeyEvent.KEYCODE_ENTER -> { selected?.let(onEdit); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode:Int,event:android.view.KeyEvent):Boolean {
        if(keyCode==android.view.KeyEvent.KEYCODE_ENTER && connectionEnterHeld) {connectionEnterHeld=false;return true}
        return super.onKeyUp(keyCode,event)
    }
    data class Viewport(val zoom:Float,val panX:Float,val panY:Float)
    fun viewport()=Viewport(zoom,panX,panY)
    fun contentScale()=scale.toDouble()
    fun prepareTextEditing(id:String) {
        cancelConnection()
        inkInput.cancel();cancelGesture();pointer=-1;suppressRemainingTouch=false
        parent?.requestDisallowInterceptTouchEvent(false)
        val cancel=MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0f,0f,0)
        try {gestures.onTouchEvent(cancel);scaleDetector.onTouchEvent(cancel)} finally {cancel.recycle()}
        zoom=maxOf(1f,zoom);centerOn(id)
    }
    fun panText(dx:Float,dy:Float) {
        if(inkInput.active || connectionPen || !dx.isFinite() || !dy.isFinite()) return
        panX+=dx;panY+=dy;invalidate();onViewportChanged()
    }
    fun restoreViewport(saved:Viewport,oldWidth:Int,oldHeight:Int,oldDensity:Float) {
        if(width<=0 || height<=0 || oldDensity<=0 || inkInput.active || connectionPen) return
        zoom=saved.zoom.coerceIn(.05f,4f)
        val oldScale=oldDensity*saved.zoom
        panX=width/2f-(oldWidth/2f-saved.panX)/oldScale*scale
        panY=height/2f-(oldHeight/2f-saved.panY)/oldScale*scale
        invalidate();onViewportChanged()
    }
    fun revealCaret(r:com.xnotes.core.geometry.Rect) {
        if(width<=0 || height<=0 || !listOf(r.x,r.y,r.w,r.h).all {it.isFinite()}) return
        editingCaretBounds=r
        val margin=minOf(16f*resources.displayMetrics.density,width/4f,height/4f).toDouble()
        val dx=when {r.w>width-2*margin || r.left<margin->margin-r.left;r.right>width-margin->width-margin-r.right;else->0.0}
        val dy=when {r.h>height-2*margin || r.top<margin->margin-r.top;r.bottom>height-margin->height-margin-r.bottom;else->0.0}
        if(kotlin.math.abs(dx)>=.5 || kotlin.math.abs(dy)>=.5) panText(dx.toFloat(),dy.toFloat())
    }
    fun screenPoint(p:Pt)=Pt((panX+p.x*scale).toDouble(),(panY+p.y*scale).toDouble())
    fun zoomBy(factor: Float) = scaleAt(factor, width / 2f, height / 2f)
    private fun scaleAt(factor: Float, x: Float, y: Float) {
        if(inkInput.active || connectionPen) return
        val p = point(x, y)
        zoom = (zoom * factor).coerceIn(.05f, 4f)
        panX = x - p.x.toFloat() * scale; panY = y - p.y.toFloat() * scale
        invalidate();onViewportChanged()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { cancelConnection();inkInput.cancel(); if (!initialized) fit();onViewportChanged() }
    override fun setEnabled(enabled:Boolean) {if(inputReady && !enabled) cancelConnection();super.setEnabled(enabled)}
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        canvas.save(); canvas.translate(panX, panY); canvas.scale(scale, scale)
        val owner=document.nodes.firstOrNull {it.id==inkOwner}
        painter.retainInk(document.nodes.flatMap {it.ink})
        val shown=if(inkInput.active && owner!=null) document.update(owner.id) {it.copy(ink=inkInput.displayInk)} else preview ?: document
        painter.draw(canvas, shown, dark, selected, connectionTarget ?: dropTarget,editingText,
            connectionFrom?.let {from->connectionPreview?.let {from to it}})
        if(owner!=null && selected==owner.id) {
            canvas.save();canvas.translate(owner.x.toFloat(),owner.y.toFloat())
            canvas.clipRect(0f,0f,owner.width.toFloat(),owner.height.toFloat())
            inkInput.drawSelection(canvas,if(dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE,scale)
            canvas.restore()
        }
        canvas.restore()
        connectionPort()?.let {bounds->
            val fg=if(dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val bg=if(dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            val node=document.node(requireNotNull(selected));val a=screenPoint(node.bounds.topLeft)
            val b=screenPoint(Pt(node.bounds.right,node.bounds.bottom))
            val cx=bounds.centerX.toFloat();val cy=bounds.centerY.toFloat()
            portPaint.color=fg;portPaint.style=android.graphics.Paint.Style.STROKE
            portPaint.strokeWidth=Space.s2.value*resources.displayMetrics.density
            canvas.drawLine(cx.coerceIn(a.x.toFloat(),b.x.toFloat()),cy.coerceIn(a.y.toFloat(),b.y.toFloat()),cx,cy,portPaint)
            val radius=Space.s8.value*resources.displayMetrics.density
            portPaint.color=bg;portPaint.style=android.graphics.Paint.Style.FILL;canvas.drawCircle(cx,cy,radius,portPaint)
            portPaint.color=fg;portPaint.style=android.graphics.Paint.Style.STROKE;canvas.drawCircle(cx,cy,radius,portPaint)
        }
    }
    /** Screen-space target: never shrink it with zoom or place it over editable node content. */
    internal fun connectionPort():Rect? {
        if(!isEnabled || inkInput.active || editingText!=null || connectionFrom!=null || pointer>=0 || width<=0 || height<=0) return null
        val visible=document.visible();val node=visible.firstOrNull {it.id==selected} ?: return null
        fun screenBounds(n:MapNode):Rect {
            val a=screenPoint(n.bounds.topLeft);val b=screenPoint(Pt(n.bounds.right,n.bounds.bottom))
            return Rect.fromPoints(a,b)
        }
        val box=screenBounds(node)
        if(box.right<0 || box.left>width || box.bottom<0 || box.top>height) return null
        val size=(MinTouchTarget.value*resources.displayMetrics.density).toDouble();val half=size/2
        val candidates=listOf(Pt(box.right+half,box.centerY),Pt(box.left-half,box.centerY),
            Pt(box.centerX,box.bottom+half),Pt(box.centerX,box.top-half))
        val others=visible.filter {it.id!=node.id}.map(::screenBounds)
        return candidates.map {Rect(it.x-half,it.y-half,size,size)}.firstOrNull {r->
            r.left>=0 && r.top>=0 && r.right<=width && r.bottom<=height &&
                others.none {r.left<it.right && r.right>it.left && r.top<it.bottom && r.bottom>it.top}
        }
    }
    fun beginConnection(from:String):Boolean {
        if(!isEnabled || inkInput.active || pressedStream || pointer>=0 || editingText!=null || document.visible().none {it.id==from}) return false
        armConnection(from);return true
    }
    private fun armConnection(from:String) {
        cancelConnection();cancelGesture();cancelDetectors()
        savedQuickScale=scaleDetector.isQuickScaleEnabled;scaleDetector.isQuickScaleEnabled=false
        connectionDocumentId=document.id;connectionFrom=from;suppressRemainingTouch=false
        selected=from;onSelect(from);onConnectionModeChanged(from);requestFocus();invalidate()
    }
    fun cancelConnection() {endConnection(false)}
    private fun cancelDetectors() {
        val cancel=MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0f,0f,0)
        try {gestures.onTouchEvent(cancel);scaleDetector.onTouchEvent(cancel)} finally {cancel.recycle()}
    }
    private fun clearConnectionTouch() {
        connectionPointer=-1;connectionTarget=null;connectionPreview=null
        connectionByPort=false;connectionPortOrigin=null;connectionPortDragged=false
        val wasPen=connectionPen;connectionPen=false
        if(wasPen) onInkContact(false)
        parent?.requestDisallowInterceptTouchEvent(false);invalidate()
    }
    private fun endConnection(keepScale:Boolean) {
        if(connectionFrom==null) return
        clearConnectionTouch();connectionFrom=null;connectionDocumentId=null
        savedQuickScale?.let {scaleDetector.isQuickScaleEnabled=it};savedQuickScale=null
        suppressRemainingTouch=!keepScale
        if(!keepScale) connectionDrain=true
        if(!keepScale) cancelDetectors()
        cancelGesture();pointer=-1;onConnectionModeChanged(null);invalidate()
    }
    private fun isPen(e:MotionEvent,index:Int)=e.getToolType(index) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)
    /** True consumes; false hands a real two-finger stream to the existing viewport path. */
    private fun handleConnection(e:MotionEvent):Boolean {
        fun position(index:Int) {
            connectionPortOrigin?.let {origin->
                if(hypot(e.getX(index)-origin.x,e.getY(index)-origin.y)>slop) connectionPortDragged=true
            }
            connectionPreview=point(e.getX(index),e.getY(index))
            connectionTarget=hit(e.getX(index),e.getY(index))?.id?.takeUnless {it==connectionFrom}
            invalidate()
        }
        fun begin(index:Int) {
            val byPort=connectionByPort
            clearConnectionTouch();cancelDetectors();suppressRemainingTouch=false
            connectionByPort=byPort
            if(byPort) connectionPortOrigin=Pt(e.getX(index).toDouble(),e.getY(index).toDouble())
            connectionPointer=e.getPointerId(index);connectionPen=isPen(e,index)
            if(connectionPen) onInkContact(true) else scaleDetector.onTouchEvent(e)
            parent?.requestDisallowInterceptTouchEvent(true);position(index)
        }
        if(suppressRemainingTouch && e.actionMasked!=MotionEvent.ACTION_DOWN &&
            !(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN && isPen(e,e.actionIndex))) return true
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if(connectionPen) return true // All other pointers are palm contacts.
                if(isPen(e,e.actionIndex)) begin(e.actionIndex)
                else if(connectionPointer>=0 && (0 until e.pointerCount).all {e.getToolType(it)==MotionEvent.TOOL_TYPE_FINGER}) {
                    endConnection(true);return false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index=e.findPointerIndex(connectionPointer)
                if(index>=0) {if(!connectionPen) scaleDetector.onTouchEvent(e);position(index)}
                else {clearConnectionTouch();suppressRemainingTouch=true;connectionDrain=true;cancelDetectors()}
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> {
                if(e.getPointerId(e.actionIndex)!=connectionPointer) return true
                position(e.actionIndex)
                val from=connectionFrom;val canDrop=connectionPortOrigin==null || connectionPortDragged
                val target=connectionTarget?.takeIf {canDrop}
                val x=e.getX(e.actionIndex);val y=e.getY(e.actionIndex)
                val blank=if(canDrop && x>=0 && y>=0 && x<width && y<height && hit(x,y)==null) connectionPreview else null
                val create=onConnectionBlank
                if((e.flags and 0x20)==0 && from!=null && target!=null) {
                    endConnection(false) // Busy is released before opening a relation draft.
                    onConnectionTarget(from,target)
                } else if((e.flags and 0x20)==0 && from!=null && blank!=null && create!=null) {
                    endConnection(false);create(from,blank)
                } else {clearConnectionTouch();suppressRemainingTouch=true;connectionDrain=true;cancelDetectors()}
            }
            MotionEvent.ACTION_CANCEL -> {clearConnectionTouch();suppressRemainingTouch=true;connectionDrain=true;cancelDetectors()}
        }
        return true
    }
    private fun cancelGesture() { cancelled = true; preview = null; dropTarget = null; before = null; dragged = null; invalidate() }
    private fun inkBody(n:MapNode,p:Pt):Boolean = n.kind==NodeKind.INK &&
        p.x in n.x+8..n.x+n.width-8 && p.y in n.y+8..n.y+n.height-(if(n.sources.isEmpty()) 8 else 28)
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {pressedStream=true;connectionDrain=false}
        return try {handleTouch(e)} finally {
            if(e.actionMasked==MotionEvent.ACTION_UP || e.actionMasked==MotionEvent.ACTION_CANCEL) pressedStream=false
        }
    }
    private fun handleTouch(e:MotionEvent):Boolean {
        if(!isEnabled) return true
        // Connection cancellation drains independently of ink's deliberate pen-over-palm priority.
        if(connectionDrain) return true
        if(inkInput.active) {
            inkInput.handle(e,{x,y->point(x,y)},{false});invalidate();return true
        }
        if(e.actionMasked==MotionEvent.ACTION_DOWN) suppressRemainingTouch=false
        if(connectionFrom!=null && handleConnection(e)) return true
        if(e.actionMasked==MotionEvent.ACTION_DOWN && hit(e.x,e.y)==null && connectionPort()?.contains(Pt(e.x.toDouble(),e.y.toDouble()))==true) {
            armConnection(requireNotNull(selected));connectionByPort=true
            return handleConnection(e)
        }
        if(e.actionMasked in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN)) {
            val i=e.actionIndex
            if(e.getToolType(i) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER,MotionEvent.TOOL_TYPE_MOUSE)) {
                val n=hit(e.getX(i),e.getY(i))
                if(n!=null && inkBody(n,point(e.getX(i),e.getY(i)))) {
                    // Only detector-local cancellation; the original MotionEvent is never changed.
                    val cancel=MotionEvent.obtain(e)
                    try {cancel.action=MotionEvent.ACTION_CANCEL;gestures.onTouchEvent(cancel);scaleDetector.onTouchEvent(cancel)} finally {cancel.recycle()}
                    cancelGesture();pointer=-1;suppressRemainingTouch=true
                    if(inkOwner!=n.id) inkInput.clearSelection()
                    inkOwner=n.id
                    selected=n.id;onSelect(n.id);inkInput.ink=n.ink
                    val capturedScale=scale;val px=panX;val py=panY;val nx=n.x;val ny=n.y
                    inkInput.handle(e,{x,y->Pt(((x-px)/capturedScale).toDouble()-nx,((y-py)/capturedScale).toDouble()-ny)},{true})
                    invalidate();return true
                }
            }
        }
        if(suppressRemainingTouch) return true
        if(e.actionMasked==MotionEvent.ACTION_DOWN && e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER) {
            val n=hit(e.x,e.y)
            if(n!=null && inkBody(n,point(e.x,e.y))) {
                scaleDetector.onTouchEvent(e);gestures.onTouchEvent(e)
                cancelGesture();pointer=-1;selected=n.id;onSelect(n.id);return true
            }
        }
        val stylus = (0 until e.pointerCount).any { e.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }
        // A second pointer while the pen owns a drag is a palm, never a pinch or a new drag.
        if (pointer >= 0 && e.findPointerIndex(pointer) >= 0 && e.getToolType(e.findPointerIndex(pointer)) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) {
            if (e.actionMasked in listOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP) && e.getPointerId(e.actionIndex) != pointer) return true
        } else if (!stylus) scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.getToolType(e.actionIndex) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) begin(e, e.actionIndex)
                else cancelGesture()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointer)
                if (i < 0 || cancelled || (scaleDetector.isInProgress && !stylus)) return true
                val x = e.getX(i); val y = e.getY(i)
                if (hypot((x - downX).toDouble(), (y - downY).toDouble()) > slop) dragging = true
                if (dragging) {
                    val id = dragged
                    if (id != null) {
                        val base = before ?: return true
                        preview = base.translate(id, ((x - downX) / scale).toDouble(), ((y - downY) / scale).toDouble())
                        val excluded = base.descendants(id)
                        dropTarget = document.visible().lastOrNull { it.id !in excluded && it.bounds.contains(point(x, y)) }?.id
                    } else { panX += x - lastX; panY += y - lastY }
                    invalidate()
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (pointer < 0 || e.getPointerId(e.actionIndex) != pointer) return true
                if (!cancelled && dragging && (e.flags and 0x20) == 0) preview?.let { moved ->
                    val parent = dropTarget
                    onChange(if (parent != null) moved.reparent(requireNotNull(dragged), parent) else moved)
                }
                cancelGesture(); pointer = -1
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { cancelGesture(); pointer = -1; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    private fun begin(e: MotionEvent, index: Int) {
        parent?.requestDisallowInterceptTouchEvent(true)
        pointer = e.getPointerId(index)
        downX = e.getX(index); downY = e.getY(index); lastX = downX; lastY = downY
        dragging = false; cancelled = false; before = document; preview = null; dropTarget = null
        val node=hit(downX,downY)
        dragged = node?.id
        onSelect(dragged)
        if(node != null && node.sources.isNotEmpty() && point(downX,downY).y >= node.y+node.height-28) {
            cancelled=true;dragged=null;onSources(node.id)
        }
    }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) {
        if(!hasWindowFocus) {cancelConnection();pressedStream=false;inkInput.cancel();cancelGesture();pointer=-1;suppressRemainingTouch=true}
        super.onWindowFocusChanged(hasWindowFocus)
    }
    override fun onDetachedFromWindow() { cancelConnection();pressedStream=false;inkInput.cancel(); cancelGesture(); super.onDetachedFromWindow() }
}
