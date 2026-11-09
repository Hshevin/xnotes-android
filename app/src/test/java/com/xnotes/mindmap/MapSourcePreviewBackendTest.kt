package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.*
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.NativeDocumentPreview
import com.xnotes.platform.FontCatalog
import com.xnotes.platform.TemplateLibrary
import com.xnotes.core.pal.FontFace
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Actual owned source reads + ordinary CPU raster/PNG. Does not establish PDFium/device behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapSourcePreviewBackendTest {
    private class Provider(val root:File):ContentProvider() {
        var reads=0;var writes=0
        var allowWrites=false
        var afterTruncate:(()->Unit)?=null
        var queried:(()->Unit)?=null
        fun file(uri:Uri)=File(root,requireNotNull(uri.lastPathSegment))
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/octet-stream"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
            queried?.invoke()
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {addRow(arrayOf(file(uri).name))}
        }
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=error("Preview must not delete source")
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            if('w' in mode) {
                writes++;check(allowWrites) {"Preview must not open a source writer"}
                val fd=ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
                try {afterTruncate?.invoke();return fd} catch(error:Throwable) {fd.close();throw error}
            };reads++
            return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
    private class Fixture(initializeCatalogs:Boolean=true):AutoCloseable {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"preview-backend-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val authority="preview-backend-${UUID.randomUUID()}"
        val provider=Provider(root)
        val storage=NotebookStorage(context)
        private val field=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        private val previous=field.get(null)
        val noteCodec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val canvasCodec=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
        init {
            provider.attachInfo(context,ProviderInfo().apply {authority=this@Fixture.authority});ShadowContentResolver.registerProviderInternal(authority,provider)
            field.set(null,storage)
            if(initializeCatalogs) {FontCatalog.init(context);TemplateLibrary.init(context)}
        }
        fun uri(name:String)=Uri.parse("content://$authority/document/$name")
        fun note(doc:Document):Uri=uri("source.xnote").also {uri->
            provider.file(uri).outputStream().use {noteCodec.write(doc,it)}
            storage.registry.register(doc.identity.documentId,"xnote","source.xnote",uri.toString(),pageReferences=doc.hasReferenceAnchors)
        }
        fun load(targets:List<MapSourceTarget>,render:Set<MapSourceTarget> = targets.toSet(),check:()->Unit={})=
            runBlocking {MapSourcePreviewBackend.load(context,targets,render,check)}
        fun assetsGone()=File(context.cacheDir,"source-preview-assets").listFiles().orEmpty().isEmpty()
        override fun close() {storage.registry.close();field.set(null,previous);root.deleteRecursively()}
    }
    private fun shape(x:Double,y:Double)=ShapeItem(ShapeKind.RECTANGLE,Pt(x,y),Pt(x+40,y+40),Rgba(0,0,0),fillRgba=Rgba(0,0,0))
    private fun note():Document=Document(mutableListOf(Page(200.0,200.0,mutableListOf(shape(20.0,20.0))),Page(200.0,200.0,mutableListOf(shape(140.0,140.0)))),dpi=72)
        .apply {style=PageStyle(template=PageTemplates.NONE);enableReferenceAnchors()}
    private fun pixel(asset:MapPreviewAsset,x:Int,y:Int):Int {
        val bytes=asset.pngBytes();val image=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        return try {image.getPixel(x,y)} finally {image.recycle()}
    }
    /** Reset the process catalogs only for a cold-start fixture, restoring all prior library state. */
    private class SavedCatalogs:AutoCloseable {
        private class Value(val owner:Any,val field:java.lang.reflect.Field,val old:Any?)
        private val values=mutableListOf<Value>()
        init {
            fun save(owner:Any,names:List<String>) {names.forEach {name->
                val field=owner.javaClass.getDeclaredField(name).apply {isAccessible=true};val value=field.get(owner)
                val old=if(java.lang.reflect.Modifier.isFinal(field.modifiers)) when(value) {
                    is Map<*,*>->HashMap(value);is Set<*>->HashSet(value);else->value
                } else value
                values+=Value(owner,field,old)
            }}
            save(FontCatalog,listOf("assets","customDir","custom","cache"))
            save(TemplateLibrary,listOf("legacyDir","cacheDir","assets","apkStamp","packagesLoaded","store","bundled","imported","handles","parsed","packageFiles","pictures","missingPictures"))
            save(TemplateLibrary.layouts,listOf("map"))
        }
        private fun set(owner:Any,name:String,value:Any?) {owner.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(owner,value)}
        @Suppress("UNCHECKED_CAST") fun cold() {
            set(FontCatalog,"assets",null);set(FontCatalog,"customDir",null)
            for(name in listOf("custom","cache")) (FontCatalog.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(FontCatalog) as MutableMap<Any?,Any?>).clear()
            set(TemplateLibrary,"assets",null);set(TemplateLibrary,"legacyDir",null);set(TemplateLibrary,"cacheDir",null)
            set(TemplateLibrary,"apkStamp",0L);set(TemplateLibrary,"packagesLoaded",false);set(TemplateLibrary,"store",null)
            set(TemplateLibrary,"bundled",emptyList<TemplateLibrary.Entry>());set(TemplateLibrary,"imported",emptyMap<String,TemplateLibrary.Entry>())
            set(TemplateLibrary,"handles",emptyMap<String,String>())
        }
        @Suppress("UNCHECKED_CAST") override fun close() {values.asReversed().forEach {saved->
            if(!java.lang.reflect.Modifier.isFinal(saved.field.modifiers)) saved.field.set(saved.owner,saved.old)
            else when(val actual=saved.field.get(saved.owner)) {
                is MutableMap<*,*>->{val map=actual as MutableMap<Any?,Any?>;map.clear();map.putAll(saved.old as Map<Any?,Any?>)}
                is MutableSet<*>->{val set=actual as MutableSet<Any?>;set.clear();set.addAll(saved.old as Set<Any?>)}
            }
        }}
    }
    @Test fun oneActualSnapshotServesMultiplePermanentPagesAndFileAndReorderingDoesNotRetargetCards()=Fixture().use {f->
        val doc=note();val uri=f.note(doc);val before=f.provider.file(uri).readBytes();val id=doc.identity.documentId
        val a=MapSourceTarget(id,SourceScope.PAGE,doc.pages[0].referenceIdentity.id)
        val b=MapSourceTarget(id,SourceScope.PAGE,doc.pages[1].referenceIdentity.id)
        val file=MapSourceTarget(id,SourceScope.FILE)
        val rows=f.load(listOf(b,file,a));assertEquals(1,f.provider.reads);assertEquals(0,f.provider.writes)
        assertTrue(rows.all {it.status==SourceStatus.CURRENT && !it.failedPreview && it.asset!=null})
        assertEquals(2,rows[0].lastKnownPage);assertEquals(1,rows[2].lastKnownPage)
        assertEquals(Color.BLACK,pixel(rows[0].asset!!,205,205));assertEquals(Color.WHITE,pixel(rows[0].asset!!,50,50))
        assertEquals(Color.BLACK,pixel(rows[2].asset!!,50,50));assertEquals(Color.WHITE,pixel(rows[2].asset!!,205,205))
        assertEquals(rows[2].asset!!.key,rows[1].asset!!.key);assertEquals("native-sha256:${rows[0].rawVersion}",rows[0].renderedVersion)
        assertTrue(f.assetsGone());assertArrayEquals(before,f.provider.file(uri).readBytes())
        java.util.Collections.swap(doc.pages,0,1);f.note(doc)
        val reordered=f.load(listOf(b,file,a));assertEquals(rows[0].asset!!.key,reordered[0].asset!!.key)
        assertEquals(1,reordered[0].lastKnownPage);assertEquals(reordered[0].asset!!.key,reordered[1].asset!!.key)
        assertNotEquals(rows[0].rawVersion,reordered[0].rawVersion);assertEquals(2,f.provider.reads)
    }
    @Test fun pageRegionsAndMissingIdsAreProvedWithoutWholePageFallbackAndChecksNeverDecode()=Fixture().use {f->
        val doc=note();f.note(doc);val id=doc.identity.documentId;val page=doc.pages[0].referenceIdentity.id
        val inside=MapSourceTarget(id,SourceScope.PAGE,page,Rect(20.0,20.0,40.0,40.0))
        val outside=inside.copy(region=Rect(500.0,500.0,20.0,20.0));val missing=inside.copy(pageId=UUID.randomUUID().toString(),region=null)
        val checked=f.load(listOf(inside,outside,missing),emptySet());assertEquals(1,f.provider.reads)
        assertTrue(checked.all {it.asset==null});assertTrue(f.assetsGone());assertEquals(SourceStatus.DELETED,checked[2].status)
        val rendered=f.load(listOf(inside,outside,missing));assertEquals(SourceStatus.CURRENT,rendered[0].status)
        assertEquals(Color.BLACK,pixel(rendered[0].asset!!,128,128));assertEquals(SourceStatus.EMPTY,rendered[1].status)
        assertNull(rendered[1].asset);assertEquals(SourceStatus.DELETED,rendered[2].status);assertNull(rendered[2].asset)
        assertTrue(f.assetsGone());assertEquals(0,f.provider.writes)
    }
    @Test fun cancellingRealPaintReleasesOnlyOwnedAssetsAndAllowsANewObservation()=Fixture().use {f->
        val doc=note();val uri=f.note(doc);val before=f.provider.file(uri).readBytes()
        val target=MapSourceTarget(doc.identity.documentId,SourceScope.PAGE,doc.pages[0].referenceIdentity.id)
        val unrelated=File(f.context.cacheDir,"other-owner").apply {mkdir();File(this,"keep").writeText("keep")}
        var checks=0
        assertThrows(CancellationException::class.java) {f.load(listOf(target)) {
            if(Thread.currentThread().stackTrace.any {it.className==NativeDocumentPreview::class.java.name && it.methodName=="note"} && ++checks==4)
                throw CancellationException("cancel actual source paint")
        }}
        assertEquals(4,checks);assertTrue(f.assetsGone());assertTrue(File(unrelated,"keep").isFile)
        assertArrayEquals(before,f.provider.file(uri).readBytes());assertEquals(0,f.provider.writes)
        assertNotNull(f.load(listOf(target)).single().asset)
    }
    @Test fun canvasUsesOrdinaryCpuAndMapVersionsIgnoreDerivedPreviewMetadata()=Fixture().use {f->
        val canvas=InfiniteDocument().apply {add(shape(0.0,0.0))};val canvasUri=f.uri("source.xcanvas")
        f.provider.file(canvasUri).outputStream().use {f.canvasCodec.write(canvas,it)}
        f.storage.registry.register(canvas.identity.documentId,"xcanvas","source.xcanvas",canvasUri.toString())
        val ct=MapSourceTarget(canvas.identity.documentId,SourceScope.FILE);assertNotNull(f.load(listOf(ct)).single().asset)
        val source=MapSource(documentId=UUID.randomUUID().toString(),fileName="Referenced file")
        val doc=MindMapDocument(nodes=listOf(MapNode(parent=null,text="Actual map",sources=listOf(source))))
        val uri=f.uri("source.xmap");f.provider.file(uri).writeBytes(MapCodec.encode(doc))
        f.storage.registry.registerMapObserved(doc.id,"source.xmap",uri.toString())
        val target=MapSourceTarget(doc.id,SourceScope.FILE);val first=f.load(listOf(target)).single();assertNotNull(first.asset)
        val asset=first.asset!!
        val updated=doc.copy(nodes=doc.nodes.map {it.copy(sources=listOf(source.copy(status=SourceStatus.STALE,previewKey=asset.key,
            observedVersion="derived",lastPreview=MapLastPreview(asset.key,source.target,"native-sha256:old",0))))},previewAssets=mapOf(asset.key to asset))
        f.provider.file(uri).writeBytes(MapCodec.encode(updated));val second=f.load(listOf(target)).single()
        assertNotEquals(first.rawVersion,second.rawVersion);assertEquals(first.renderedVersion,second.renderedVersion)
        assertEquals(first.asset!!.key,second.asset!!.key);assertEquals(0,f.provider.writes);assertTrue(f.assetsGone())
    }
    @Test fun mapPreviewWaitsForTheCanonicalWriterToFinishItsActualTruncatedFile()=Fixture().use {f->runBlocking {
        val doc=MindMapDocument(nodes=listOf(MapNode(parent=null,text="A")))
        val next=doc.copy(nodes=doc.nodes.map {it.copy(text="Complete B")})
        val uri=f.uri("source.xmap")
        val alias=DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(f.authority,"root"),"source.xmap")
        f.provider.file(uri).writeBytes(MapCodec.encode(doc));f.storage.registry.registerMapObserved(doc.id,"source.xmap",alias.toString())
        assertSame(MapSaves.lockFor(uri),MapSaves.lockFor(alias))
        val target=MapSourceTarget(doc.id,SourceScope.FILE)
        val truncated=CountDownLatch(1);val release=CountDownLatch(1);val queried=CountDownLatch(1)
        f.provider.allowWrites=true;f.provider.afterTruncate={truncated.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val writer=async(Dispatchers.IO) {MapSaves.lockFor(uri).withLock {MapFiles.target(f.context,uri).write(MapCodec.encode(next))}}
        try {
            assertTrue(truncated.await(5,TimeUnit.SECONDS));assertEquals(0,f.provider.file(uri).length())
            f.provider.queried={queried.countDown()}
            val reader=async(Dispatchers.IO) {MapSourcePreviewBackend.load(f.context,listOf(target),emptySet())}
            assertTrue(queried.await(5,TimeUnit.SECONDS))
            val premature=withTimeoutOrNull(300) {reader.await()}
            assertNull("Preview read half JSON while the canonical save still held its lock",premature)
            assertEquals(0,f.provider.reads)
            release.countDown();writer.await()
            val result=withTimeout(5000) {reader.await()}.single()
            assertEquals(SourceStatus.CURRENT,result.status);assertEquals(MapCodec.semanticPreviewVersion(next),result.renderedVersion)
            assertEquals(next,MapCodec.decode(f.provider.file(uri).readBytes()));assertEquals(1,f.provider.reads)
        } finally {release.countDown();writer.await();f.provider.afterTruncate=null;f.provider.queried=null}
    }}
    @Test fun cancellingAPreviewWaitingForTheWriterNeverOpensTheSourceAfterUnlock()=Fixture().use {f->runBlocking {
        val doc=MindMapDocument();val uri=f.uri("source.xmap");f.provider.file(uri).writeBytes(MapCodec.encode(doc))
        f.storage.registry.registerMapObserved(doc.id,"source.xmap",uri.toString());val target=MapSourceTarget(doc.id,SourceScope.FILE)
        val truncated=CountDownLatch(1);val release=CountDownLatch(1);val queried=CountDownLatch(1)
        f.provider.allowWrites=true;f.provider.afterTruncate={truncated.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val writer=async(Dispatchers.IO) {MapSaves.lockFor(uri).withLock {MapFiles.target(f.context,uri).write(MapCodec.encode(doc))}}
        try {
            assertTrue(truncated.await(5,TimeUnit.SECONDS));f.provider.queried={queried.countDown()}
            val reader=async(Dispatchers.IO) {MapSourcePreviewBackend.load(f.context,listOf(target),emptySet())}
            assertTrue(queried.await(5,TimeUnit.SECONDS))
            assertNull("The preview did not wait at the actual writer lock",withTimeoutOrNull(300) {reader.await()})
            withTimeout(2000) {reader.cancelAndJoin()}
            assertTrue(MapSaves.lockFor(uri).isLocked);assertEquals(0,f.provider.reads)
            release.countDown();writer.await();assertEquals(0,f.provider.reads)
            assertEquals(doc,MapCodec.decode(f.provider.file(uri).readBytes()))
        } finally {release.countDown();writer.await();f.provider.afterTruncate=null;f.provider.queried=null}
    }}
    @Test fun coldBackendWithoutMainActivityPaintsBuiltInLinesInsteadOfSuccessfulBlankPaper()=SavedCatalogs().use {catalogs->
        catalogs.cold()
        Fixture(initializeCatalogs=false).use {f->
            val doc=Document(mutableListOf(Page(200.0,200.0)),dpi=72).apply {
                style=PageStyle(template="lines",patternColor=Rgba(0,0,0),spacing=40.0);enableReferenceAnchors()
            }
            f.note(doc);val target=MapSourceTarget(doc.identity.documentId,SourceScope.FILE)
            val read=f.load(listOf(target)).single();assertEquals(SourceStatus.CURRENT,read.status)
            val bytes=requireNotNull(read.asset).pngBytes();val image=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            try {
                val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                assertTrue("Cold source preview silently omitted the built-in lines template",pixels.any {it!=Color.WHITE})
            } finally {image.recycle()}
            assertEquals(1,f.provider.reads);assertEquals(0,f.provider.writes)
        }
    }
    @Test fun coldBackendInvalidatesAnEarlySansFallbackAndActuallyRendersTheBundledFace()=SavedCatalogs().use {catalogs->
        catalogs.cold()
        Fixture(initializeCatalogs=false).use {f->
            val face=FontFace("Fira Sans");val fallback=FontCatalog.resolve(face,false,false)
            val doc=Document(mutableListOf(Page(300.0,200.0)),dpi=72).apply {
                style=PageStyle(template=PageTemplates.NONE);flow.defaultFace=face
                flow.paragraphs+=Paragraph(mutableListOf(Run("MW il Fira Sans")));enableReferenceAnchors()
            }
            f.note(doc);val target=MapSourceTarget(doc.identity.documentId,SourceScope.FILE)
            val actual=f.load(listOf(target)).single();val resolved=FontCatalog.resolve(face,false,false)
            assertNotSame("A pre-init sans fallback remained in the font cache",fallback,resolved)
            assertNotEquals("The bundled face still resolved to the sans fallback",fallback.typeface,resolved.typeface)
            val actualAsset=requireNotNull(actual.asset)
            // A separately warmed ordinary render must depict the exact same bundled font pixels.
            FontCatalog.javaClass.getDeclaredField("cache").apply {isAccessible=true}.get(FontCatalog).let {(it as MutableMap<*,*>).clear()}
            FontCatalog.init(f.context);val warmed=f.load(listOf(target)).single()
            assertEquals(actualAsset.key,requireNotNull(warmed.asset).key);assertEquals(0,f.provider.writes)
        }
    }
    @Test fun warmBackendDoesNotReloadTheActiveTemplateStoreOrFontCache()=SavedCatalogs().use {catalogs->
        catalogs.cold()
        Fixture().use {f->
            val raw=f.context.assets.open("templates/lines.xtemplate").use {String(it.readBytes())}
                .replace("com.xnotes.lines","com.xnotes.preview-store-test").replace("\"Lines\"","\"Store template\"")
            var lists=0
            val store=object:TemplateLibrary.Store {
                override fun list():List<TemplateLibrary.StoredFile> {lists++;return listOf(TemplateLibrary.StoredFile("owned","owned.xtemplate") {raw.byteInputStream()})}
                override fun write(name:String,from:File):String?=error("Preview must not write imports")
                override fun delete(handle:String)=error("Preview must not delete imports")
            }
            TemplateLibrary.reload(store);val key=TemplateLibrary.keyOf(raw);val entry=requireNotNull(TemplateLibrary.entry(key));val initialLists=lists
            val face=FontCatalog.resolve(FontFace("Fira Sans"),false,false)
            val doc=note();f.note(doc);val target=MapSourceTarget(doc.identity.documentId,SourceScope.FILE)
            repeat(2) {assertNotNull(f.load(listOf(target)).single().asset)}
            assertSame(entry,TemplateLibrary.entry(key));assertEquals(initialLists,lists)
            assertSame(store,TemplateLibrary.javaClass.getDeclaredField("store").apply {isAccessible=true}.get(TemplateLibrary))
            assertSame(face,FontCatalog.resolve(FontFace("Fira Sans"),false,false))
        }
    }
}
