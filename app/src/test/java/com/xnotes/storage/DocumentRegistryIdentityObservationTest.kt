package com.xnotes.storage

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteException
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual registry producers and rollback; tickets are shared across independent helper instances. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class DocumentRegistryIdentityObservationTest {
    private fun scenario(block:(DocumentRegistry)->Unit) {DocumentRegistry(RuntimeEnvironment.getApplication()).use(block)}
    @Test fun equalRegistrationAndNameRefreshKeepTicketsButSecondLocationsAndAvailabilityAbaRetireThemAcrossInstances()=scenario {registry->
        val doc=UUID.randomUUID().toString();val file=File(RuntimeEnvironment.getApplication().cacheDir,"registered.xnote");val uri=android.net.Uri.fromFile(file).toString()
        val one=registry
        one.register(doc,"xnote",file.name,uri)
        DocumentRegistry(RuntimeEnvironment.getApplication()).use {other->
            val stable=one.identityObservation(doc)
            other.register(doc,"xnote","Updated display name only",uri);assertTrue(stable.current())
            val duplicate=File(RuntimeEnvironment.getApplication().cacheDir,"second.xnote");val second=android.net.Uri.fromFile(duplicate).toString()
            other.register(doc,"xnote",duplicate.name,second);assertFalse(stable.current())
            val beforeUnavailable=one.identityObservation(doc)
            other.markUnavailable(second);assertFalse(beforeUnavailable.current())
            val unavailable=one.identityObservation(doc)
            other.markUnavailable(second);assertTrue(unavailable.current())
            other.register(doc,"xnote",duplicate.name,second);assertFalse(unavailable.current())
            assertFalse(stable.current());assertTrue(one.identityObservation(doc).current())
        }
    }
    @Test fun aNewAliasAndAPageContractAreStructuralEvenWhenTheActualFileAndUuidRemainTheSame()=scenario {registry->
        val doc=UUID.randomUUID().toString();val file=File(RuntimeEnvironment.getApplication().cacheDir,"aliased.xnote")
        val original=android.net.Uri.fromFile(file).toString()
        val alias=android.net.Uri.fromFile(File(file.parentFile,"./${file.name}")).toString()
        registry.register(doc,"xnote",file.name,original)
        val beforeAlias=registry.identityObservation(doc)
        registry.register(doc,"xnote",file.name,alias);assertFalse(beforeAlias.current())
        val beforePages=registry.identityObservation(doc)
        registry.register(doc,"xnote",file.name,alias,pageReferences=true);assertFalse(beforePages.current())
        val stable=registry.identityObservation(doc)
        registry.register(doc,"xnote",file.name,alias,pageReferences=true);assertTrue(stable.current())
    }
    @Test fun reassignmentAndDestinationReplacementRetireAllAffectedIdentities()=scenario {registry->
        val one=UUID.randomUUID().toString();val two=UUID.randomUUID().toString();val a=File(RuntimeEnvironment.getApplication().cacheDir,"one.xnote");val b=File(RuntimeEnvironment.getApplication().cacheDir,"two.xnote")
        val uriA=android.net.Uri.fromFile(a).toString();val uriB=android.net.Uri.fromFile(b).toString()
        registry.register(one,"xnote",a.name,uriA);registry.register(two,"xnote",b.name,uriB)
        val beforeA=registry.identityObservation(one);val beforeB=registry.identityObservation(two)
        registry.moved(uriA,uriB);assertFalse(beforeA.current());assertFalse(beforeB.current())
        val same=registry.identityObservation(one);registry.moved(uriB,uriB);assertTrue(same.current())
        val third=UUID.randomUUID().toString();val old=registry.identityObservation(one);val fresh=registry.identityObservation(third)
        registry.register(third,"xnote","new owner",uriB)
        assertFalse(old.current());assertFalse(fresh.current());assertEquals(third,registry.idAt(uriB))
    }
    @Test fun failedFirstStructuralSqlStillRetiresTheOldTicketAndDoesNotLeaveMutationBusyAfterRollback()=scenario {registry->
        val doc=UUID.randomUUID().toString();val a=File(RuntimeEnvironment.getApplication().cacheDir,"original.xnote");val b=File(RuntimeEnvironment.getApplication().cacheDir,"blocked.xnote")
        val original=android.net.Uri.fromFile(a).toString();val blocked=android.net.Uri.fromFile(b).toString()
        registry.register(doc,"xnote",a.name,original)
        val before=registry.identityObservation(doc)
        registry.writableDatabase.execSQL("CREATE TRIGGER reject_identity_position BEFORE INSERT ON locations "+
            "WHEN NEW.uri=${DatabaseUtils.sqlEscapeString(blocked)} BEGIN SELECT RAISE(ABORT,'controlled rollback'); END")
        try {
            assertThrows(SQLiteException::class.java) {registry.register(doc,"xnote",b.name,blocked)}
            assertFalse(before.current()) // Starting only after SQL would miss the failed INSERT.
            assertEquals(listOf(original),registry.locations(doc));assertTrue(registry.identityObservation(doc).current())
        } finally {registry.writableDatabase.execSQL("DROP TRIGGER reject_identity_position")}
    }
    @Test fun anInFlightMutationRejectsNewTicketsAndRetiresOldOnesAcrossCanonicalDatabaseAliases()=scenario {registry->
        val path=registry.databaseName
        val one=DocumentRegistryIdentityEpoch.forDatabase(path)
        val two=DocumentRegistryIdentityEpoch.forDatabase(File(File(path).parentFile,"./${File(path).name}").path)
        val id=UUID.randomUUID().toString();val other=UUID.randomUUID().toString()
        val old=one.observe(id);val untouched=one.observe(other)
        val outer=two.begin(setOf(id));val nested=one.begin(setOf(id))
        assertFalse(old.current());assertTrue(untouched.current())
        assertThrows(IllegalStateException::class.java) {one.observe(id)}
        outer.close();assertThrows(IllegalStateException::class.java) {two.observe(id)}
        nested.close();nested.close();outer.close()
        assertFalse(old.current());assertTrue(one.observe(id).current());assertTrue(untouched.current())
    }
}
