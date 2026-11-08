package com.xnotes.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class WorkingSessionLoadStateTest {
    private fun cache(root:File)=WorkingSessionCache(root,validateBody={body,_,_->require(body.readText()=="original")})
    @Test fun genuineAbsenceClearAndActiveAreDistinctWithoutChangingExistingNullableLoad() {
        val root=Files.createTempDirectory("session-read-state-").toFile()
        try {
            val store=cache(root)
            assertSame(WorkingSessionCache.LoadState.Absent,store.loadState());assertNull(store.load())
            assertTrue(store.publishClear(store.reserveClear()).published)
            assertSame(WorkingSessionCache.LoadState.Cleared,cache(root).loadState());assertNull(store.load())
            val request=store.reserve(Any(),WorkingSessionCache.Kind.NOTE,UUID.randomUUID().toString(),0,JSONObject())
            assertTrue(store.publish(request) {it.write("original".toByteArray())}.published)
            val active=cache(root).loadState() as WorkingSessionCache.LoadState.Active
            active.read.use {assertEquals("original",it.body.readText());assertEquals(request.documentId,it.documentId)}
            store.load()!!.use {assertEquals("original",it.body.readText())}
        } finally {root.deleteRecursively()}
    }
    @Test fun corruptFutureDirectoryAndDanglingSymlinkNeverBecomeAnAbsentHead() {
        for(case in 0..3) {
            val root=Files.createTempDirectory("session-invalid-state-").toFile();val head=File(root,"head.json")
            try {
                when(case) {
                    0->head.writeText("broken")
                    1->head.writeText("""{"version":99,"state":"cleared"}""")
                    2->assertTrue(head.mkdir())
                    else->Files.createSymbolicLink(head.toPath(),File(root,"missing-head").toPath())
                }
                assertThrows(java.io.IOException::class.java) {cache(root).loadState()}
                assertTrue(Files.exists(head.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            } finally {root.deleteRecursively()}
        }
    }
}
