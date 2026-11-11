package com.xnotes.mindmap

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import com.xnotes.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** All coordinates are pane-local pixels; a narrow pane has one valid position. */
internal fun mapDockOffset(width:Float,panelWidth:Float,right:Boolean):Float =
    if(right) (width-panelWidth).coerceAtLeast(0f) else 0f

internal fun mapDockSnapRight(x:Float,width:Float,panelWidth:Float):Boolean =
    x >= mapDockOffset(width,panelWidth,true)/2f

/** Overlay dock. Only its header drags; content gestures and the underlying canvas keep their size. */
@Composable
internal fun MapDockPanel(
    modifier:Modifier=Modifier,
    enabled:Boolean=true,
    onOutsideTap:()->Unit,
    handle:@Composable ()->Unit,
    content:@Composable ColumnScope.()->Unit,
) {
    val outside by rememberUpdatedState(onOutsideTap)
    var right by rememberSaveable {mutableStateOf(true)}
    var preview by remember {mutableStateOf<Float?>(null)}
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelWidth=minOf(MindMapMenuWidth,maxWidth)
        val density=LocalDensity.current
        val panePx=with(density) {maxWidth.toPx()}
        val panelPx=with(density) {panelWidth.toPx()}
        val maxX=mapDockOffset(panePx,panelPx,true)
        val x=(preview ?: mapDockOffset(panePx,panelPx,right)).coerceIn(0f,maxX)
        Box(Modifier.fillMaxSize().testTag("map-menu-dismiss-area").pointerInput(Unit) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                down.consume()
                var tapped=true
                do {
                    val event=awaitPointerEvent()
                    if(event.changes.size!=1 || event.changes.any {
                        it.id!=down.id || (it.position-down.position).getDistance()>viewConfiguration.touchSlop
                    }) tapped=false
                    event.changes.forEach {it.consume()}
                } while(event.changes.any {it.pressed})
                if(tapped) outside()
            }
        })
        Surface(Modifier.offset {IntOffset(x.roundToInt(),0)}.width(panelWidth).fillMaxHeight().testTag("map-menu-panel"),
            color=LocalPalette.current.panel.toComposeColor()) {
            Box {
              Column {
                Box(Modifier.fillMaxWidth().heightIn(min=MinTouchTarget)
                    .pointerInput(enabled,panePx,panelPx) {
                        if(!enabled) return@pointerInput
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false)
                            val start=mapDockOffset(panePx,panelPx,right)
                            var accumulated=0f
                            var dragging=false
                            var cancelled=false
                            try {
                                while(true) {
                                    val event=awaitPointerEvent()
                                    if(event.changes.count {it.pressed}>1) {cancelled=true;break}
                                    val change=event.changes.firstOrNull {it.id==down.id}
                                    if(change==null || change.isConsumed) {cancelled=true;break}
                                    accumulated+=change.position.x-change.previousPosition.x
                                    if(!dragging && abs(accumulated)>viewConfiguration.touchSlop) dragging=true
                                    if(dragging) {
                                        preview=(start+accumulated).coerceIn(0f,maxX)
                                        change.consume()
                                    }
                                    if(!change.pressed) {
                                        if(dragging && !cancelled) right=mapDockSnapRight(preview ?: start,panePx,panelPx)
                                        break
                                    }
                                }
                            } finally {
                                // Cancellation, resizing, or a second pointer always discards the preview.
                                preview=null
                            }
                        }
                    }) {handle()}
                content()
              }
              VerticalDivider(Modifier.align(if(right) Alignment.CenterStart else Alignment.CenterEnd))
            }
        }
    }
}
