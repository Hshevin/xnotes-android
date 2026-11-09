package com.xnotes.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteException
import com.xnotes.pen.PenTestRunner
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Actual Android SQLite transaction/rollback ordering; compiled separately from JVM verification. */
class DocumentRegistryIdentityObservationAndroidTest {
    @Test fun platformRegistryCommitsRollbacksAndNoOpsRetireOnlyStructuralObservationTickets() {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"identity-ticket-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(PenTestRunner.instance.targetContext) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=root
        }
        val one=DocumentRegistry(context);val two=DocumentRegistry(context)
        try {
            val id=UUID.randomUUID().toString();val other=UUID.randomUUID().toString()
            val a="content://identity-ticket/document/a";val b="content://identity-ticket/document/b"
            val c="content://identity-ticket/document/c";val blocked="content://identity-ticket/document/blocked"
            one.register(id,"xmap","Source",a);one.register(other,"xmap","Destination",b)
            val stable=one.identityObservation(id)
            two.register(id,"xmap","Name only",a);assertTrue(stable.current())
            val target=one.identityObservation(other)
            two.moved(a,b);assertFalse(stable.current());assertFalse(target.current())
            val available=one.identityObservation(id)
            two.markUnavailable(b);assertFalse(available.current())
            val absent=one.identityObservation(id)
            two.markUnavailable(b);assertTrue(absent.current())
            two.register(id,"xmap","Restored position",b);assertFalse(absent.current())
            val beforeRollback=one.identityObservation(id)
            one.writableDatabase.execSQL("CREATE TRIGGER reject_identity_position BEFORE INSERT ON locations "+
                "WHEN NEW.uri=${DatabaseUtils.sqlEscapeString(blocked)} BEGIN SELECT RAISE(ABORT,'platform controlled rollback'); END")
            try {two.register(id,"xmap","Must roll back",blocked);fail("Expected actual SQLite rollback")}
            catch(_:SQLiteException) {}
            assertFalse(beforeRollback.current());assertEquals(listOf(b),one.locations(id))
            assertTrue(two.identityObservation(id).current())
            one.writableDatabase.execSQL("DROP TRIGGER reject_identity_position")
            val beforeDuplicate=two.identityObservation(id)
            one.register(id,"xmap","Second actual position",c);assertFalse(beforeDuplicate.current())
            assertEquals(setOf(b,c),one.locations(id).toSet())
        } finally {two.close();one.close();root.deleteRecursively()}
    }
}
