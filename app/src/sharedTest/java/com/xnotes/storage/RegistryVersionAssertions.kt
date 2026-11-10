package com.xnotes.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import java.util.UUID
import org.junit.Assert.*

object RegistryVersionAssertions {
    fun refusesFutureSchemaWithoutDroppingRecovery(base:Context) {
        val root=File(base.cacheDir,"registry-version-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {override fun getNoBackupFilesDir()=root}
        val id=UUID.randomUUID().toString();val uri="content://version-test/document/original"
        try {
            val futureVersion=DocumentRegistry(context).use {registry->
                registry.register(id,"xnote","Original",uri,pageReferences=true)
                registry.writableDatabase.execSQL("INSERT INTO migrations(uri,original,candidate,source_hash,document_id) VALUES(?,?,?,?,?)",
                    arrayOf(uri,"retained-original","retained-candidate","digest",id))
                val future=registry.writableDatabase.version+1
                registry.writableDatabase.version=future
                future
            }
            assertThrows(SQLiteException::class.java) {DocumentRegistry(context).use {it.writableDatabase}}
            SQLiteDatabase.openDatabase(File(root,"document-registry.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db->
                assertEquals(futureVersion,db.version)
                db.rawQuery("SELECT document_id FROM locations WHERE uri=?",arrayOf(uri)).use {assertTrue(it.moveToFirst());assertEquals(id,it.getString(0))}
                db.rawQuery("SELECT document_id FROM page_reference_contracts",null).use {assertTrue(it.moveToFirst());assertEquals(id,it.getString(0))}
                db.rawQuery("SELECT original,candidate FROM migrations WHERE uri=?",arrayOf(uri)).use {
                    assertTrue(it.moveToFirst());assertEquals("retained-original",it.getString(0));assertEquals("retained-candidate",it.getString(1))
                }
            }
        } finally {root.deleteRecursively()}
    }
}
