package com.xnotes.mindmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import android.net.Uri
import java.io.OutputStream
import java.util.UUID

/** Stored-file helpers keep explorer actions out of the notebook/PDF bundle codecs. */
object MapFiles {
    fun read(context: Context, uri: String): MindMapDocument = checkNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use(MapCodec::read)
    fun assignCopy(context: Context, uri: Uri) {
        val copied = read(context, uri.toString()).copy(id = UUID.randomUUID().toString())
        val bytes = MapCodec.encode(copied)
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
        check(read(context, uri.toString()) == copied)
    }
    fun thumbnail(context: Context, uri: String, width: Int, height: Int): Bitmap {
        val doc = read(context, uri)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { render(context,Canvas(it), doc, width, height) }
    }
    fun exportPdf(context: Context, uri: String, out: OutputStream, cancelled: () -> Boolean) {
        val doc = read(context, uri)
        if (cancelled()) throw com.xnotes.format.DocumentCodec.WriteCancelled()
        PDFBoxResourceLoader.init(context.applicationContext)
        // First-version PDF is a bounded, flattened reading snapshot. The editable backup is .xmap.
        val bitmap = Bitmap.createBitmap(2382, 1684, Bitmap.Config.ARGB_8888)
        try {
            render(context,Canvas(bitmap), doc, bitmap.width, bitmap.height)
            PDDocument().use { pdf ->
                val page = PDPage(PDRectangle(1191f, 842f))
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.drawImage(LosslessFactory.createFromImage(pdf, bitmap), 0f, 0f, 1191f, 842f)
                }
                if (cancelled()) throw com.xnotes.format.DocumentCodec.WriteCancelled()
                val sink = java.io.BufferedOutputStream(out, 1 shl 16)
                pdf.save(sink)
                sink.flush()
            }
        } finally { bitmap.recycle() }
    }

    private fun render(context:Context,canvas: Canvas, doc: MindMapDocument, width: Int, height: Int) {
        canvas.drawColor(Color.WHITE)
        val painter=MapPainter().apply {collapsedLabel={context.getString(com.xnotes.R.string.graph_hidden_nodes_count,it)}}
        val b = painter.drawingBounds(doc)
        val scale = minOf(width / b.w, height / b.h).toFloat()
        canvas.save()
        canvas.translate(width / 2f - b.centerX.toFloat() * scale, height / 2f - b.centerY.toFloat() * scale)
        canvas.scale(scale, scale)
        painter.draw(canvas, doc)
        canvas.restore()
    }
}
