package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import com.xnotes.core.model.LayerPreview
import com.xnotes.core.model.LayerPreviewMode
import com.xnotes.core.model.deepCopyAll
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LayerThumbnailRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Per-open-sidebar cache; only visible rows render, and stale generations cannot publish. */
internal class LayerThumbnailCache {
    val images = android.util.LruCache<String, ImageBitmap>(32)
    val workers = Semaphore(2)
    val measurer = AndroidTextMeasurer()
}

@Composable
internal fun LayerThumbnail(preview: LayerPreview, id: String, mode: LayerPreviewMode, cache: LayerThumbnailCache, modifier: Modifier = Modifier) {
    val frame = remember(preview, id, mode) { preview.frameFor(id, mode) } ?: return
    var failed by remember(preview, id, mode) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(cache.images.get(id), preview, id, mode, cache) {
        cache.images.get(id)?.let { value = it; return@produceState }
        // Coalesce successive pen-up/content notifications; never rasterize in composition.
        delay(150)
        try {
            cache.workers.withPermit {
                // Snapshot mutable geometry on the main thread before the worker reads it.
                val items = preview.itemsFor(id).deepCopyAll(cache.measurer)
                val rendered = withContext(Dispatchers.Default) {
                    LayerThumbnailRenderer.render(items, frame, active = { isActive })?.asImageBitmap()
                }
                if (isActive && rendered != null) {
                    cache.images.put(id, rendered)
                    value = rendered
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val shape = MaterialTheme.shapes.extraSmall
    Box(modifier.clip(shape).border(Dp.Hairline, LocalPalette.current.border.toComposeColor(), shape), contentAlignment = Alignment.Center) {
        // Transparency checkerboard: a faint grey step so it reads as backdrop, not content.
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / 8f
            for (y in 0..7) for (x in 0..7) {
                drawRect(if ((x + y) % 2 == 0) Color.White else CheckerGrey,
                    Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        if (failed) Icon(XnotesIcons.close, stringResource(R.string.layers_preview_failed), tint = Color.Black)
    }
}

private val CheckerGrey = Color(0xFFEBEBEB)
