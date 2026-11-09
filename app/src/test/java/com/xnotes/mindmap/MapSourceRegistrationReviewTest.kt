package com.xnotes.mindmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapSourceRegistrationReviewTest {
    @Test fun aRejectedDuplicateMapOfferDoesNotRegisterItsUriOrReplaceTheKnownName()=MapBacklinkLibraryTest().scenario {f->
        val doc=MindMapDocument();val original=f.graph("original.xmap",doc);val duplicate=f.graph("duplicate.xmap",doc)
        withContext(Dispatchers.IO) {
            MapSourceAccess.offer(f.context,f.uri(original))
            val before=f.storage.registry.knownLocations()
            val permissions=f.context.contentResolver.persistedUriPermissions.map {Triple(it.uri,it.isReadPermission,it.isWritePermission)}
            assertThrows(IllegalArgumentException::class.java) {MapSourceAccess.offer(f.context,f.uri(duplicate))}
            assertEquals(before,f.storage.registry.knownLocations())
            assertNull(f.storage.registry.idAt(f.uri(duplicate).toString()))
            assertEquals(original.name,f.storage.registry.nameForId(doc.id))
            assertEquals(permissions,f.context.contentResolver.persistedUriPermissions.map {Triple(it.uri,it.isReadPermission,it.isWritePermission)})
            val uri=f.uri(duplicate)
            f.context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val preexisting=f.context.contentResolver.persistedUriPermissions.map {Triple(it.uri,it.isReadPermission,it.isWritePermission)}
            assertTrue(preexisting.any {it.first==uri && it.second})
            assertThrows(IllegalArgumentException::class.java) {MapSourceAccess.offer(f.context,uri)}
            assertEquals(preexisting,f.context.contentResolver.persistedUriPermissions.map {Triple(it.uri,it.isReadPermission,it.isWritePermission)})
        }
        assertArrayEquals(original.readBytes(),duplicate.readBytes());assertEquals(0,f.writes.get())
    }
}
