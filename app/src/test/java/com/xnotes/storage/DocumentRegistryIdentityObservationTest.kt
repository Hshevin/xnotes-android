package com.xnotes.storage

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteException
import com.xnotes.mindmap.MapBacklinkLibraryTest
import com.xnotes.mindmap.MindMapDocument
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
    private fun scenario(block:(MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario {f->block(f)}
    @Test fun equalRegistrationAndNameRefreshKeepTicketsButSecondLocationsAndAvailabilityAbaRetireThemAcrossInstances()=scenario {f->
        val doc=MindMapDocument();val file=f.graph("registered.xmap",doc);val uri=f.uri(file).toString()
        val one=f.storage.registry
        one.registerMapObserved(doc.id,file.name,uri)
        DocumentRegistry(f.context).use {other->
            val stable=one.identityObservation(doc.id)
            other.registerMapObserved(doc.id,"Updated display name only",uri);assertTrue(stable.current())
            val duplicate=f.graph("second.xmap",doc);val second=f.uri(duplicate).toString()
            other.registerMapObserved(doc.id,duplicate.name,second);assertFalse(stable.current())
            val beforeUnavailable=one.identityObservation(doc.id)
            other.markUnavailable(second);assertFalse(beforeUnavailable.current())
            val unavailable=one.identityObservation(doc.id)
            other.markUnavailable(second);assertTrue(unavailable.current())
            other.registerMapObserved(doc.id,duplicate.name,second);assertFalse(unavailable.current())
            assertFalse(stable.current());assertTrue(one.identityObservation(doc.id).current())
        }
    }
    @Test fun aNewAliasAndAPageContractAreStructuralEvenWhenTheActualFileAndUuidRemainTheSame()=scenario {f->
        val doc=MindMapDocument();val file=f.graph("aliased.xmap",doc)
        val original=android.net.Uri.fromFile(file).toString()
        val alias=android.net.Uri.fromFile(File(file.parentFile,"./${file.name}")).toString()
        val registry=f.storage.registry
        registry.registerMapObserved(doc.id,file.name,original)
        val beforeAlias=registry.identityObservation(doc.id)
        registry.registerMapObserved(doc.id,file.name,alias);assertFalse(beforeAlias.current())
        val beforePages=registry.identityObservation(doc.id)
        registry.register(doc.id,"xmap",file.name,alias,pageReferences=true);assertFalse(beforePages.current())
        val stable=registry.identityObservation(doc.id)
        registry.register(doc.id,"xmap",file.name,alias,pageReferences=true);assertTrue(stable.current())
    }
    @Test fun reassignmentAndDestinationReplacementRetireAllAffectedIdentities()=scenario {f->
        val one=MindMapDocument();val two=MindMapDocument();val a=f.graph("one.xmap",one);val b=f.graph("two.xmap",two)
        val registry=f.storage.registry;val uriA=f.uri(a).toString();val uriB=f.uri(b).toString()
        registry.registerMapObserved(one.id,a.name,uriA);registry.registerMapObserved(two.id,b.name,uriB)
        val beforeA=registry.identityObservation(one.id);val beforeB=registry.identityObservation(two.id)
        registry.moved(uriA,uriB);assertFalse(beforeA.current());assertFalse(beforeB.current())
        val same=registry.identityObservation(one.id);registry.moved(uriB,uriB);assertTrue(same.current())
        val third=UUID.randomUUID().toString();val old=registry.identityObservation(one.id);val fresh=registry.identityObservation(third)
        registry.register(third,"xmap","new owner",uriB)
        assertFalse(old.current());assertFalse(fresh.current());assertEquals(third,registry.idAt(uriB))
    }
    @Test fun failedFirstStructuralSqlStillRetiresTheOldTicketAndDoesNotLeaveMutationBusyAfterRollback()=scenario {f->
        val doc=MindMapDocument();val a=f.graph("original.xmap",doc);val b=f.graph("blocked.xmap",doc)
        val registry=f.storage.registry;val original=f.uri(a).toString();val blocked=f.uri(b).toString()
        registry.registerMapObserved(doc.id,a.name,original)
        val before=registry.identityObservation(doc.id)
        registry.writableDatabase.execSQL("CREATE TRIGGER reject_identity_position BEFORE INSERT ON locations "+
            "WHEN NEW.uri=${DatabaseUtils.sqlEscapeString(blocked)} BEGIN SELECT RAISE(ABORT,'controlled rollback'); END")
        try {
            assertThrows(SQLiteException::class.java) {registry.registerMapObserved(doc.id,b.name,blocked)}
            assertFalse(before.current()) // Starting only after SQL would miss the failed INSERT.
            assertEquals(listOf(original),registry.locations(doc.id));assertTrue(registry.identityObservation(doc.id).current())
        } finally {registry.writableDatabase.execSQL("DROP TRIGGER reject_identity_position")}
    }
    @Test fun anInFlightMutationRejectsNewTicketsAndRetiresOldOnesAcrossCanonicalDatabaseAliases()=scenario {f->
        val path=f.storage.registry.databaseName
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
