package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import com.xnotes.storage.NotebookStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID
import java.io.IOException
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapCreationConsumersHostTest {
    private class Provider(val root:File):ContentProvider() {
        val writes=mutableMapOf<String,Int>()
        fun file(uri:Uri)=File(root,requireNotNull(uri.lastPathSegment))
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/octet-stream"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=error("Copy must not delete a provider target")
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            val writing='w' in mode
            if(writing) writes[uri.toString()]=(writes[uri.toString()] ?: 0)+1
            return ParcelFileDescriptor.open(file(uri),if(writing) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
    private fun scenario(block:(Context,Provider,Uri,Uri,Uri)->Unit) {
        val base=RuntimeEnvironment.getApplication();val root=File(base.cacheDir,"map-create-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val authority="map-creation-${UUID.randomUUID()}";val provider=Provider(root)
        provider.attachInfo(context,ProviderInfo().apply {this.authority=authority})
        ShadowContentResolver.registerProviderInternal(authority,provider)
        val source=Uri.parse("content://$authority/source.xmap")
        val occupied=Uri.parse("content://$authority/occupied.xmap")
        val empty=Uri.parse("content://$authority/new.xmap")
        val doc=MindMapDocument(nodes=listOf(MapNode(parent=null,text="source 🖊",kind=NodeKind.INK,
            ink=listOf(MapInk(listOf(InkPoint(10.0,20.0,.8,120.0)))),
            sources=listOf(MapSource(documentId="00000000-0000-4000-8000-000000000099")))))
        provider.file(source).writeBytes(MapCodec.encode(doc))
        provider.file(occupied).writeBytes(MapCodec.encode(MindMapDocument()))
        provider.file(empty).writeBytes(byteArrayOf())
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=singleton.get(null);val storage=NotebookStorage(context)
        singleton.set(null,storage)
        try {block(context,provider,source,occupied,empty)} finally {
            storage.registry.close();singleton.set(null,previous);root.deleteRecursively()
        }
    }
    private fun await(condition:()->Boolean) {
        repeat(500) {shadowOf(Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(5)}
        fail("Actual MapSession IO did not settle")
    }
    @Test fun actualSessionCopyRefusesOccupiedTargetsAndPreservesItsSourceWhenAnEmptyCopyGetsANewIdentity()=scenario {context,p,source,occupied,empty->
        val sourceBytes=p.file(source).readBytes();val occupiedBytes=p.file(occupied).readBytes()
        val session=MapSession();session.open(context,source);await {!session.loading}
        assertFalse(session.loadFailed);val model=requireNotNull(session.document);val selected=session.selected
        var completed=0
        session.saveCopy(occupied) {completed++};await {!session.saving}
        assertTrue(session.saveFailed);assertFalse(session.copySaved);assertEquals(0,completed)
        assertEquals(0,p.writes[occupied.toString()] ?: 0);assertArrayEquals(occupiedBytes,p.file(occupied).readBytes())
        session.saveCopy(empty) {completed++};await {!session.saving}
        assertTrue(session.copySaved);assertEquals(1,completed);assertEquals(1,p.writes[empty.toString()])
        val copy=MapCodec.decode(p.file(empty).readBytes())
        assertNotEquals(model.id,copy.id);assertEquals(model.copy(id=copy.id),copy)
        assertSame(model,session.document);assertEquals(selected,session.selected)
        assertFalse(session.dirty);assertFalse(session.canUndo);assertFalse(session.canRedo)
        assertArrayEquals(sourceBytes,p.file(source).readBytes());assertEquals(0,p.writes[source.toString()] ?: 0)
    }
    @Test fun storedJsonCopyChecksBeforeOpeningOutputAndCreatesAnIndependentGraphWithoutTouchingItsSource()=scenario {context,p,source,occupied,empty->
        val original=p.file(source).readBytes();val before=p.file(occupied).readBytes()
        assertThrows(Exception::class.java) {runBlocking {MapFiles.copyToDestination(context,source.toString(),occupied)}}
        assertEquals(0,p.writes[occupied.toString()] ?: 0);assertArrayEquals(before,p.file(occupied).readBytes())
        runBlocking {MapFiles.copyToDestination(context,source.toString(),empty)}
        val model=MapCodec.decode(original);val copy=MapCodec.decode(p.file(empty).readBytes())
        assertNotEquals(model.id,copy.id);assertEquals(model.copy(id=copy.id),copy)
        assertEquals(1,p.writes[empty.toString()]);assertArrayEquals(original,p.file(source).readBytes())
        assertEquals(0,p.writes[source.toString()] ?: 0)
    }
    @Test fun canonicalSameTargetsShareOneLockAndCurrentConstructibleAndRegisteredOldJournalsBlockCreation()=scenario {context,p,source,_,empty->
        val authority=requireNotNull(source.authority)
        val direct=DocumentsContract.buildDocumentUri(authority,"source.xmap")
        val same=DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority,"root"),"source.xmap")
        val original=p.file(source).readBytes()
        assertSame(MapSaves.lockFor(direct),MapSaves.lockFor(same))
        assertThrows(IOException::class.java) {runBlocking {MapFiles.copyToDestination(context,direct.toString(),same)}}
        assertTrue(p.writes.isEmpty());assertArrayEquals(original,p.file(source).readBytes())

        val destination=DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority,"current"),"new.xmap")
        val canonical=DocumentsContract.buildDocumentUri(authority,"new.xmap")
        val registered=DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(authority,"old"),"new.xmap")
        val candidate=MapCodec.decode(original).copy(id=UUID.randomUUID().toString())
        NotebookStorage.get(context).registry.register(candidate.id,"xmap","new.xmap",registered.toString())
        val directory=File(context.noBackupFilesDir,"mind-map-recovery")
        for(alias in listOf(destination,canonical,registered)) {
            val old=MapStore(directory,alias.toString(),object:MapTarget {
                override fun read()=byteArrayOf()
                override fun write(bytes:ByteArray) {throw IOException("retained before provider write")}
            })
            assertThrows(IOException::class.java) {old.create(candidate)}
            val journal=directory.listFiles()!!.single();val bytes=journal.readBytes()
            assertThrows(IOException::class.java) {runBlocking {MapFiles.copyToDestination(context,source.toString(),destination)}}
            assertEquals(0,p.writes[destination.toString()] ?: 0);assertArrayEquals(bytes,journal.readBytes())
            val recovered=runBlocking(Dispatchers.IO) {MapFiles.store(context,destination).open()}
            assertEquals(candidate,recovered.document);assertTrue(recovered.recovered)
            assertTrue(journal.delete()) // Isolate the next deliberately different legacy-key fixture.
        }
        assertTrue(p.file(empty).readBytes().isEmpty());assertArrayEquals(original,p.file(source).readBytes())
    }
    @Test fun cancellationDuringSourceReadNeverOpensTheDestinationForWrite()=scenario {context,p,source,_,empty->
        val original=p.file(source).readBytes();var checks=0
        assertThrows(CancellationException::class.java) {runBlocking {
            MapFiles.copyToDestination(context,source.toString(),empty) {if(++checks==3) throw CancellationException("source cancelled")}
        }}
        assertTrue(checks>=3);assertTrue(p.writes.isEmpty());assertArrayEquals(original,p.file(source).readBytes())
        assertTrue(p.file(empty).readBytes().isEmpty())
    }
}
