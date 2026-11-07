package com.xnotes.pen

import com.xnotes.core.model.Document
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.WorkingSessionCache
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Private Android filesystem/JSON/native-codec boundary; compiling this does not execute it. */
class WorkingSessionCacheAndroidTest {
    @Test fun nativeSnapshotAndPlatformNullMetadataRemainPairedThroughAtomicPublication() {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"working-session-${UUID.randomUUID()}")
        val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val cache=WorkingSessionCache(root,validateBody={file,kind,id->
            val info=BundleReferenceInfo.inspect(file,true)
            require(info.format==if(kind==WorkingSessionCache.Kind.NOTE) "xnote" else "xcanvas")
            require(info.header.identity?.documentId==id)
        })
        try {
            val doc=Document.blank(2).apply {enableReferenceAnchors()};val snapshot=doc.snapshot();val owner=Any()
            val request=cache.reserve(owner,WorkingSessionCache.Kind.NOTE,doc.identity.documentId,1,JSONObject().put("path",JSONObject.NULL).put("dirty",true))
            assertTrue(cache.publish(request) {codec.write(snapshot,it)}.published)
            cache.load()!!.use {read->
                assertSame(JSONObject.NULL,JSONObject(read.metadataJson).opt("path"))
                val restored=read.body.inputStream().use {codec.read(it)}
                assertEquals(doc.identity,restored.identity)
                assertEquals(doc.pages.map {it.referenceIdentity.id},restored.pages.map {it.referenceIdentity.id})
            }
            val view=cache.reserve(owner,WorkingSessionCache.Kind.NOTE,doc.identity.documentId,1,JSONObject().put("zoom",1.5))
            assertFalse(view.requiresBody);assertTrue(cache.publish(view) {fail("must reuse body")}.published)
            val lease=cache.load()!!;assertTrue(cache.publishClear(cache.reserveClear()).published)
            assertTrue(lease.body.isFile);lease.close();assertFalse(lease.body.exists());assertNull(cache.load())
        } finally {root.deleteRecursively()}
    }
    @Test fun alteredHeadCandidateCannotDestroyTheLastCompleteAndroidCache() {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"working-session-failure-${UUID.randomUUID()}")
        var alter=false
        val cache=WorkingSessionCache(root,validateBody={file,_,_->require(file.readText().startsWith("valid"))},onEvent={event,file->
            if(alter && event==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) file.writeText("invalid head")
        })
        try {
            val id=UUID.randomUUID().toString()
            fun request()=cache.reserve(Any(),WorkingSessionCache.Kind.NOTE,id,0,JSONObject())
            cache.publish(request()) {it.write("valid old".toByteArray())}
            alter=true
            try {cache.publish(request()) {it.write("valid new".toByteArray())};fail("must reject")} catch(_:IOException) {}
            alter=false;cache.load()!!.use {assertEquals("valid old",it.body.readText())}
        } finally {root.deleteRecursively()}
    }
}
