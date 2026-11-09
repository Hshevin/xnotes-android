package com.xnotes.mindmap

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import com.xnotes.storage.DocumentRegistry
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.OriginalPdfCatalog
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual SQLite scalar pagination. No permanent-page arrays, PDF engine or writer are needed
 * to list rows; a displayed available row is still untrusted until openOriginal verifies it. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OriginalSourcePickerAccessTest {
    private class Fixture(val root:File) {
        val privateDir=File(root,"private")
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=privateDir
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val storage=NotebookStorage(context)
        val registry:DocumentRegistry get()=storage.registry
        val catalog=OriginalPdfCatalog(registry)
        val access=OriginalSourcePickerAccess(registry)
        val database=File(registry.databaseName)
        fun warm() {privateDir.mkdirs();registry.writableDatabase}
        fun seed(id:String=UUID.randomUUID().toString(),title:String="Original",deleted:Any=0,schema:Any=1):String {
            warm()
            val sha=UUID.randomUUID().toString().replace("-","").repeat(2)
            // Large page_count and no page rows deliberately prove the picker is scalar-only.
            registry.writableDatabase.execSQL("INSERT INTO original_pdf_documents VALUES(?,?,?,?,?,?,?)",
                arrayOf(id,sha,title,1_000_000,1,deleted,schema))
            return id
        }
        fun fingerprint():Map<String,String> = if(!privateDir.exists()) emptyMap() else privateDir.walkTopDown().filter {it.isFile}.associate {
            it.relativeTo(privateDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") {b->"%02x".format(b)}
        }
    }
    private fun scenario(block:(Fixture)->Unit) {
        val f=Fixture(Files.createTempDirectory("source-picker-").toFile())
        try {block(f)} finally {f.registry.close();f.root.deleteRecursively()}
    }
    @Test fun coldMissingDatabaseListsEmptyWithoutCreatingStorageOrResources()=scenario {f->
        assertFalse(f.privateDir.exists())
        val page=f.access.page(null) {}
        assertTrue(page.supported);assertTrue(page.current());assertTrue(page.entries.isEmpty());assertNull(page.next)
        assertFalse(f.privateDir.exists());assertFalse(f.database.exists())
    }
    @Test fun paginationIsBoundedToOneHundredScalarRowsAndOneProbeWithoutMaterializingPageDirectories()=scenario {f->
        val ids=(0 until 205).map {f.seed(title="Original $it")}
        val before=f.fingerprint();val first=f.access.page(null) {}
        assertTrue(first.supported);assertEquals(ids.take(100),first.entries.map {it.documentId})
        assertTrue(first.entries.all {it.current() && it.state==OriginalPickerState.ROW_AVAILABLE})
        val second=f.access.page(first.next!!) {};assertEquals(ids.drop(100).take(100),second.entries.map {it.documentId})
        val third=f.access.page(second.next!!) {};assertEquals(ids.drop(200),third.entries.map {it.documentId});assertNull(third.next)
        assertEquals(before,f.fingerprint())
        // A scalar row is not source authority: incomplete permanent-page metadata still refuses opening.
        val access=MapSourceTargetAccess(f.context,f.storage)
        assertThrows(Exception::class.java) {access.openOriginal(first.entries.first())}
        assertEquals(before,f.fingerprint())
    }
    @Test fun hugeAndMalformedRowsStayVisibleWithoutBlockingHealthyRowsOrRewritingTheDatabase()=scenario {f->
        val hugeId="i".repeat(3*1024*1024);f.seed(id=hugeId)
        f.seed(title="short"+0.toChar()+"x".repeat(3*1024*1024))
        f.seed(title="\uD83D\uDE00".repeat(4096))
        f.seed(deleted=1.25)
        val future=f.seed(schema=99)
        val deleted=f.seed(deleted=1)
        val legacy=f.seed(title="A"+0.toChar()+"B")
        val healthy=f.seed(title="Healthy")
        val before=f.fingerprint();val page=f.access.page(null) {}
        assertTrue(page.supported);assertEquals(8,page.entries.size)
        assertNull(page.entries[0].documentId);assertNull(page.entries[1].title);assertNull(page.entries[2].title)
        assertEquals(List(4) {OriginalPickerState.CORRUPT},page.entries.take(4).map {it.state})
        assertEquals(future,page.entries[4].documentId);assertEquals(OriginalPickerState.UNSUPPORTED,page.entries[4].state)
        assertEquals(deleted,page.entries[5].documentId);assertEquals(OriginalPickerState.ROW_DELETED,page.entries[5].state)
        assertEquals(legacy,page.entries[6].documentId);assertEquals("A"+0.toChar()+"B",page.entries[6].title)
        assertEquals(OriginalPickerState.ROW_AVAILABLE,page.entries[6].state)
        assertEquals(healthy,page.entries.last().documentId);assertEquals(OriginalPickerState.ROW_AVAILABLE,page.entries.last().state)
        assertEquals(before,f.fingerprint())
    }
    @Test fun mutationStartRetiresRowsAndContinuationCursorsEvenIfTheCatalogContentsDoNotChange()=scenario {f->
        repeat(101) {f.seed()};val page=f.access.page(null) {};val cursor=page.next!!
        assertTrue(page.current());assertTrue(cursor.current())
        val before=f.fingerprint();val change=f.catalog.beginNetworkMutation()
        assertFalse(page.current());assertFalse(cursor.current());assertTrue(page.entries.none {it.current()})
        assertThrows(IllegalStateException::class.java) {f.access.page(cursor) {}}
        change.close()
        assertFalse(page.current());assertFalse(cursor.current());assertThrows(IllegalStateException::class.java) {f.access.page(cursor) {}}
        assertEquals(before,f.fingerprint());assertTrue(f.access.page(null) {}.current())
        assertThrows(CancellationException::class.java) {f.access.page(null) {throw CancellationException()}}
    }
    @Test fun oldAndFutureDatabaseVersionsAreReadOnlyAndNeverMigratedByThePicker()=scenario {f->
        f.warm();f.registry.close()
        SQLiteDatabase.openDatabase(f.database.path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("DROP TABLE original_pdf_pages");db.execSQL("DROP TABLE original_pdf_documents");db.version=2
        }
        val before=f.fingerprint();val old=f.access.page(null) {}
        assertTrue(old.supported);assertTrue(old.entries.isEmpty());assertEquals(before,f.fingerprint())
        SQLiteDatabase.openDatabase(f.database.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version=4}
        val futureBefore=f.fingerprint();val future=f.access.page(null) {}
        assertFalse(future.supported);assertTrue(future.entries.isEmpty());assertEquals(futureBefore,f.fingerprint())
        SQLiteDatabase.openDatabase(f.database.path,null,SQLiteDatabase.OPEN_READONLY).use {assertEquals(4,it.version)}
    }
}
