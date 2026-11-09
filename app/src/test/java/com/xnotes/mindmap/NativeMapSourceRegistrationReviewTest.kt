package com.xnotes.mindmap

import android.content.Intent
import android.net.Uri
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.pen.EditorPageCopyFixture
import com.xnotes.storage.NativeSourceSnapshot
import com.xnotes.storage.NotebookStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException

/** Actual native ZIP/source-catalogue reads through the real provider and persisted-grant shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeMapSourceRegistrationReviewTest {
    private fun permission(context:android.content.Context,uri:Uri):Int=context.contentResolver.persistedUriPermissions
        .filter {it.uri==uri}.fold(0) {flags,p->flags or (if(p.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if(p.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)}
    private fun fixture(block:(EditorPageCopyFixture)->Unit) {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"native-source-registration-${UUID.randomUUID()}").apply {mkdirs()}
        val f=EditorPageCopyFixture(base,root,namespaced=true)
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true};val previous=singleton.get(null)
        val uris=listOf(Uri.parse(f.sourceUri),Uri.parse(f.targetUri));val before=uris.associateWith {permission(f.context,it)}
        singleton.set(null,f.storage)
        try {runBlocking(Dispatchers.IO) {f.seed();f.source.copyTo(f.target,overwrite=true);block(f)}}
        finally {
            uris.forEach {uri->val acquired=permission(f.context,uri) and before.getValue(uri).inv()
                if(acquired!=0) f.context.contentResolver.releasePersistableUriPermission(uri,acquired)}
            singleton.set(null,previous);f.storage.registry.close();root.deleteRecursively()
        }
    }
    @Test fun rejectingADuplicateNativeOfferPreservesItsCatalogueBytesAndTheOriginalRegistryRow()=fixture {f->
        val original=Uri.parse(f.sourceUri);val duplicate=Uri.parse(f.targetUri);val bytes=f.source.readBytes()
        val offer=MapSourceAccess.offer(f.context,original)
        val catalog=requireNotNull(offer.catalog);val pages=catalog.pages as NativeSourceSnapshot.ReferenceCatalog.Pages.Available
        assertEquals(2,pages.orderedIds.size);assertEquals(BundleMigration.hash(f.source),catalog.sha256)
        assertEquals(BundleReferenceInfo.inspectStrict(f.source).header.identity!!.documentId,offer.fileSource.documentId)
        val before=f.storage.registry.knownLocations().sorted()
        assertThrows(IllegalArgumentException::class.java) {MapSourceAccess.offer(f.context,duplicate)}
        assertEquals(before,f.storage.registry.knownLocations().sorted());assertNull(f.storage.registry.idAt(f.targetUri))
        assertNull(f.storage.registry.expectationAt(f.targetUri));assertEquals(f.sourceDisplayName,f.storage.registry.nameForId(offer.fileSource.documentId))
        assertTrue(f.storage.registry.expectationForId(offer.fileSource.documentId).requiresPages)
        assertArrayEquals(bytes,f.source.readBytes());assertArrayEquals(bytes,f.target.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }
    @Test fun rejectedNativeOffersReleaseOnlyNewReadGrantsAndKeepPreexistingReadOrWriteGrants()=fixture {f->
        val original=Uri.parse(f.sourceUri);val duplicate=Uri.parse(f.targetUri);val resolver=f.context.contentResolver
        MapSourceAccess.offer(f.context,original)
        val read=Intent.FLAG_GRANT_READ_URI_PERMISSION;val write=Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for(held in listOf(0,read,write,read or write)) {
            permission(f.context,duplicate).takeIf {it!=0}?.let {resolver.releasePersistableUriPermission(duplicate,it)}
            if(held!=0) resolver.takePersistableUriPermission(duplicate,held)
            assertEquals(held,permission(f.context,duplicate))
            val acquiredDuringRead=AtomicBoolean(false)
            f.beforeTargetRead={if(permission(f.context,duplicate) and read!=0) acquiredDuringRead.set(true)}
            assertThrows(IllegalArgumentException::class.java) {MapSourceAccess.offer(f.context,duplicate)}
            assertTrue("The actual failing native read must have held the newly requested read grant",acquiredDuringRead.get())
            assertEquals("Reject must release only grants acquired by this offer",held,permission(f.context,duplicate))
            assertNull(f.storage.registry.idAt(f.targetUri))
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aCancelledOfferCannotRevokeTheReadGrantRetainedByASuccessfulConcurrentOffer()=fixture {f->
        val uri=Uri.parse(f.sourceUri);val read=Intent.FLAG_GRANT_READ_URI_PERMISSION
        assertEquals(0,permission(f.context,uri))
        val aAcquired=CountDownLatch(1);val releaseA=CountDownLatch(1)
        val bStarted=CountDownLatch(1);val bAcquired=CountDownLatch(1)
        val workers=Executors.newFixedThreadPool(2)
        try {
            val first=workers.submit<MapSourceOffer> {
                MapSourceAccess.offer(f.context,uri) {
                    aAcquired.countDown();check(releaseA.await(10,TimeUnit.SECONDS))
                    throw CancellationException("The first picker request was cancelled")
                }
            }
            assertTrue(aAcquired.await(5,TimeUnit.SECONDS));assertEquals(read,permission(f.context,uri))
            val second=workers.submit<MapSourceOffer> {
                bStarted.countDown();MapSourceAccess.offer(f.context,uri) {bAcquired.countDown()}
            }
            assertTrue(bStarted.await(5,TimeUnit.SECONDS))
            // Before the fix B can finish using A's grant, then A revokes it. A serialized owner
            // legitimately queues B before its first check, so release A after the bounded wait.
            if(bAcquired.await(1,TimeUnit.SECONDS)) second.get(5,TimeUnit.SECONDS)
            releaseA.countDown()
            val stopped=assertThrows(ExecutionException::class.java) {first.get(5,TimeUnit.SECONDS)}
            assertTrue(stopped.cause is CancellationException)
            val accepted=second.get(5,TimeUnit.SECONDS)
            assertEquals(accepted.fileSource.documentId,f.storage.registry.idAt(f.sourceUri))
            assertEquals("A failed request must not withdraw a successful source's retained read permission",read,permission(f.context,uri))
            assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        } finally {releaseA.countDown();workers.shutdownNow()}
    }
}
