package com.xnotes.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PageReferenceRegistryTest {
    private val id="00000000-0000-0000-0000-000000000001"
    private val uri="content://example/document/a"
    private fun scenario(block:(Context,File)->Unit) {
        val root=Files.createTempDirectory("reference-registry-").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {override fun getNoBackupFilesDir()=root}
        try {block(context,root)} finally {root.deleteRecursively()}
    }
    // Historical v1 schema fixture, independent of the new helper's onCreate/onUpgrade.
    private val v1=listOf(
        "CREATE TABLE documents(id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL)",
        "CREATE TABLE locations(uri TEXT PRIMARY KEY, location_key TEXT NOT NULL UNIQUE, document_id TEXT NOT NULL REFERENCES documents(id), available INTEGER NOT NULL DEFAULT 1)",
        "CREATE INDEX locations_document ON locations(document_id,available)",
        "CREATE TABLE resources(id TEXT PRIMARY KEY,name TEXT NOT NULL,path TEXT NOT NULL UNIQUE,size INTEGER NOT NULL,digest TEXT NOT NULL UNIQUE,version INTEGER NOT NULL,owned INTEGER NOT NULL,created INTEGER NOT NULL)",
        "CREATE TABLE resource_refs(owner TEXT NOT NULL,resource_id TEXT NOT NULL REFERENCES resources(id),PRIMARY KEY(owner,resource_id))",
        "CREATE INDEX refs_resource ON resource_refs(resource_id)",
        "CREATE TABLE leases(token TEXT PRIMARY KEY,resource_id TEXT NOT NULL REFERENCES resources(id),expires INTEGER NOT NULL)",
        "CREATE INDEX leases_resource ON leases(resource_id,expires)",
        "CREATE TABLE migrations(uri TEXT PRIMARY KEY,original TEXT NOT NULL,candidate TEXT NOT NULL,source_hash TEXT NOT NULL,document_id TEXT NOT NULL)"
    )
    private fun oldDatabase(root:File):Map<String,List<List<String?>>> {
        SQLiteDatabase.openOrCreateDatabase(File(root,"document-registry.db"),null).use {db->
            v1.forEach(db::execSQL)
            db.execSQL("INSERT INTO documents VALUES(?,?,?)",arrayOf(id,"xnote","original"))
            db.execSQL("INSERT INTO locations VALUES(?,?,?,1)",arrayOf(uri,"example|a",id))
            db.execSQL("INSERT INTO resources VALUES('resource','paper','/immutable.pdf',12,'digest',1,0,123)")
            db.execSQL("INSERT INTO resource_refs VALUES('note:original','resource')")
            db.execSQL("INSERT INTO leases VALUES('lease','resource',99999)")
            db.execSQL("INSERT INTO migrations VALUES('pending-uri','original.zip','candidate.zip','source-hash',?)",arrayOf(id))
            db.version=1
            return dump(db)
        }
    }
    private fun dump(db:SQLiteDatabase)=listOf("documents","locations","resources","resource_refs","leases","migrations").associateWith {table->
        db.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use {c->buildList {while(c.moveToNext()) add((0 until c.columnCount).map {if(c.isNull(it)) null else c.getString(it)})}}
    }
    @Test fun versionOneUpgradesWithoutChangingAnyExistingTableRows() = scenario {context,root->
        val before=oldDatabase(root)
        DocumentRegistry(context).use {registry->
            assertEquals(3,registry.writableDatabase.version)
            assertEquals(before,dump(registry.readableDatabase))
            assertEquals(id,registry.idAt(uri))
        }
    }
    @Test fun versionTwoUpgradesWithoutChangingExistingTablesOrPageContracts() = scenario {context,root->
        val before=oldDatabase(root)
        SQLiteDatabase.openDatabase(File(root,"document-registry.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("CREATE TABLE page_reference_contracts(document_id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("INSERT INTO page_reference_contracts VALUES(?)",arrayOf(id));db.version=2
        }
        DocumentRegistry(context).use {registry->
            assertEquals(3,registry.writableDatabase.version);assertEquals(before,dump(registry.readableDatabase))
            assertTrue(registry.expectationForId(id).requiresPages)
            registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM original_pdf_documents",null).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
        }
    }
    @Test fun pageContractIsMonotonicAcrossDefaultRegistrationsAliasesAndReopen() = scenario {context,_->
        DocumentRegistry(context).use {r->
            r.register(id,"xnote","before",uri,pageReferences=true)
            r.register(id,"xnote","renamed","content://example/tree/root/document/a")
            assertTrue(r.expectationForId(id).requiresPages)
            assertEquals(ReferenceExpectation(id,true),r.expectationAt(uri))
            assertEquals(1,r.locations(id).size)
        }
        DocumentRegistry(context).use {r->assertTrue(r.expectationForId(id).requiresPages);assertEquals(ReferenceExpectation(id,true),r.expectationAt(uri))}
    }
    @Test fun movesAndPermissionLossPreserveContractAndUnregisteredNewUriStillUsesUuidEvidence() = scenario {context,_->
        DocumentRegistry(context).use {r->
            r.register(id,"xnote","source",uri,pageReferences=true)
            r.moved(uri,"content://example/document/moved")
            r.markUnavailable("content://example/tree/root/document/moved")
            assertTrue(r.locations(id).isEmpty())
            assertEquals(ReferenceExpectation(id,true),r.expectationAt("content://example/document/moved"))
            r.writableDatabase.execSQL("DELETE FROM locations")
            r.writableDatabase.execSQL("DELETE FROM documents")
            assertNull(r.expectationAt("content://example/document/external-move"))
            assertTrue(r.expectationForId(id).requiresPages)
        }
        DocumentRegistry(context).use {r->assertTrue(r.expectationForId(id).requiresPages)}
    }
    @Test fun newDocumentUuidDoesNotInheritAnotherDocumentsContract() = scenario {context,_->
        DocumentRegistry(context).use {r->
            r.register(id,"xnote","original",uri,pageReferences=true)
            val copy=UUID.randomUUID().toString();r.register(copy,"xnote","copy","content://example/document/copy")
            assertFalse(r.expectationForId(copy).requiresPages);assertTrue(r.expectationForId(id).requiresPages)
        }
    }
    @Test fun failedContractInsertionRollsBackDocumentAndLocationRegistration() = scenario {context,_->
        DocumentRegistry(context).use {r->
            r.register(id,"xnote","before",uri)
            r.writableDatabase.execSQL("CREATE TRIGGER reject_contract BEFORE INSERT ON page_reference_contracts BEGIN SELECT RAISE(ABORT,'test failure'); END")
            val copy=UUID.randomUUID().toString()
            assertThrows(android.database.SQLException::class.java) {r.register(copy,"xnote","after",uri,pageReferences=true)}
            assertEquals(id,r.idAt(uri));assertFalse(r.expectationForId(copy).requiresPages)
            r.readableDatabase.rawQuery("SELECT id FROM documents WHERE id=?",arrayOf(copy)).use {assertFalse(it.moveToFirst())}
        }
    }
    @Test fun unknownFutureSchemaIsRejectedWithoutDowngradingOrChangingStoredRows() = scenario {context,root->
        val before=oldDatabase(root)
        SQLiteDatabase.openDatabase(File(root,"document-registry.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version=4}
        val helper=DocumentRegistry(context)
        try {assertThrows(android.database.sqlite.SQLiteException::class.java) {helper.writableDatabase}} finally {helper.close()}
        SQLiteDatabase.openDatabase(File(root,"document-registry.db").path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            assertEquals(4,db.version);assertEquals(before,dump(db))
        }
    }
    @Test fun movedFileWithoutMarkerIsRejectedUsingUuidEvidenceAfterReopeningTheRegistry() = scenario {context,root->
        DocumentRegistry(context).use {r->
            r.register(id,"xnote","original",uri,pageReferences=true)
            r.writableDatabase.execSQL("DELETE FROM locations")
        }
        val file=File(root,"externally-moved.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use {com.xnotes.format.BundleIdentity(id).write(it)}
        val header=com.xnotes.format.BundleHeader.read(file)
        DocumentRegistry(context).use {r->
            assertNull(r.expectationAt("content://example/document/new-place"))
            val error=assertThrows(ReferenceContractViolation::class.java) {
                ReferenceContract.verifyObserved(r.expectationAt("content://example/document/new-place"),r.expectationForId(id),header)
            }
            assertEquals(StorageMsg.REFERENCE_PAGE_METADATA_MISSING,error.reason)
        }
    }
}
