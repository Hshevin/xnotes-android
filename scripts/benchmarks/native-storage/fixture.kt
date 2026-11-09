package com.xnotes.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.ContextThemeWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleIdentity
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.ui.Editor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** Opt-in host I/O benchmark. Run only in the ignored, identically instrumented source archives. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeStorageBenchmarkTest {
    private class Provider(private val file:File):ContentProvider() {
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/octet-stream"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor=
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file.name,file.length()))}
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun delete(uri:Uri,s:String?,a:Array<out String>?)=error("Benchmark never deletes provider content")
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
    }
    private fun method(type:Class<*>,name:String)=type.declaredMethods.single {
        it.name.substringBefore('$')==name && !Modifier.isStatic(it.modifiers)
    }.apply {isAccessible=true}
    private fun call(method:Method,target:Any,vararg args:Any?):Any?=try {method.invoke(target,*args)}
        catch(error:InvocationTargetException) {throw error.targetException}
    private fun noEglHandles() {
        fun <T> handle(type:Class<T>):T=ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
        if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=handle(EGLDisplay::class.java)
        if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=handle(EGLContext::class.java)
        if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=handle(EGLSurface::class.java)
    }
    private fun codec()=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
    private fun nonClosing(output:FileOutputStream)=object:FilterOutputStream(output) {
        override fun write(bytes:ByteArray,offset:Int,length:Int)=output.write(bytes,offset,length)
        override fun close() {flush()}
    }
    private val checkActive:()->Unit={}

    // PNGs are generated a row at a time; the largest allocation is one encoded ~3 MiB image.
    private fun png(file:File,seed:Int) {
        val width=1024;val height=1024;val random=Random(seed.toLong())
        val encoded=ByteArrayOutputStream()
        DeflaterOutputStream(encoded,Deflater(Deflater.BEST_SPEED)).use {zip->
            val row=ByteArray(width*3)
            repeat(height) {random.nextBytes(row);zip.write(0);zip.write(row)}
        }
        DataOutputStream(file.outputStream().buffered()).use {out->
            out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            fun chunk(name:String,bytes:ByteArray) {
                val type=name.toByteArray(Charsets.US_ASCII);val crc=CRC32().apply {update(type);update(bytes)}
                out.writeInt(bytes.size);out.write(type);out.write(bytes);out.writeInt(crc.value.toInt())
            }
            val ihdr=ByteArrayOutputStream().also {DataOutputStream(it).use {header->header.writeInt(width);header.writeInt(height);header.write(byteArrayOf(8,2,0,0,0))}}.toByteArray()
            chunk("IHDR",ihdr);chunk("IDAT",encoded.toByteArray());chunk("IEND",byteArrayOf())
        }
    }
    private fun fixture(directory:File):File {
        directory.mkdirs();val file=File(directory,"images-and-large-manifest.xnote")
        if(file.isFile) return file
        val doc=Document.blank(20).apply {identity=BundleIdentity("00000000-0000-4000-8000-00000000b001")}
        repeat(20) {i->
            val image=File(directory,"source-$i.png");png(image,7100+i)
            doc.pages[i].items+=ImageItem(ImageData(image,1024,1024),Rect(0.0,0.0,1024.0,1024.0))
        }
        val random=Random(9200);val alphabet="abcdefghijklmnopqrstuvwxyz0123456789 "
        repeat(256) {i->
            val text=CharArray(32768) {alphabet[random.nextInt(alphabet.length)]}.concatToString()
            doc.pages[i%20].items+=TextItem(Pt(20.0,20.0+i),width=1000.0,text=text,measurer=FakeTextMeasurer())
        }
        FileOutputStream(file).use {output->codec().write(doc,nonClosing(output));output.fd.sync()}
        assertTrue(file.length()>50L*1024*1024)
        directory.listFiles().orEmpty().filter {it.extension=="png"}.forEach {it.delete()}
        return file
    }
    private data class LockSample(val label:String,val wait:Long,val held:Long)
    private class Timing(private val storage:NotebookStorage) {
        private val type=Class.forName("com.xnotes.storage.BenchmarkStorageLockTiming")
        private val observer=type.getDeclaredField("observer")
        private val entered=type.getDeclaredField("entered")
        fun measure(block:()->Unit):JSONObject {
            val samples=CopyOnWriteArrayList<LockSample>()
            val ready=CountDownLatch(1);val acquired=AtomicBoolean()
            val actualLock=requireNotNull(NotebookStorage::class.java.getDeclaredField("lock").apply {isAccessible=true}.get(storage))
            var contendedWait=0L
            val probe=thread(name="native-storage-lock-probe") {
                if(ready.await(5,TimeUnit.MINUTES) && acquired.get()) {
                    val start=System.nanoTime();synchronized(actualLock) {contendedWait=System.nanoTime()-start}
                }
            }
            val record:(String,Long,Long)->Unit={label,wait,held->samples+=LockSample(label,wait,held)}
            val entry:(String)->Unit={label->if(label=="storage" && acquired.compareAndSet(false,true)) ready.countDown()}
            observer.set(null,record);entered.set(null,entry)
            val start=System.nanoTime();var end=start
            try {block()} finally {end=System.nanoTime();ready.countDown();probe.join(TimeUnit.MINUTES.toMillis(5));observer.set(null,null);entered.set(null,null)}
            assertFalse("Lock probe failed to complete",probe.isAlive)
            fun millis(ns:Long)=ns/1_000_000.0
            return JSONObject().put("total_ms",millis(end-start))
                .put("storage_lock_held_ms",millis(samples.filter {it.label=="storage"}.sumOf {it.held}))
                .put("storage_lock_wait_ms",millis(samples.filter {it.label=="storage"}.sumOf {it.wait}))
                .put("storage_lock_probe_wait_ms",millis(contendedWait))
                .put("storage_lock_segments",samples.count {it.label=="storage"})
                .put("editor_save_lock_held_ms",millis(samples.filter {it.label=="editor-save"}.sumOf {it.held}))
        }
    }

    @Test fun imagesAndLargeManifestExerciseActualSaveOpenAndStorageLocks() {
        assumeTrue("Explicit host benchmark opt-in is required",System.getProperty("xnotes.storageBenchmark")=="true")
        noEglHandles()
        val benchmarkRoot=File(requireNotNull(System.getProperty("xnotes.storageBenchmark.root")))
        val input=fixture(File(benchmarkRoot,"fixture"))
        val label=System.getProperty("xnotes.storageBenchmark.label","unlabelled")
        val output=File(benchmarkRoot,"$label.jsonl");requireNotNull(output.parentFile).mkdirs();output.writeText("")
        val root=File(benchmarkRoot,"run-$label-${UUID.randomUUID()}").apply {mkdirs()}
        val base=RuntimeEnvironment.getApplication()
        val packageStamp=File(root,"host-package-stamp").apply {writeText("isolated benchmark package stamp")}
        val context=object:ContextThemeWrapper(base,android.R.style.Theme_Material_Light_NoActionBar) {
            override fun getApplicationContext():Context=this
            override fun getApplicationInfo()=ApplicationInfo(base.applicationInfo).apply {sourceDir=packageStamp.absolutePath}
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getExternalFilesDir(type:String?)=File(root,"external").apply {mkdirs()}
        }
        val current=File(root,"working.xnote");input.copyTo(current,overwrite=true)
        val providerAuthority="native-storage-benchmark-${UUID.randomUUID()}"
        val uri=Uri.parse("content://$providerAuthority/document/working.xnote")
        val provider=Provider(current).apply {attachInfo(context,ProviderInfo().apply {this.authority=providerAuthority})}
        ShadowContentResolver.registerProviderInternal(providerAuthority,provider)
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=singleton.get(null);singleton.set(null,null)
        val storage=NotebookStorage.get(context);val timing=Timing(storage)
        var editor:Editor?=null
        try {
            com.xnotes.platform.FontCatalog.init(context);com.xnotes.platform.TemplateLibrary.init(context)
            val actualEditor=Editor(context);editor=actualEditor
            (Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(actualEditor) as CoroutineScope).cancel()
            // Keep host decoding identical between revisions; no rendering/pixel decode is timed.
            Editor::class.java.getDeclaredField("codec").apply {isAccessible=true}.set(actualEditor,codec())
            val noteCodec=codec();val canvasCodec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
            val documents=File(root,"model-assets").apply {mkdirs()}
            val doc=current.inputStream().use {noteCodec.read(it,imageDir=documents)}
            val isBranch=NotebookStorage::class.java.declaredMethods.any {it.name.substringBefore('$')=="prepareOpen"}
            val save=method(Editor::class.java,"writeNoteSafely")
            var expected:Any?=if(isBranch) {
                val source=call(method(NotebookStorage::class.java,"openSource"),storage,uri.toString(),DocumentKind.NOTE,checkActive)!!
                try {call(method(source.javaClass,"getVersion"),source)} finally {(source as AutoCloseable).close()}
            } else null
            val manifestBytes=ZipFile(input).use {it.getEntry("manifest.json").size}
            val payload=ZipFile(input).use {zip->zip.entries().asSequence().filter {it.name.startsWith("assets/")}.sumOf {it.size}}
            val sourceHash=com.xnotes.format.BundleMigration.hash(input)
            val metadata=JSONObject().put("fixture_sha256",sourceHash).put("label",label).put("bytes",input.length()).put("manifest_bytes",manifestBytes)
                .put("image_payload_bytes",payload).put("images",20).put("text_items",256).put("warmups",1).put("measurements",3)
            output.appendText(metadata.toString()+"\n");println("NATIVE_BENCH "+metadata)
            fun report(kind:String,index:Int,block:()->Unit) {
                val result=timing.measure(block).put("operation",kind).put("iteration",index).put("warmup",index==0).put("label",label)
                if(kind=="editor_save") {
                    result.put("encode_ms",actualEditor.state.lastSaveEncodeMs).put("copy_ms",actualEditor.state.lastSaveCopyMs)
                        .put("asset_encode_ms",actualEditor.state.lastSaveAssetsMs)
                        .put("save_path",if(isBranch) "full_candidate_expected_publication" else "manifest_tail_splice")
                    if(!isBranch) assertEquals("Master must actually take its manifest splice path",0L,actualEditor.state.lastSaveAssetsMs)
                }
                output.appendText(result.toString()+"\n");println("NATIVE_BENCH "+result)
            }
            fun assertContent(opened:Document) {
                assertEquals(20,opened.pages.size)
                assertEquals(20,opened.pages.sumOf {page->page.items.count {it is ImageItem}})
                assertEquals(256,opened.pages.sumOf {page->page.items.count {it is TextItem}})
                assertEquals(doc.pages.first().items.filterIsInstance<TextItem>().first().text,opened.pages.first().items.filterIsInstance<TextItem>().first().text)
                assertEquals(doc.identity.documentId,opened.identity.documentId)
            }
            repeat(4) {index->
                val text=doc.pages.first().items.filterIsInstance<TextItem>().first();text.text=text.text.take(32768)+" save $index"
                report("editor_save",index) {
                    val result=if(isBranch) call(save,actualEditor,uri.toString(),doc,expected) else call(save,actualEditor,uri.toString(),doc)
                    assertNotNull(result);if(result is Boolean) assertTrue(result) else expected=result
                }
                assertTrue(current.length()>50L*1024*1024);assertFalse(storage.hasRecovery(uri.toString()))
                report("open",index) {
                    if(isBranch) {
                        call(method(NotebookStorage::class.java,"ensureForOpen"),storage,uri.toString(),"working.xnote","xnote",checkActive)
                        val prepared=call(method(NotebookStorage::class.java,"prepareOpen"),storage,uri.toString(),DocumentKind.NOTE,noteCodec,canvasCodec,File(root,"pdf"),File(root,"opened"),null,checkActive)!!
                        try {assertContent(call(method(prepared.javaClass,"getNote"),prepared) as Document)}
                        finally {(prepared as AutoCloseable).close()}
                    } else {
                        storage.ensure(uri.toString(),"working.xnote","xnote")
                        val assets=File(root,"baseline-open-assets").apply {mkdirs()}
                        try {val opened=context.contentResolver.openInputStream(uri)!!.use {noteCodec.read(it,imageDir=assets)};assertContent(opened)}
                        finally {assets.deleteRecursively()}
                    }
                }
            }
            if(!isBranch) repeat(4) {index->
                report("full_candidate_save",index) {
                    val candidate=File(root,"full-candidate.xnote")
                    try {FileOutputStream(candidate).use {output->noteCodec.write(doc,nonClosing(output))};call(method(NotebookStorage::class.java,"publishNote"),storage,uri.toString(),candidate)}
                    finally {candidate.delete()}
                }
            }
        } finally {
            editor?.let {(Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(it) as CoroutineScope).cancel()}
            storage.registry.close();singleton.set(null,previous);root.deleteRecursively()
        }
    }
}
