@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.iosapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.xnotes.core.model.*
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.platform.ios.CgSurfaceFactory
import com.xnotes.platform.ios.CgTextMeasurer
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation

internal fun UIImage.bitmap(): ImageBitmap {
    val data = requireNotNull(UIImagePNGRepresentation(this))
    val bytes = requireNotNull(data.bytes).reinterpret<ByteVar>().readBytes(data.length.toInt())
    return org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

@Composable
internal fun PageThumb(images: PageImages, index: Int, style: TextStyle) {
    val result by produceState<Result<ImageBitmap>?>(null, images, index) { value = runCatching { images.page(index, 128).bitmap() } }
    Thumb(result, style)
}

private val thumbnailWorkers = Semaphore(2)

@Composable
internal fun LayerThumb(editor: InkEditor, id: String, style: TextStyle) {
    val page = editor.panels.page
    val result by produceState<Result<ImageBitmap>?>(null, page, id, editor.revision) {
        value = null
        delay(120)
        thumbnailWorkers.withPermit {
            editor.attachSurface()
            try {
                val model = editor.session.canvas
                val members = (page?.items ?: checkNotNull(model).items).filter { (it.layerId ?: DrawingLayers.DEFAULT) == id }.deepCopyAll(CgTextMeasurer)
                val state = model?.map?.state
                val folds = model?.map?.collapsed.orEmpty().toSet()
                val ids = model?.layers?.entries.orEmpty().map { it.id }
                value = withContext(Dispatchers.Default) {
                    val mapItems = if (state != null && state.nodes.isNotEmpty()) PanelMapScene(false).update(state, folds, ids, false).filter { it.layerId == id } else emptyList()
                    val items = members + mapItems
                    val frame = requireNotNull(LayerPreview(items).frameFor(id, LayerPreviewMode.CONTENT))
                    val surface = CgSurfaceFactory.create(128, 128)
                    try {
                        surface.fill(Rgba(0, 0, 0, 0))
                        val renderer = surface.renderer(); val scale = 120.0 / maxOf(frame.w, frame.h)
                        renderer.translate((128 - frame.w * scale) / 2, (128 - frame.h * scale) / 2)
                        renderer.scale(scale, scale); renderer.translate(-frame.x, -frame.y)
                        for (item in items) { ensureActive(); item.paint(renderer) }
                        Result.success(surface.image().bitmap())
                    } finally { surface.recycle() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { value = Result.failure(failure) }
            finally { editor.releaseSurface() }
        }
    }
    Thumb(result, style)
}

@Composable
private fun Thumb(result: Result<ImageBitmap>?, style: TextStyle) {
    Box(Modifier.size(Space.thumbnail).background(Color.LightGray).border(Dp.Hairline, Color.Gray), contentAlignment = Alignment.Center) {
        result?.getOrNull()?.let { Image(it, null, Modifier.fillMaxSize()) }
        if (result?.isFailure == true) BasicText(text("preview_failed"), style = style.copy(color = Color.Black))
    }
}
