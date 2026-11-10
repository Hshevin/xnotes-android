package com.xnotes.mindmap

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.xnotes.storage.DocumentRegistry
import com.xnotes.storage.OriginalPdfCatalog
import java.io.File
import java.util.Collections
import java.util.UUID

/** Display-only scalar pages, never Catalog.list()/all permanent-page arrays or source authority. */
internal class OriginalSourcePickerAccess(private val registry:DocumentRegistry) {
    private val file=File(registry.databaseName)
    private val key=file.canonicalPath
    private val catalog=OriginalPdfCatalog(registry)
    fun page(cursor:OriginalPickerCursor?,check:()->Unit):OriginalPickerPage {
        require(cursor==null || cursor.databaseKey==key)
        val domain=if(cursor==null) catalog.identityDomains(emptySet(),check) else null
        val epoch=catalog.networkEpoch.value
        fun valid()=cursor?.current() ?: (domain?.current()==true && !epoch.busy && catalog.networkEpoch.value==epoch)
        fun active() {check();kotlin.check(valid())}
        active()
        if(domain?.supported==false) return OriginalPickerPage(emptyList(),null,false,::valid)
        if(!file.exists()) return OriginalPickerPage(emptyList(),null,true,::valid)
        var db:SQLiteDatabase?=null
        try {
            db=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            if(db.version !in 1..3) return OriginalPickerPage(emptyList(),null,false,::valid)
            val exists=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='original_pdf_documents'",null).use {it.moveToFirst()}
            if(!exists) return OriginalPickerPage(emptyList(),null,db.version<3,::valid)
            val after=if(cursor==null) "" else " WHERE rowid>?"
            val sql="SELECT rowid,${textColumn("id",36,true)},${textColumn("title",16384,chars=4096,allowNul=true)},"+
                "${intColumn("deleted")},${intColumn("row_schema")},${intColumn("generation")},${textColumn("resource_sha",64,true)} "+
                "FROM original_pdf_documents$after ORDER BY rowid LIMIT 101"
            val entries=mutableListOf<OriginalPickerEntry>();var last:Long?=null;var more=false
            db.rawQuery(sql,cursor?.let {arrayOf(it.rowId.toString())}).use {c->while(c.moveToNext()) {
                active();if(entries.size==100) {more=true;break}
                val rowId=integer(c,0) ?: error("Invalid catalog cursor")
                last=rowId
                val id=text(c,1)?.takeIf(::canonical);val title=text(c,2)?.takeIf {it.length<=4096}
                val deleted=integer(c,3)?.takeIf {it==0L || it==1L};val schema=integer(c,4)
                val generation=integer(c,5)?.takeIf {it>0};val sha=text(c,6)?.takeIf {it.matches(SHA)}
                val state=when {
                    schema!=null && schema>1->OriginalPickerState.UNSUPPORTED
                    schema!=1L || id==null || title==null || deleted==null || generation==null || sha==null->OriginalPickerState.CORRUPT
                    deleted==1L->OriginalPickerState.ROW_DELETED
                    else->OriginalPickerState.ROW_AVAILABLE
                }
                entries+=OriginalPickerEntry(id,title,deleted?.let {it==1L},state,generation,sha,::valid)
            }}
            active()
            return OriginalPickerPage(Collections.unmodifiableList(entries),
                if(more && last!=null) OriginalPickerCursor(key,requireNotNull(last),::valid) else null,true,::valid)
        } catch(error:java.util.concurrent.CancellationException) {throw error}
        catch(_:Exception) {active();return OriginalPickerPage(emptyList(),null,false,::valid)}
        finally {db?.close()}
    }
    private companion object {
        val SHA=Regex("[0-9a-f]{64}")
        fun canonical(id:String)=runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)
        fun integer(c:Cursor,i:Int):Long?=if(c.getType(i)==Cursor.FIELD_TYPE_INTEGER) c.getLong(i) else null
        fun text(c:Cursor,i:Int):String?=if(c.getType(i)==Cursor.FIELD_TYPE_STRING) c.getString(i) else null
        fun intColumn(column:String)="CASE WHEN typeof($column)='integer' THEN $column ELSE NULL END"
        fun textColumn(column:String,bytes:Int,exact:Boolean=false,chars:Int?=null,allowNul:Boolean=false):String {
            val size=if(exact) "=$bytes" else "<=$bytes"
            val length=chars?.let {" AND length($column)<=$it"}.orEmpty()
            val nul=if(allowNul) "" else " AND instr($column,char(0))=0"
            return "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB))$size$length$nul THEN $column ELSE NULL END"
        }
    }
}
