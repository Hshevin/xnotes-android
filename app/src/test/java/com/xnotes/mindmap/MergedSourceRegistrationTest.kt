package com.xnotes.mindmap

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Newly merged source entry points must retain the existing duplicate/permission guarantees. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MergedSourceRegistrationTest {
    private val read=Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val write=Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private fun permission(context:Context,uri:Uri):Int=context.contentResolver.persistedUriPermissions
        .filter {it.uri==uri}.fold(0) {flags,p->flags or (if(p.isReadPermission) read else 0) or
            (if(p.isWritePermission) write else 0)}
    private fun setPermission(context:Context,uri:Uri,flags:Int) {
        val resolver=context.contentResolver
        permission(context,uri).takeIf {it!=0}?.let {resolver.releasePersistableUriPermission(uri,it)}
        if(flags!=0) resolver.takePersistableUriPermission(uri,flags)
    }
    private fun withRestoredPermissions(f:MapBacklinkLibraryTest.Fixture,files:List<File>,block:()->Unit) {
        val before=files.map {f.uri(it)}.associateWith {permission(f.context,it)}
        try {block()} finally {before.forEach {(uri,flags)->setPermission(f.context,uri,flags)}}
    }
    private fun scenario(block:(MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(opaqueRoot=true) {f->
        withContext(Dispatchers.IO) {block(f)}
    }
    private fun native(f:MapBacklinkLibraryTest.Fixture,kind:DocumentKind):Pair<File,String> {
        val file=File(f.notes,"merged-original.${kind.extension}")
        val id=when(kind) {
            DocumentKind.NOTE->{
                val doc=Document(mutableListOf(Page(500.0,600.0))).apply {enableReferenceAnchors()}
                file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc.fixedWriteSnapshot(),it)}
                doc.identity.documentId
            }
            DocumentKind.CANVAS->{
                val doc=InfiniteDocument()
                file.outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc,it)}
                doc.identity.documentId
            }
            else->error("A native fixture needs a native kind")
        }
        return file to id
    }
    private fun rejectedDuplicate(f:MapBacklinkLibraryTest.Fixture,original:File,duplicate:File,id:String,
        reject:(Uri)->Unit) {
        val registry=f.storage.registry
        val originalUri=f.uri(original);val duplicateUri=f.uri(duplicate)
        val locations=registry.knownLocations().sorted()
        val rows=registry.fileObservations(setOf(id))
        val expectedOriginal=registry.expectationAt(originalUri.toString())
        val expectedId=registry.expectationForId(id)
        val originalBytes=original.readBytes();val duplicateBytes=duplicate.readBytes()
        val writes=f.writes.get();val deletes=f.deletes.get()
        assertEquals(id,registry.idAt(originalUri.toString()))
        assertEquals(original.name,registry.nameForId(id))
        assertNull(registry.expectationAt(duplicateUri.toString()))
        for(held in listOf(0,read,write,read or write)) {
            setPermission(f.context,duplicateUri,held)
            assertEquals(held,permission(f.context,duplicateUri))
            val originalPermission=permission(f.context,originalUri)
            var readPermissionObserved=false
            val reads=f.reads.get()
            f.beforeOpenRead={if(permission(f.context,duplicateUri) and read!=0) readPermissionObserved=true}
            try {assertThrows(IllegalArgumentException::class.java) {reject(duplicateUri)}}
            finally {f.beforeOpenRead=null}
            assertTrue("Reject must follow an actual provider read",f.reads.get()>reads)
            assertTrue("The actual rejected read must hold read permission",readPermissionObserved)
            assertEquals("Reject must not add a registered location",locations,registry.knownLocations().sorted())
            assertEquals("Reject must preserve registered kind/name/location rows",rows,registry.fileObservations(setOf(id)))
            assertEquals(original.name,registry.nameForId(id))
            assertNull(registry.idAt(duplicateUri.toString()))
            assertNull(registry.expectationAt(duplicateUri.toString()))
            assertEquals(expectedOriginal,registry.expectationAt(originalUri.toString()))
            assertEquals(expectedId,registry.expectationForId(id))
            assertEquals("Only this offer's new read grant may be released",held,permission(f.context,duplicateUri))
            assertEquals(originalPermission,permission(f.context,originalUri))
            assertArrayEquals(originalBytes,original.readBytes())
            assertArrayEquals(duplicateBytes,duplicate.readBytes())
            assertEquals(writes,f.writes.get());assertEquals(deletes,f.deletes.get())
        }
    }
    @Test fun canvasOfferRejectsDuplicateWithoutRegisteringItOrLeakingReadPermissions()=scenario {f->
        val (original,id)=native(f,DocumentKind.CANVAS)
        val duplicate=File(f.notes,"merged-duplicate.xcanvas").also {original.copyTo(it)}
        withRestoredPermissions(f,listOf(original,duplicate)) {
            val offer=MapSourceAccess.offerCanvas(f.context,f.uri(original),id)
            assertEquals(DocumentKind.CANVAS,offer.kind);assertEquals(id,offer.fileSource.documentId)
            rejectedDuplicate(f,original,duplicate,id) {MapSourceAccess.offerCanvas(f.context,it,id)}
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun nativeTargetSelectionRejectsDuplicateNotesAndCanvasesWithoutChangingDiscoveryOrPermissions()=scenario {f->
        val access=MapSourceTargetAccess(f.context,f.storage)
        for(kind in listOf(DocumentKind.NOTE,DocumentKind.CANVAS)) {
            val (original,id)=native(f,kind)
            val duplicate=File(f.notes,"merged-duplicate.${kind.extension}").also {original.copyTo(it)}
            withRestoredPermissions(f,listOf(original,duplicate)) {
                val offer=access.inspectFile(f.uri(original)) as SourceTargetOffer.Native
                try {assertEquals(kind,offer.offer.kind);assertEquals(id,offer.offer.fileSource.documentId)}
                finally {offer.closeAndAwait()}
                rejectedDuplicate(f,original,duplicate,id) {access.inspectFile(it).closeAndAwait()}
            }
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun mapTargetSelectionRejectsDuplicateWithoutChangingDiscoveryOrPermissions()=scenario {f->
        val document=MindMapDocument()
        val original=f.graph("merged-original.xmap",document)
        val duplicate=File(f.notes,"merged-duplicate.xmap").also {original.copyTo(it)}
        val access=MapSourceTargetAccess(f.context,f.storage)
        withRestoredPermissions(f,listOf(original,duplicate)) {
            val offer=access.inspectFile(f.uri(original)) as SourceTargetOffer.Map
            try {assertEquals(document.id,offer.documentId)} finally {offer.closeAndAwait()}
            rejectedDuplicate(f,original,duplicate,document.id) {access.inspectFile(it).closeAndAwait()}
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
}
