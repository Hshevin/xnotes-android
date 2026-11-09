package com.xnotes.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.xnotes.core.model.ReferenceAnchors
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Authoritative local identities, not an index which can be rebuilt from a hash or a page ordinal. */
internal class OriginalPdfCatalog internal constructor(
    internal val registry:DocumentRegistry,
    private val mutationHook:(String)->Unit={},
) {
    internal data class Record(val id:String,val sha256:String,val title:String,val pageCount:Int,
        val pages:List<String>,val generation:Long,val deleted:Boolean)
    internal class Observation private constructor(val record:Record,private val stamp:Stamp) {
        fun current()=stamp.current()
        internal fun belongs(state:State)=stamp.state===state
        companion object {internal fun issue(record:Record,stamp:Stamp)=Observation(record,stamp)}
    }
    internal data class Listing(val records:List<Observation>,val limited:Boolean)
    internal sealed interface Retention {
        data class Retained(val observation:Observation):Retention
        data class RestoreRequired(val observation:Observation):Retention
    }
    internal class State {val gate=Any();@Volatile var epoch=0L;@Volatile var busy=false}
    internal class Stamp internal constructor(internal val state:State,private val epoch:Long) {
        fun current()=!state.busy && state.epoch==epoch && !state.busy
    }
    private val key=java.io.File(registry.databaseName).canonicalPath
    private val state=states.computeIfAbsent(key) {State()}
    internal fun stamp():Stamp=synchronized(state.gate) {check(!state.busy);Stamp(state,state.epoch)}
    private fun observation(record:Record)=Observation.issue(record,Stamp(state,state.epoch))
    fun observe(id:String):Observation?=synchronized(state.gate) {
        uuid(id);check(!state.busy);read(registry.readableDatabase,id)?.let(::observation)
    }
    /** Corrupt/future rows fail the listing explicitly; they are never omitted as if absent. */
    fun list(limit:Int=10000):Listing=synchronized(state.gate) {
        require(limit in 1..10000);check(!state.busy)
        val ids=registry.readableDatabase.rawQuery("SELECT id FROM original_pdf_documents ORDER BY title,id LIMIT ?",arrayOf((limit+1).toString()))
            .use {c->buildList {while(c.moveToNext()) add(c.getString(0))}}
        Listing(ids.take(limit).map {observation(requireNotNull(read(registry.readableDatabase,it)))},ids.size>limit)
    }
    private fun read(db:SQLiteDatabase,id:String):Record? {
        val record=db.rawQuery("SELECT id,resource_sha,title,page_count,generation,deleted,row_schema FROM original_pdf_documents WHERE id=?",arrayOf(id)).use {c->
            if(!c.moveToFirst()) return null
            check(integer(c,6)==1L) {"Unsupported original identity record"}
            val deleted=integer(c,5);check(deleted==0L || deleted==1L)
            val count=integer(c,3);require(count in 1L..ReferenceAnchors.MAX_TARGETS.toLong())
            val generation=integer(c,4);require(generation>0)
            Record(string(c,0),string(c,1),string(c,2),count.toInt(),emptyList(),generation,deleted==1L)
        }
        uuid(record.id);require(record.sha256.matches(SHA));require(record.title.length<=4096)
        require(record.pageCount in 1..ReferenceAnchors.MAX_TARGETS && record.generation>0)
        val pages=db.rawQuery("SELECT ordinal,page_id FROM original_pdf_pages WHERE document_id=? ORDER BY ordinal",arrayOf(id)).use {c->
            buildList {
                while(c.moveToNext()) {
                    require(size<record.pageCount && integer(c,0)==size.toLong())
                    add(string(c,1).also(::uuid))
                }
            }
        }
        require(pages.size==record.pageCount && pages.toSet().size==pages.size)
        val result=record.copy(pages=Collections.unmodifiableList(pages))
        // The complete namespaced pin set is part of the record contract. A stray unknown pin
        // is evidence of corruption, not permission to clear it during delete or repair it away.
        val pins=db.rawQuery("SELECT resource_id FROM resource_refs WHERE owner=? LIMIT 2",arrayOf(owner(id))).use {c->
            buildSet {while(c.moveToNext()) add(string(c,0))}
        }
        check(pins==if(result.deleted) emptySet<String>() else setOf(result.sha256)) {"Original identity protection mismatch"}
        return result
    }
    private fun integer(cursor:Cursor,column:Int):Long {
        require(cursor.getType(column)==Cursor.FIELD_TYPE_INTEGER) {"Malformed original identity integer"}
        return cursor.getLong(column)
    }
    private fun string(cursor:Cursor,column:Int):String {
        require(cursor.getType(column)==Cursor.FIELD_TYPE_STRING) {"Malformed original identity text"}
        return cursor.getString(column)
    }
    private fun bySha(db:SQLiteDatabase,sha:String):Record?=db.rawQuery("SELECT id FROM original_pdf_documents WHERE resource_sha=?",arrayOf(sha))
        .use {if(it.moveToFirst()) read(db,it.getString(0)) else null}
    /** Candidate owns the verified immutable object lease throughout this one atomic publication. */
    fun retain(candidate:OriginalPdfAccess.Candidate):Retention {
        candidate.verify(this)
        return synchronized(state.gate) {
            check(candidate.current() && candidate.belongs(this));check(!state.busy)
            val existing=bySha(registry.readableDatabase,candidate.sha256)
            if(existing!=null) {
                require(existing.pageCount==candidate.pageCount)
                return@synchronized if(existing.deleted) Retention.RestoreRequired(observation(existing)) else Retention.Retained(observation(existing))
            }
            val record=mutate {
                val id=UUID.randomUUID().toString()
                val pages=List(candidate.pageCount) {UUID.randomUUID().toString()}
                val next=Record(id,candidate.sha256,candidate.title,candidate.pageCount,pages,1,false)
                it.execSQL("INSERT INTO original_pdf_documents(id,resource_sha,title,page_count,generation,deleted,row_schema) VALUES(?,?,?,?,1,0,1)",
                    arrayOf(id,next.sha256,next.title,next.pageCount))
                mutationHook("record")
                pages.forEachIndexed {index,pageId->it.execSQL("INSERT INTO original_pdf_pages(document_id,ordinal,page_id) VALUES(?,?,?)",arrayOf(id,index,pageId))}
                mutationHook("pages");pin(it,next);mutationHook("pin")
                requireNotNull(read(it,id))
            }
            Retention.Retained(observation(record))
        }
    }
    fun restore(expected:Observation,candidate:OriginalPdfAccess.Candidate):Observation {
        candidate.verify(this)
        return synchronized(state.gate) {
            check(expected.belongs(state) && (expected.current() || candidate.authorizesRelink(expected)) && candidate.current() && candidate.belongs(this))
            val actual=requireNotNull(read(registry.readableDatabase,expected.record.id))
            check(actual==expected.record)
            require(actual.sha256==candidate.sha256 && actual.pageCount==candidate.pageCount)
            val restored=mutate {db->
                // Re-read the entire ordinal/UUID mapping in the same transaction as re-pinning.
                check(read(db,actual.id)==actual)
                check(actual.generation<Long.MAX_VALUE)
                db.execSQL("UPDATE original_pdf_documents SET deleted=0,generation=generation+1 WHERE id=?",arrayOf(actual.id))
                mutationHook("restore-record");pin(db,actual);mutationHook("restore-pin")
                requireNotNull(read(db,actual.id))
            }
            observation(restored)
        }
    }
    /** Retire readers before an explicit same-hash resource repair begins, including failed repairs. */
    internal fun beginRelink(expected:Observation):Stamp=synchronized(state.gate) {
        check(expected.belongs(state) && expected.current() && !state.busy)
        check(read(registry.readableDatabase,expected.record.id)==expected.record)
        check(state.epoch<Long.MAX_VALUE);state.busy=true;state.epoch++;state.busy=false
        Stamp(state,state.epoch)
    }
    fun delete(expected:Observation):Observation=synchronized(state.gate) {
        check(expected.belongs(state) && expected.current())
        val actual=requireNotNull(read(registry.readableDatabase,expected.record.id))
        check(actual==expected.record && !actual.deleted && actual.generation<Long.MAX_VALUE)
        val deleted=mutate {db->
            check(read(db,actual.id)==actual)
            db.execSQL("UPDATE original_pdf_documents SET deleted=1,generation=generation+1 WHERE id=?",arrayOf(actual.id))
            mutationHook("delete-record")
            db.execSQL("DELETE FROM resource_refs WHERE owner=?",arrayOf(owner(actual.id)))
            // Start the existing delayed-collection grace period when this last owner releases it.
            db.execSQL("UPDATE resources SET created=? WHERE id=? AND NOT EXISTS(SELECT 1 FROM resource_refs WHERE resource_id=?)",
                arrayOf(System.currentTimeMillis(),actual.sha256,actual.sha256))
            mutationHook("delete-pin")
            requireNotNull(read(db,actual.id))
        }
        observation(deleted)
    }
    private fun pin(db:SQLiteDatabase,record:Record) {
        val exists=db.rawQuery("SELECT digest FROM resources WHERE id=?",arrayOf(record.sha256)).use {it.moveToFirst() && it.getString(0)==record.sha256}
        check(exists)
        db.execSQL("INSERT OR IGNORE INTO resource_refs(owner,resource_id) VALUES(?,?)",arrayOf(owner(record.id),record.sha256))
    }
    private fun <T> mutate(block:(SQLiteDatabase)->T):T {
        check(!state.busy && state.epoch<Long.MAX_VALUE)
        // Retire before SQLite commits, including failed/rolled-back attempts. No post-commit window.
        state.busy=true;state.epoch++
        return try {registry.originalPdfTransaction(block)} finally {state.busy=false}
    }
    internal fun sameDatabase(other:OriginalPdfCatalog)=state===other.state
    companion object {
        private val states=ConcurrentHashMap<String,State>()
        private val SHA=Regex("[0-9a-f]{64}")
        private fun uuid(id:String) {require(UUID.fromString(id).toString()==id)}
        internal fun owner(id:String)="original-pdf:$id"
        fun get(context:Context)=OriginalPdfCatalog(NotebookStorage.get(context).registry)
    }
}
