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
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stored-file helpers keep explorer actions out of the notebook/PDF bundle codecs. */
object MapFiles {
    internal fun target(context:Context,uri:Uri,checkActive:()->Unit={}):MapTarget=object:MapTarget {
        override fun read():ByteArray {
            checkActive()
            return (context.contentResolver.openInputStream(uri) ?: throw IOException("Unreadable mind map")).use {input->
                MapCodec.readBytes(object:FilterInputStream(input) {
                    override fun read():Int {checkActive();return super.read()}
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {checkActive();return super.read(bytes,offset,length)}
                }).also {checkActive()}
            }
        }
        override fun write(bytes:ByteArray) {
            checkActive()
            (context.contentResolver.openOutputStream(uri,"wt") ?: throw IOException("Unwritable mind map")).use {out->
                var offset=0
                while(offset<bytes.size) {checkActive();val count=minOf(8192,bytes.size-offset);out.write(bytes,offset,count);offset+=count}
            }
            checkActive()
        }
    }
    /** Also discovers the raw URI journal written by older versions; never guesses unknown aliases. */
    internal fun store(context:Context,uri:Uri,target:MapTarget=target(context,uri),checkActive:()->Unit={},knownAliases:Set<String> = emptySet()):MapStore {
        checkActive()
        val key=MapSaves.key(uri)
        val aliases=linkedSetOf(uri.toString(),key)
        knownAliases.filterTo(aliases) {alias->runCatching {MapSaves.key(Uri.parse(alias))==key}.getOrDefault(false)}
        runCatching {android.provider.DocumentsContract.buildDocumentUri(requireNotNull(uri.authority),
            android.provider.DocumentsContract.getDocumentId(uri)).toString()}.getOrNull()?.let(aliases::add)
        if(uri.scheme=="content") {
            val registry=com.xnotes.storage.NotebookStorage.get(context).registry
            registry.uriAtLocation(uri.toString())?.takeIf {MapSaves.key(Uri.parse(it))==key}?.let(aliases::add)
        }
        checkActive()
        return MapStore(File(context.noBackupFilesDir,"mind-map-recovery"),key,target,aliases)
    }
    internal data class CreationAttempt(val retained:Boolean,val cause:Throwable?=null)
    internal suspend fun createAttempt(context:Context,uri:Uri,doc:MindMapDocument,providedTarget:MapTarget?=null,checkActive:()->Unit={}):CreationAttempt =
        withContext(Dispatchers.IO) {
            val coroutine=currentCoroutineContext();val check={coroutine.ensureActive();checkActive()}
            MapSaves.lockFor(uri).withLock {
                check()
                val owned=store(context,uri,providedTarget ?: target(context,uri,check),check)
                try {owned.create(doc,check);CreationAttempt(false)}
                catch(error:java.util.concurrent.CancellationException) {throw error}
                catch(error:Exception) {CreationAttempt(owned.journalReady,error)}
            }
        }
    internal suspend fun create(context:Context,uri:Uri,doc:MindMapDocument,providedTarget:MapTarget?=null,checkActive:()->Unit={}) {
        createAttempt(context,uri,doc,providedTarget,checkActive).cause?.let {throw it}
    }
    /** Checked independent JSON copy for explorer actions. Neither the source nor a live model is adopted. */
    suspend fun copyToDestination(context:Context,source:String,destination:Uri,checkActive:()->Unit={}) = withContext(Dispatchers.IO) {
        val coroutine=currentCoroutineContext();val check={coroutine.ensureActive();checkActive()}
        val sourceUri=Uri.parse(source)
        check()
        if(MapSaves.key(sourceUri)==MapSaves.key(destination)) throw MapConflict()
        val copied=MapSaves.lockFor(sourceUri).withLock {
            check();MapCodec.decode(target(context,sourceUri,check).read()).also {check()}.copy(id=UUID.randomUUID().toString())
        }
        create(context,destination,copied,checkActive=check)
    }
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
