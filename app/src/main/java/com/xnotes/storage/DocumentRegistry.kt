package com.xnotes.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

/** Private index only. Documents and immutable resources remain independently recoverable files. IO callers only. */
class DocumentRegistry(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "document-registry.db").absolutePath, null, 2) {
    private val liveLeases = java.util.concurrent.ConcurrentHashMap<String,String>()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        createPageContracts(db)
        db.execSQL("CREATE TABLE documents(id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE locations(uri TEXT PRIMARY KEY, location_key TEXT NOT NULL UNIQUE, document_id TEXT NOT NULL REFERENCES documents(id), available INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE INDEX locations_document ON locations(document_id,available)")
        db.execSQL("CREATE TABLE resources(id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL UNIQUE, size INTEGER NOT NULL, digest TEXT NOT NULL UNIQUE, version INTEGER NOT NULL, owned INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE resource_refs(owner TEXT NOT NULL, resource_id TEXT NOT NULL REFERENCES resources(id), PRIMARY KEY(owner,resource_id))")
        db.execSQL("CREATE INDEX refs_resource ON resource_refs(resource_id)")
        db.execSQL("CREATE TABLE leases(token TEXT PRIMARY KEY,resource_id TEXT NOT NULL REFERENCES resources(id),expires INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX leases_resource ON leases(resource_id,expires)")
        db.execSQL("CREATE TABLE migrations(uri TEXT PRIMARY KEY, original TEXT NOT NULL, candidate TEXT NOT NULL, source_hash TEXT NOT NULL, document_id TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion==1 && newVersion==2) {"Unsupported registry upgrade"}
        createPageContracts(db)
    }
    /** No location/document FK: losing a location or removing an index row must not erase evidence. */
    private fun createPageContracts(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE page_reference_contracts(document_id TEXT PRIMARY KEY NOT NULL)")
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    fun register(id: String, kind: String, name: String, uri: String, pageReferences:Boolean=false) = transaction { db ->
        register(db,id,kind,name,uri,pageReferences)
    }
    private fun register(db:SQLiteDatabase,id:String,kind:String,name:String,uri:String,pageReferences:Boolean) {
        require(UUID.fromString(id).toString() == id)
        db.execSQL("DELETE FROM locations WHERE location_key=? AND uri<>?",arrayOf(locationKey(uri),uri))
        db.execSQL("INSERT OR IGNORE INTO documents(id,kind,name) VALUES(?,?,?)", arrayOf(id, kind, name))
        db.execSQL("UPDATE documents SET name=? WHERE id=?", arrayOf(name,id))
        db.execSQL("INSERT OR IGNORE INTO locations(uri,location_key,document_id,available) VALUES(?,?,?,1)", arrayOf(uri,locationKey(uri), id))
        db.execSQL("UPDATE locations SET document_id=?,available=1 WHERE location_key=?", arrayOf(id,locationKey(uri)))
        if(pageReferences) db.execSQL("INSERT OR IGNORE INTO page_reference_contracts(document_id) VALUES(?)",arrayOf(id))
    }
    /** Includes unavailable locations and orphaned monotonic contracts; an empty file is not identity proof. */
    internal fun verifyCreation(id:String,kind:String,uri:String,allowRegisteredHere:Boolean=false,pageReferences:Boolean=false) {
        verifyCreation(readableDatabase,id,kind,uri,allowRegisteredHere,pageReferences)
    }
    private fun verifyCreation(db:SQLiteDatabase,id:String,kind:String,uri:String,allowRegisteredHere:Boolean,pageReferences:Boolean) {
        val key=locationKey(uri)
        val here=db.rawQuery("SELECT document_id FROM locations WHERE location_key=?",arrayOf(key)).use {if(it.moveToFirst()) it.getString(0) else null}
        val locations=db.rawQuery("SELECT location_key FROM locations WHERE document_id=?",arrayOf(id)).use {c->buildList {while(c.moveToNext()) add(c.getString(0))}}
        val knownKind=db.rawQuery("SELECT kind FROM documents WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) it.getString(0) else null}
        val anchored=db.rawQuery("SELECT 1 FROM page_reference_contracts WHERE document_id=?",arrayOf(id)).use {it.moveToFirst()}
        val fresh=here==null && locations.isEmpty() && knownKind==null && !anchored
        val same=allowRegisteredHere && here==id && locations.isNotEmpty() && locations.all {it==key} && knownKind==kind && (!anchored || pageReferences)
        if(!fresh && !same) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    }
    /** The final recheck and registration are indivisible even if another caller updated the index during IO. */
    internal fun registerCreated(id:String,kind:String,name:String,uri:String,pageReferences:Boolean) = transaction {db->
        verifyCreation(db,id,kind,uri,true,pageReferences)
        register(db,id,kind,name,uri,pageReferences)
    }
    /** Existing native recovery cannot reassign a location changed during PDF protection/IO. */
    internal fun registerObserved(header:com.xnotes.format.BundleHeader,kind:String,name:String,uri:String) = transaction {db->
        val id=header.identity?.documentId ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISSING)
        if(kind !in setOf("xnote","xcanvas")) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        val known=db.rawQuery("SELECT kind FROM documents WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) it.getString(0) else null}
        if(known!=null && known!=kind) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        ReferenceContract.verifyObserved(expectationAt(uri),expectationForId(id),header)
        register(db,id,kind,name,uri,header.anchors!=null)
    }
    /** JSON map observations cannot reclassify a known native identity or erase its page contract. */
    internal fun registerMapObserved(id:String,name:String,uri:String) = transaction {db->
        val at=expectationAt(uri);val byId=expectationForId(id)
        val known=db.rawQuery("SELECT kind FROM documents WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) it.getString(0) else null}
        if(at!=null && at.documentId!=id || at?.requiresPages==true || byId.requiresPages || known!=null && known!="xmap")
            throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        register(db,id,"xmap",name,uri,false)
    }
    /** One query freezes location identity and its monotonic contract together, including unavailable rows. */
    fun expectationAt(uri:String):ReferenceExpectation? = readableDatabase.rawQuery(
        "SELECT l.document_id,p.document_id IS NOT NULL FROM locations l LEFT JOIN page_reference_contracts p ON p.document_id=l.document_id WHERE l.location_key=?",
        arrayOf(locationKey(uri)),
    ).use {c->if(c.moveToFirst()) ReferenceExpectation(c.getString(0),c.getInt(1)!=0) else null}
    /** Also works after every old location/document row was removed or the file moved externally. */
    fun expectationForId(id:String):ReferenceExpectation = readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM page_reference_contracts WHERE document_id=?)",arrayOf(id),
    ).use {c->check(c.moveToFirst());ReferenceExpectation(id,c.getInt(0)!=0)}
    internal fun locationKey(uri: String): String = runCatching { val parsed=android.net.Uri.parse(uri); "${parsed.authority}|${android.provider.DocumentsContract.getDocumentId(parsed)}" }.getOrDefault(uri)
    internal fun uriAtLocation(uri:String):String? = readableDatabase.rawQuery("SELECT uri FROM locations WHERE location_key=?",arrayOf(locationKey(uri))).use {if(it.moveToFirst()) it.getString(0) else null}
    internal fun nameForId(id:String):String? = readableDatabase.rawQuery("SELECT name FROM documents WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) it.getString(0) else null}
    internal fun kindForId(id:String):String? = readableDatabase.rawQuery("SELECT kind FROM documents WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) it.getString(0) else null}
    fun idAt(uri: String): String? = readableDatabase.rawQuery("SELECT document_id FROM locations WHERE location_key=?", arrayOf(locationKey(uri))).use { if (it.moveToFirst()) it.getString(0) else null }
    /** Multiple live copies are a conflict, never a last-open-wins path. */
    fun locations(id: String): List<String> = readableDatabase.rawQuery("SELECT uri FROM locations WHERE document_id=? AND available=1 ORDER BY uri", arrayOf(id)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    fun resolve(id: String): String? {
        val found = locations(id)
        check(found.size <= 1) { StorageText.t(StorageMsg.DUPLICATE_DOCUMENT_ID) }
        return found.singleOrNull()
    }
    fun moved(old: String, new: String) = transaction { db ->
        val oldKey = locationKey(old)
        val newKey = locationKey(new)
        // Called only after a successful filesystem move. A reused destination may have a stale row.
        if (idAt(old) != null && oldKey != newKey) {
            db.execSQL("DELETE FROM locations WHERE location_key=?", arrayOf(newKey))
        }
        db.execSQL("UPDATE locations SET uri=?,location_key=?,available=1 WHERE location_key=?", arrayOf(new,newKey,oldKey))
    }
    fun markUnavailable(uri: String) { writableDatabase.execSQL("UPDATE locations SET available=0 WHERE location_key=?", arrayOf(locationKey(uri))) }
    fun referenceIds(owner: String): Set<String> = readableDatabase.rawQuery("SELECT resource_id FROM resource_refs WHERE owner=?",arrayOf(owner)).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    fun owners(prefix: String): Set<String> = readableDatabase.rawQuery("SELECT DISTINCT owner FROM resource_refs WHERE owner LIKE ?",arrayOf("$prefix%" )).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    fun knownLocations(): List<String> = readableDatabase.rawQuery("SELECT uri FROM locations",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun resource(id: String): Resource? = readableDatabase.rawQuery("SELECT id,name,path,size,digest,version,owned FROM resources WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else Resource(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getInt(5), c.getInt(6) == 1)
    }
    fun registerResource(resource: Resource) = transaction { db ->
        db.execSQL("INSERT OR IGNORE INTO resources(id,name,path,size,digest,version,owned,created) VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>(resource.id, resource.name, resource.path, resource.size, resource.digest, resource.version, if (resource.owned) 1 else 0, System.currentTimeMillis()))
        db.execSQL("UPDATE resources SET created=? WHERE id=?",arrayOf<Any>(System.currentTimeMillis(),resource.id))
        val stored = resource(resource.id)
        check(stored?.digest == resource.digest && stored.path == resource.path && stored.size == resource.size) { "Resource registration conflict" }
    }
    /** Owner replacement is one transaction: no intermediate unreferenced window. */
    fun references(owner: String, resources: Set<String>) = transaction { db ->
        for (id in resources) check(resource(id) != null) { StorageText.t(StorageMsg.PDF_RESOURCE_UNREGISTERED) }
        val previous=referenceIds(owner)
        db.execSQL("DELETE FROM resource_refs WHERE owner=?", arrayOf(owner))
        for (id in resources) db.execSQL("INSERT INTO resource_refs(owner,resource_id) VALUES(?,?)", arrayOf(owner,id))
        for (id in previous-resources) db.execSQL("UPDATE resources SET created=? WHERE id=? AND NOT EXISTS(SELECT 1 FROM resource_refs WHERE resource_id=?)",arrayOf<Any>(System.currentTimeMillis(),id,id))
    }
    fun lease(resourceId: String, until: Long): String = transaction { db ->
        check(resource(resourceId) != null) { StorageText.t(StorageMsg.PDF_RESOURCE_MISSING) }
        UUID.randomUUID().toString().also { db.execSQL("INSERT INTO leases(token,resource_id,expires) VALUES(?,?,?)", arrayOf<Any>(it, resourceId, until)); liveLeases[it]=resourceId }
    }
    fun release(token: String) { liveLeases.remove(token); writableDatabase.execSQL("DELETE FROM leases WHERE token=?", arrayOf(token)) }
    /** Delete only app-owned, unreferenced resources; expired leases of dead processes do not pin forever. */
    fun collect(now: Long, retainMillis: Long, root: File): Int = transaction { db ->
        db.execSQL("DELETE FROM leases WHERE expires<?", arrayOf(now))
        val candidates = db.rawQuery("SELECT id,path FROM resources r WHERE owned=1 AND created<? AND NOT EXISTS(SELECT 1 FROM resource_refs WHERE resource_id=r.id) AND NOT EXISTS(SELECT 1 FROM leases WHERE resource_id=r.id)", arrayOf((now-retainMillis).toString())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        var removed = 0
        for ((id, path) in candidates) {
            if (id in liveLeases.values) continue
            val file = File(path).canonicalFile
            check(file.parentFile == root.canonicalFile) { "Refusing resource outside owned directory" }
            if (!file.exists() || file.delete()) { db.execSQL("DELETE FROM resources WHERE id=?", arrayOf(id)); removed++ }
        }
        removed
    }
    data class Resource(val id: String, val name: String, val path: String, val size: Long, val digest: String, val version: Int = 1, val owned: Boolean = true)
}
