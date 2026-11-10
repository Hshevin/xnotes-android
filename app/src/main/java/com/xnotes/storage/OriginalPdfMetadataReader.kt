package com.xnotes.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.xnotes.core.model.ReferenceAnchors
import com.xnotes.storage.OriginalPdfCatalog.*
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException

/** Independent read-only connection: no helper bootstrap, migration, PDF resolver, or body reader. */
internal class OriginalPdfMetadataReader(path:String,private val epoch:OriginalPdfNetworkEpoch) {
    private val file=File(path)
    private val resourceRoot=File(file.parentFile,"ai-pdfs")
    private data class Capability(val supported:Boolean,val catalog:Boolean,val tables:Set<String>)
    private data class Raw(val id:String,val sha:String,val title:String,val count:Int,val generation:Long,val deleted:Boolean)
    private data class Row(val id:String?,val state:MetadataState,val raw:Raw?=null)
    private data class Pages(val state:MetadataState,val first:String?=null,val scanned:Int=0)
    private data class Resource(val valid:Boolean,val presence:ResourcePresence)
    private fun open():SQLiteDatabase? {
        if(!file.exists()) return null
        check(file.isFile)
        return SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }
    private fun capability(db:SQLiteDatabase):Capability {
        val tables=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('documents','locations','page_reference_contracts','original_pdf_documents','original_pdf_pages','resources','resource_refs')",null).use {c->buildSet {while(c.moveToNext()) add(c.getString(0))}}
        val version=db.version
        val original="original_pdf_documents" in tables
        val native=setOf("documents","locations").all {it in tables}
        val supported=when(version) {
            1,2->native && !original
            3->native && setOf("original_pdf_documents","original_pdf_pages","resources","resource_refs","page_reference_contracts").all {it in tables}
            else->false
        }
        return Capability(supported,original,tables)
    }
    private fun nativeIds(db:SQLiteDatabase,cap:Capability,ids:Set<String>,check:()->Unit):Set<String> {
        val found=linkedSetOf<String>()
        val tables=listOf("documents" to "id","locations" to "document_id","page_reference_contracts" to "document_id")
            .filter {it.first in cap.tables}
        if(tables.isEmpty()) return found
        for(chunk in ids.toList().chunked(200)) {
            check()
            val values=chunk.joinToString(",") {"(?)"}
            val exists=tables.joinToString(" OR ") {(table,column)->"EXISTS(SELECT 1 FROM $table WHERE $column=requested.wanted)"}
            // Return only bounded input IDs. Raw stored identity text is occupancy evidence,
            // not something a corrupt/future schema may force into a CursorWindow.
            db.rawQuery("WITH requested(wanted) AS (VALUES $values) SELECT wanted FROM requested WHERE $exists",chunk.toTypedArray()).use {c->
                while(c.moveToNext()) {check();found+=text(c,0)}
            }
        }
        return found
    }
    private fun reserved(db:SQLiteDatabase,cap:Capability,ids:Set<String>,check:()->Unit):Set<String> {
        if(!cap.catalog) return emptySet()
        val found=linkedSetOf<String>()
        for(chunk in ids.toList().chunked(200)) {
            check();val values=chunk.joinToString(",") {"(?)"}
            // Presence is intentionally independent of row schema and never projects raw TEXT.
            db.rawQuery("WITH requested(wanted) AS (VALUES $values) SELECT wanted FROM requested "+
                "WHERE EXISTS(SELECT 1 FROM original_pdf_documents WHERE id=requested.wanted)",chunk.toTypedArray()).use {c->
                while(c.moveToNext()) {check();found+=text(c,0)}
            }
        }
        return found
    }
    fun domains(ids:Set<String>,checkActive:()->Unit):IdentityDomainsBatch {
        validateIds(ids);val valid=epoch.stamp()
        fun check() {checkActive();kotlin.check(valid())}
        check()
        try {
            val db=open() ?: return IdentityDomainsBatch(emptySet(),emptySet(),true,false,valid)
            db.use {
                val cap=capability(db);val originals=reserved(db,cap,ids,::check);val native=nativeIds(db,cap,ids,::check)
                check();return IdentityDomainsBatch(immutable(originals),immutable(native),cap.supported,!cap.supported,valid)
            }
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {check();return IdentityDomainsBatch(emptySet(),emptySet(),false,true,valid)}
    }
    fun metadata(ids:Set<String>,targets:Set<MetadataTarget>,orphanLimit:Int,pageBudget:Int,checkActive:()->Unit):MetadataBatch {
        validateIds(ids);require(targets.size<=10000 && orphanLimit in 0..250 && pageBudget in 1..ReferenceAnchors.MAX_TARGETS)
        targets.forEach {require(canonical(it.documentId) && canonical(it.pageId) && it.sha256.matches(SHA) && it.pageIndex in 0 until ReferenceAnchors.MAX_TARGETS)}
        val valid=epoch.stamp()
        fun check() {checkActive();kotlin.check(valid())}
        fun empty(state:MetadataState,supported:Boolean)=MetadataBatch(immutable(ids.associateWith {Metadata(null,state)}),emptyList(),
            immutable(targets.associateWith {TargetCheck.UNVERIFIABLE}),if(supported) emptyList() else listOf(MetadataIssue(null,state)),false,
            emptySet(),emptySet(),supported,!supported,valid)
        check()
        try {
            val db=open() ?: return empty(MetadataState.ABSENT,true)
            db.use {
                val cap=capability(db)
                val originalIds=reserved(db,cap,ids,::check).toMutableSet()
                if(!cap.supported) {
                    val native=nativeIds(db,cap,ids,::check);check()
                    return MetadataBatch(immutable(ids.associateWith {Metadata(null,MetadataState.UNSUPPORTED,nativeIdentityPresent=it in native)}),emptyList(),
                        immutable(targets.associateWith {TargetCheck.UNVERIFIABLE}),listOf(MetadataIssue(null,MetadataState.UNSUPPORTED)),false,
                        immutable(originalIds),immutable(native),false,true,valid)
                }
                if(!cap.catalog) {
                    val native=nativeIds(db,cap,ids,::check);check()
                    return MetadataBatch(immutable(ids.associateWith {Metadata(null,MetadataState.ABSENT,nativeIdentityPresent=it in native)}),emptyList(),
                        immutable(targets.associateWith {TargetCheck.UNVERIFIABLE}),emptyList(),false,emptySet(),immutable(native),true,false,valid)
                }
                val rows=linkedMapOf<String,Row>();val orphanRows=mutableListOf<Row>();val issues=mutableListOf<MetadataIssue>()
                var limited=false
                fun issue(row:Row) {if(issues.size<128) issues+=MetadataIssue(row.id,row.state) else limited=true}
                for(chunk in ids.toList().chunked(200)) {
                    check();val marks=chunk.joinToString(",") {"?"}
                    db.rawQuery("SELECT $COLUMNS FROM original_pdf_documents WHERE id IN ($marks) ORDER BY original_pdf_documents.id",chunk.toTypedArray()).use {c->
                        while(c.moveToNext()) {check();val row=row(c);if(row.id!=null) rows[row.id]=row else issue(row)}
                    }
                }
                if(orphanLimit>0) {
                    // Inspect at most requested IDs plus the independent staging allowance and one probe.
                    // Known schema-1 tombstones are not orphan candidates; referenced tombstones are read above.
                    val sql="SELECT $COLUMNS FROM original_pdf_documents WHERE NOT(typeof(row_schema)='integer' AND row_schema=1 AND typeof(deleted)='integer' AND deleted=1) ORDER BY original_pdf_documents.id LIMIT ?"
                    db.rawQuery(sql,arrayOf((ids.size+orphanLimit+1).toString())).use {c->while(c.moveToNext()) {
                        check();val next=row(c);if(next.id in ids) continue
                        if(orphanRows.size>=orphanLimit) {limited=true;break}
                        orphanRows+=next
                        next.id?.let {originalIds+=it}
                    }}
                }
                val allIds=ids+orphanRows.mapNotNull {it.id}
                val native=nativeIds(db,cap,allIds,::check)
                var remaining=pageBudget
                fun fact(value:Row):Metadata {
                    check()
                    if(value.state!=MetadataState.VALID) return Metadata(null,value.state,nativeIdentityPresent=value.id in native)
                    val raw=requireNotNull(value.raw)
                    if(!pins(db,raw)) return Metadata(null,MetadataState.CORRUPT,nativeIdentityPresent=raw.id in native)
                    val resource=resource(db,raw)
                    if(!resource.valid) return Metadata(null,MetadataState.CORRUPT,nativeIdentityPresent=raw.id in native)
                    if(raw.count>remaining) {limited=true;return Metadata(null,MetadataState.LIMITED,nativeIdentityPresent=raw.id in native)}
                    val pages=pages(db,raw,remaining,::check)
                    remaining=(remaining-pages.scanned).coerceAtLeast(0)
                    if(pages.state!=MetadataState.VALID) {
                        if(pages.state==MetadataState.LIMITED) limited=true
                        return Metadata(null,pages.state,nativeIdentityPresent=raw.id in native)
                    }
                    val header=MetadataHeader(raw.id,raw.sha,raw.title,raw.count,requireNotNull(pages.first),raw.generation,raw.deleted)
                    return Metadata(header,MetadataState.VALID,resource.presence,raw.id in native)
                }
                fun safeFact(row:Row):Metadata=try {fact(row)}
                    catch(error:CancellationException) {throw error}
                    catch(_:Exception) {check();Metadata(null,MetadataState.CORRUPT,nativeIdentityPresent=row.id in native)}
                val facts=linkedMapOf<String,Metadata>()
                ids.sorted().forEach {id->facts[id]=rows[id]?.let(::safeFact) ?: Metadata(null,MetadataState.ABSENT,nativeIdentityPresent=id in native)}
                val orphans=mutableListOf<Metadata>()
                for(row in orphanRows) {
                    val value=safeFact(row)
                    if(value.state==MetadataState.VALID && value.header?.deleted==false) orphans+=value
                    else issue(Row(row.id,value.state))
                }
                val pointChecks=linkedMapOf<MetadataTarget,TargetCheck>()
                for(target in targets) {
                    check();val fact=facts[target.documentId]
                    pointChecks[target]=when {
                        fact==null->{limited=true;TargetCheck.LIMITED}
                        fact.state==MetadataState.LIMITED->TargetCheck.LIMITED
                        fact.state!=MetadataState.VALID || fact.nativeIdentityPresent->TargetCheck.UNVERIFIABLE
                        else->point(db,requireNotNull(fact.header),target)
                    }
                }
                check()
                return MetadataBatch(immutable(facts),immutable(orphans),immutable(pointChecks),immutable(issues),limited,
                    immutable(originalIds),immutable(native),true,false,valid)
            }
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {check();return empty(MetadataState.CORRUPT,false)}
    }
    private fun row(c:Cursor):Row {
        var id:String?=null
        return try {
            id=text(c,0).also {require(canonical(it))}
            val schema=integer(c,6)
            if(schema>1) return Row(id,MetadataState.UNSUPPORTED)
            require(schema==1L)
            val sha=text(c,1).also {require(it.matches(SHA))};val title=text(c,2).also {require(it.length<=4096)}
            val count=integer(c,3).also {require(it in 1L..ReferenceAnchors.MAX_TARGETS.toLong())}
            val generation=integer(c,4).also {require(it>0)};val deleted=integer(c,5).also {require(it==0L || it==1L)}
            Row(id,MetadataState.VALID,Raw(id,sha,title,count.toInt(),generation,deleted==1L))
        } catch(_:Exception) {Row(id?.takeIf(::canonical),MetadataState.CORRUPT)}
    }
    private fun pins(db:SQLiteDatabase,raw:Raw):Boolean=db.rawQuery("SELECT ${boundedText("resource_id",64,exact=true)} FROM resource_refs WHERE owner=? LIMIT 2",arrayOf(OriginalPdfCatalog.owner(raw.id))).use {c->
        val pins=buildSet {while(c.moveToNext()) add(text(c,0))}
        pins==if(raw.deleted) emptySet<String>() else setOf(raw.sha)
    }
    private fun resource(db:SQLiteDatabase,raw:Raw):Resource {
        val expected=File(resourceRoot,"${raw.sha}.pdf")
        val size=db.rawQuery("SELECT ${boundedText("id",64,exact=true)},${boundedText("path",32768,chars=8192)},${integerColumn("size")},${boundedText("digest",64,exact=true)},${integerColumn("version")},${integerColumn("owned")} FROM resources WHERE id=?",arrayOf(raw.sha)).use {c->
            if(!c.moveToFirst()) return Resource(raw.deleted,ResourcePresence.MISSING)
            require(text(c,0)==raw.sha && text(c,3)==raw.sha && integer(c,4)==1L && integer(c,5)==1L)
            val path=text(c,1).also {require(it.length<=8192)};val length=integer(c,2).also {require(it>=0)}
            // Reject an untrusted external spelling lexically, before any filesystem lookup on it.
            val registered=File(path)
            require(registered.isAbsolute && registered.toPath().normalize()==expected.absoluteFile.toPath().normalize())
            val root=resourceRoot.canonicalFile;val canonical=expected.canonicalFile
            require(canonical.parentFile==root)
            length
        }
        val presence=try {
            when {
                !expected.exists()->ResourcePresence.MISSING
                !expected.isFile || !expected.canRead() || expected.length()!=size->ResourcePresence.INACCESSIBLE
                else->ResourcePresence.PRESENT_HINT
            }
        } catch(_:SecurityException) {ResourcePresence.INACCESSIBLE}
        return Resource(true,presence)
    }
    private fun pages(db:SQLiteDatabase,raw:Raw,remaining:Int,check:()->Unit):Pages {
        check()
        // LIMIT is inside the aggregate input, so corrupt overlong directories cannot cause an
        // unbounded COUNT/DISTINCT scan. One extra row can prove a declared count is false.
        val cap=minOf(raw.count+1,remaining+1)
        val sql="SELECT COUNT(*),MIN(ordinal),MAX(ordinal),COUNT(DISTINCT page_id),"+
            "SUM(CASE WHEN typeof(ordinal)='integer' AND ordinal>=0 AND $UUID_SQL THEN 0 ELSE 1 END),"+
            "MAX(CASE WHEN typeof(ordinal)='integer' AND ordinal=0 AND $UUID_SQL THEN page_id ELSE NULL END) "+
            "FROM (SELECT ${integerColumn("p.ordinal")} AS ordinal,${boundedText("p.page_id",36,exact=true)} AS page_id "+
            "FROM original_pdf_pages p WHERE document_id=? ORDER BY p.ordinal LIMIT ?)"
        return db.rawQuery(sql,arrayOf(raw.id,cap.toString())).use {c->
            check();require(c.moveToFirst());val count=integer(c,0).toInt()
            if(count>raw.count) return@use Pages(MetadataState.CORRUPT,scanned=count)
            if(count==cap && cap<=raw.count) return@use Pages(MetadataState.LIMITED,scanned=count)
            val valid=count==raw.count && !c.isNull(1) && integer(c,1)==0L && integer(c,2)==raw.count.toLong()-1 &&
                integer(c,3)==count.toLong() && integer(c,4)==0L && !c.isNull(5)
            if(valid) Pages(MetadataState.VALID,text(c,5),count) else Pages(MetadataState.CORRUPT,scanned=count)
        }
    }
    private fun point(db:SQLiteDatabase,header:MetadataHeader,target:MetadataTarget):TargetCheck {
        if(header.sha256!=target.sha256 || target.pageIndex !in 0 until header.pageCount) return TargetCheck.DESCRIPTOR_MISMATCH
        return db.rawQuery("SELECT ${integerColumn("ordinal")},${boundedText("page_id",36,exact=true)} FROM original_pdf_pages WHERE document_id=? AND page_id=? LIMIT 2",arrayOf(target.documentId,target.pageId)).use {c->
            if(!c.moveToFirst()) TargetCheck.PAGE_NOT_REGISTERED
            else if(integer(c,0)!=target.pageIndex.toLong() || text(c,1)!=target.pageId || c.moveToNext()) TargetCheck.DESCRIPTOR_MISMATCH
            else TargetCheck.MATCHED
        }
    }
    private companion object {
        // Bound original SQL values before CursorWindow sees them. Invalid values become NULL;
        // they are never truncated or coerced into apparently valid metadata. Four UTF-8 bytes
        // per character preserves every title/path accepted by the existing UTF-16 length limit.
        fun boundedText(column:String,bytes:Int,exact:Boolean=false,chars:Int?=null,allowNul:Boolean=false):String {
            val length=if(exact) "=$bytes" else "<=$bytes"
            val charBound=chars?.let {" AND length($column)<=$it"}.orEmpty()
            val nulBound=if(allowNul) "" else " AND instr($column,char(0))=0"
            return "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB))$length"+
                "$charBound$nulBound THEN $column ELSE NULL END"
        }
        fun integerColumn(column:String)="CASE WHEN typeof($column)='integer' THEN $column ELSE NULL END"
        val COLUMNS=listOf(boundedText("id",36,exact=true),boundedText("resource_sha",64,exact=true),
            boundedText("title",16384,chars=4096,allowNul=true),integerColumn("page_count"),integerColumn("generation"),
            integerColumn("deleted"),integerColumn("row_schema")).joinToString(",")
        val SHA=Regex("[0-9a-f]{64}")
        // The length and four separator checks, plus 32 remaining lower-case hex digits, equal
        // UUID.fromString(value).toString()==value without materializing every page UUID in Java.
        const val UUID_SQL="typeof(page_id)='text' AND length(CAST(page_id AS BLOB))=36 AND length(page_id)=36 AND substr(page_id,9,1)='-' AND substr(page_id,14,1)='-' AND substr(page_id,19,1)='-' AND substr(page_id,24,1)='-' AND length(replace(page_id,'-',''))=32 AND replace(page_id,'-','') NOT GLOB '*[^0-9a-f]*'"
        fun canonical(value:String)=runCatching {UUID.fromString(value).toString()==value}.getOrDefault(false)
        fun validateIds(ids:Set<String>) {require(ids.size<=10000 && ids.all(::canonical))}
        fun integer(c:Cursor,i:Int):Long {require(c.getType(i)==Cursor.FIELD_TYPE_INTEGER);return c.getLong(i)}
        fun text(c:Cursor,i:Int):String {require(c.getType(i)==Cursor.FIELD_TYPE_STRING);return c.getString(i)}
        fun <T> immutable(values:Set<T>):Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
        fun <T> immutable(values:List<T>):List<T> = Collections.unmodifiableList(values.toList())
        fun <K,V> immutable(values:Map<K,V>):Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(values))
    }
}
