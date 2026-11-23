package com.xnotes.iosapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import kotlinx.coroutines.delay

@Composable
internal fun <T : Any> PanelList(rows: List<T>, keyOf: (T) -> Any, current: T, enabled: Boolean, style: TextStyle,
                                 label: (T) -> String, select: (T) -> Unit, move: (T, T) -> Unit, thumbnail: @Composable (T) -> Unit) {
    val state = rememberLazyListState()
    var moving by remember { mutableStateOf<Int?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    fun at(y: Float) = state.layoutInfo.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - y) }?.index
    val target = if (moving != null) at(fingerY) else null
    val drop by rememberUpdatedState(move)
    LaunchedEffect(moving) {
        if (moving != null) while (true) {
            val edge = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.div(2f) ?: break
            if (fingerY < edge) state.scrollBy(-edge / 3)
            else if (fingerY > state.layoutInfo.viewportEndOffset - edge) state.scrollBy(edge / 3)
            delay(32)
        }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().pointerInput(rows, enabled) {
        if (!enabled) return@pointerInput
        try {
            detectDragGesturesAfterLongPress(onDragStart = { point -> fingerY = point.y; moving = at(fingerY) },
                onDragCancel = { moving = null }, onDragEnd = {
                    val from = moving; val to = at(fingerY); moving = null
                    if (from != null && to != null && from != to) drop(rows[from], rows[to])
                }) { change, _ -> change.consume(); fingerY = change.position.y }
        } finally { moving = null }
    }) {
        itemsIndexed(rows, key = { _, row -> keyOf(row) }) { index, row ->
            val chosen = row == current
            val inverse = if (style.color == Color.Black) Color.White else Color.Black
            Column {
                if (target == index && moving != target) Spacer(Modifier.fillMaxWidth().height(Space.s4).background(style.color))
                Row(Modifier.fillMaxWidth().background(if (chosen) style.color else inverse)
                    .semantics { selected = chosen }.clickable(enabled = enabled, role = Role.Button) { select(row) }
                    .heightIn(min = MinTouchTarget).padding(Space.s4), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                    thumbnail(row)
                    BasicText(label(row), maxLines = 2, overflow = TextOverflow.Ellipsis, style = style.copy(color = if (chosen) inverse else style.color))
                }
            }
        }
    }
}
