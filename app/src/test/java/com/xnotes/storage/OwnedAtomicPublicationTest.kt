package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.platform.AppStorageDocumentsProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OwnedAtomicPublicationTest {
    private fun scenario(block:(MigrationPublication,OwnedRecoveryTarget,File,File,File)->Unit,beforeReplace:(File)->Unit={}) {
        val root=Files.createTempDirectory("owned-atomic-").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getExternalFilesDir(type:String?)=File(root,"external").apply {mkdirs()}
        }
        val folder=AppStorageDocumentsProvider.rootDir(context)
        val current=File(folder,"note.xnote").apply {writeText("original")}
        val next=File(root,"next").apply {writeText("candidate")}
        val dir=File(root,"journal");val journal=MigrationPublication(dir);journal.stage(current,next)
        val target=OwnedRecoveryTarget(context,"root/note.xnote") {beforeReplace(current)}
        try {block(journal,target,current,next,dir)} finally {root.deleteRecursively()}
    }
    @Test fun ownedReplacementDoesNotOverwriteAnExternalVersionArrivingDuringStaging() = scenario({journal,target,current,_,dir->
        val error=assertThrows(MigrationPublication.RecoveryRequired::class.java) {journal.publish(target)}
        assertEquals(StorageMsg.MIGRATION_INTERRUPTED,error.reason);assertEquals("external B",current.readText())
        assertTrue(journal.pending);assertEquals("original",File(dir,"original.zip").readText())
        assertEquals("candidate",File(dir,"candidate.zip").readText())
        assertEquals(listOf("note.xnote"),current.parentFile!!.list()!!.toList())
    },beforeReplace={it.writeText("external B")})
    @Test fun stagingFailureKeepsTheOriginalAndJournalAndCanBeRetried() {
        var fail=true
        scenario({journal,target,current,_,_->
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {journal.publish(target)}
            assertEquals("original",current.readText());assertTrue(journal.pending)
            fail=false;journal.publish(target)
            assertEquals("candidate",current.readText());assertFalse(journal.pending)
            assertEquals(listOf("note.xnote"),current.parentFile!!.list()!!.toList())
        },beforeReplace={if(fail) throw IOException("staging interrupted")})
    }
    @Test fun explicitMissingRestoreRejectsAFileCreatedWhileItsOriginalIsStaged() = scenario({journal,target,current,_,_->
        assertTrue(current.delete());assertTrue(target.confirmedMissing())
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {journal.restore(target)}
        assertEquals("new B",current.readText());assertTrue(journal.pending)
    },beforeReplace={it.writeText("new B")})
}
