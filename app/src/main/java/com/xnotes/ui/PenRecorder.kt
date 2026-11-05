package com.xnotes.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.pen.*
import com.xnotes.ui.theme.*
import kotlinx.coroutines.delay

/** Scoped to this visible recording surface. Disposal removes the Activity key interceptor. */
@Composable
internal fun PenRecorder(onRecorded: (List<PenTrigger>) -> Unit, onFailed: (PenRecording.Failure) -> Unit) {
    val input = remember { PenRecordingInput() }
    val recorder = input.recording
    val started = remember { SystemClock.uptimeMillis() }
    var changedAt by remember { mutableLongStateOf(started) }
    val recorded by rememberUpdatedState(onRecorded)
    val failed by rememberUpdatedState(onFailed)
    val activity = LocalContext.current.mainActivity()
    val palette = LocalPalette.current
    fun record(block: () -> Boolean): Boolean {
        val before = recorder.revision
        val handled = block()
        if (before != recorder.revision) changedAt = SystemClock.uptimeMillis()
        recorder.failure?.let { failed(it) }
        return handled
    }
    DisposableEffect(activity) {
        val intercept: (KeyEvent) -> Boolean = { event -> record { input.key(event) } }
        activity?.penKeyRecorder = intercept
        activity?.cancelPenRecording = { recorder.cancel(); failed(PenRecording.Failure.CANCELED) }
        onDispose {
            recorder.cancel()
            if (activity?.penKeyRecorder === intercept) {
                activity.penKeyRecorder = null
                activity.cancelPenRecording = null
            }
        }
    }
    LaunchedEffect(recorder) {
        while (true) {
            delay(100)
            val now = SystemClock.uptimeMillis()
            val result = recorder.finish()
            if (result != null && now - changedAt >= 250) { recorded(result); break }
            if (now - started >= 15000) { recorder.cancel(); failed(PenRecording.Failure.CANCELED); break }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Text(stringResource(R.string.pen_record_help), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
        val shape = MaterialTheme.shapes.medium
        Box(Modifier.fillMaxWidth().height(MinTouchTarget * 3).clip(shape)
            .background(palette.surfaceHi.toComposeColor()).border(1.dp, palette.border.toComposeColor(), shape),
            contentAlignment = Alignment.Center) {
            // The label sits under the transparent recording view, so it never takes the pen's events.
            Text(stringResource(R.string.pen_record_area), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                object : View(context) {
                    override fun onTouchEvent(event: MotionEvent) = record { input.motion(event) }
                    override fun onHoverEvent(event: MotionEvent) = record { input.motion(event) }
                    override fun onGenericMotionEvent(event: MotionEvent) = record { input.motion(event) }
                }.apply { contentDescription = context.getString(R.string.pen_record_area) }
            })
        }
    }
}

private fun Context.mainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.mainActivity()
    else -> null
}
