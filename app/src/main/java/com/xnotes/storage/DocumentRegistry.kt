package com.xnotes.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID

/** Native location index plus authoritative independent-original identities. IO callers only. */
class DocumentRegistry internal constructor(context: Context, private val probe: SameFileProbe) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "document-registry.db").absolutePath, null, 4) {
    constructor(context: Context) : this(context, AndroidSameFileProbe(context.contentResolver))

    private val liveLeases = java.util.concurrent.ConcurrentHashMap<String,String>()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        createPageContracts(db)
        createOriginalPdfs(db)
        db.execSQL("CREATE TABLE documents(id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE locations(uri TEXT PRIMARY KEY, location_key TEXT NOT NULL UNIQUE, document_id TEXT NOT NULL REFERENCES documents(id), available INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE INDEX locations_document ON locations(document_id,available)")
        createAliasExpectations(db)
        db.execSQL("CREATE TABLE resources(id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL UNIQUE, size INTEGER NOT NULL, digest TEXT NOT NULL UNIQUE, version INTEGER NOT NULL, owned INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE resource_refs(owner TEXT NOT NULL, resource_id TEXT NOT NULL REFERENCES resources(id), PRIMARY KEY(owner,resource_id))")
        db.execSQL("CREATE INDEX refs_resource ON resource_refs(resource_id)")
        db.execSQL("CREATE TABLE leases(token TEXT PRIMARY KEY,resource_id TEXT NOT NULL REFERENCES resources(id),expires INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX leases_resource ON leases(resource_id,expires)")
        db.execSQL("CREATE TABLE migrations(uri TEXT PRIMARY KEY, original TEXT NOT NULL, candidate TEXT NOT NULL, source_hash TEXT NOT NULL, document_id TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..3 && newVersion==4) {"Unsupported registry upgrade"}
        if(oldVersion<2) createPageContracts(db)
        if(oldVersion<3) createOriginalPdfs(db)
        createAliasExpectations(db)
    }
    /** A previously observed key still protects its UUID if either grant is later revoked.
     * These are expectations only: never count or resolve one as an available file. */
    private fun createAliasExpectations(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE location_alias_expectations(location_key TEXT PRIMARY KEY NOT NULL,uri TEXT NOT NULL," +
            "document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE)")
    }
    private fun createOriginalPdfs(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE original_pdf_documents(id TEXT PRIMARY KEY NOT NULL,resource_sha TEXT NOT NULL UNIQUE,title TEXT NOT NULL,page_count INTEGER NOT NULL,generation INTEGER NOT NULL,deleted INTEGER NOT NULL,row_schema INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE original_pdf_pages(document_id TEXT NOT NULL REFERENCES original_pdf_documents(id),ordinal INTEGER NOT NULL,page_id TEXT NOT NULL UNIQUE,PRIMARY KEY(document_id,ordinal))")
    }
    /** No location/document FK: losing a location or removing an index row must not erase evidence. */
    private fun createPageContracts(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE page_reference_contracts(document_id TEXT PRIMARY KEY NOT NULL)")
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
    internal fun reservedOriginalId(id:String):Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM original_pdf_documents WHERE id=?",arrayOf(id)).use {it.moveToFirst()}
    private data class IdentityPosition(val id:String,val uri:String,val available:Int)
    private fun identityPosition(db:SQLiteDatabase,key:String):IdentityPosition? = db.rawQuery(
        "SELECT document_id,uri,available FROM locations WHERE location_key=?",arrayOf(key),
    ).use {c->if(c.moveToFirst()) IdentityPosition(c.getString(0),c.getString(1),c.getInt(2)) else null}
    fun register(id: String, kind: String, name: String, uri: String, pageReferences:Boolean=false) = transaction { db ->
        register(db,id,kind,name,uri,pageReferences)
    }
    private fun register(
        db: SQLiteDatabase, id: String, kind: String, name: String, uri: String, pageReferences: Boolean,
        uniqueSource: Boolean = false, creationLocations: List<String>? = null,
    ) {
        require(UUID.fromString(id).toString() == id)
        val key=locationKey(uri)
        val aliases = creationLocations ?: run {
            val locations = locationUris(db, id, availableOnly = false)
            // Explicit copy recovery can reassign the file through either spelling. Carry only
            // the old identity's locations that can be proved to refer to this file now.
            val oldId = idAt(uri)
            val previous = if (oldId == null || oldId == id) emptyList() else
                locationUris(db, oldId, availableOnly = false) + archivedUris(db, oldId)
            (locations + previous).filter { sameLocation(it, uri) }
        }
        if (uniqueSource) require(aliases.containsAll(locationUris(db, id, availableOnly = true))) {
            StorageText.t(StorageMsg.DUPLICATE_DOCUMENT_ID)
        }
        // A fresh grant for the same provider key may replace a revoked tree grant. Only prefer
        // an older spelling to the freshly observed URI if that spelling still opens the file.
        val kept = (aliases.filter { it == uri || locationKey(it) != key || probe.sameFile(it, uri) == true } + uri)
            .minWith(locationPreference)
        val keptKey = locationKey(kept)
        db.execSQL("INSERT OR IGNORE INTO documents(id,kind,name) VALUES(?,?,?)", arrayOf(id, kind, name))
        db.execSQL("UPDATE documents SET name=? WHERE id=?", arrayOf(name,id))
        if(pageReferences) db.execSQL("INSERT OR IGNORE INTO page_reference_contracts(document_id) VALUES(?)",arrayOf(id))

        for (old in (aliases + uri).distinctBy(::locationKey)) {
            if (locationKey(old) != keptKey) archiveLocation(db, old, id)
        }
        val previous = identityPosition(db, keptKey)
        if (previous != IdentityPosition(id, kept, 1)) {
            db.execSQL("INSERT OR REPLACE INTO locations(uri,location_key,document_id,available) VALUES(?,?,?,1)", arrayOf(kept,keptKey,id))
            db.delete("location_alias_expectations", "location_key=?", arrayOf(keptKey))
        }
    }
    private fun aliasId(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT document_id FROM location_alias_expectations WHERE location_key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun archivedUris(db: SQLiteDatabase, id: String): List<String> =
        db.rawQuery("SELECT uri FROM location_alias_expectations WHERE document_id=?", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun archiveLocation(db: SQLiteDatabase, uri: String, id: String) {
        val key = locationKey(uri)
        val previous = identityPosition(db, key)
        val expectation = aliasId(db, key)
        if (previous == null && expectation == id) return
        db.delete("locations", "location_key=?", arrayOf(key))
        db.execSQL("INSERT OR REPLACE INTO location_alias_expectations(location_key,uri,document_id) VALUES(?,?,?)", arrayOf(key, uri, id))
    }
    /** Includes unavailable locations and orphaned monotonic contracts; an empty file is not identity proof. */
    internal fun verifyCreation(id:String,kind:String,uri:String,allowRegisteredHere:Boolean=false,pageReferences:Boolean=false) {
        verifyCreation(readableDatabase,id,kind,uri,allowRegisteredHere,pageReferences)
    }
    private fun verifyCreation(db:SQLiteDatabase,id:String,kind:String,uri:String,allowRegisteredHere:Boolean,pageReferences:Boolean): List<String> {
        val here = idAt(uri)
        val locations = locationUris(db, id, availableOnly = false)
        val knownKind=db.rawQuery("SELECT kind FROM documents WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) it.getString(0) else null}
        val anchored=db.rawQuery("SELECT 1 FROM page_reference_contracts WHERE document_id=?",arrayOf(id)).use {it.moveToFirst()}
        val fresh=here==null && locations.isEmpty() && knownKind==null && !anchored &&
            knownLocations().none { sameLocation(it, uri) }
        val same=allowRegisteredHere && (here==null || here==id) && locations.isNotEmpty() && locations.all {sameLocation(it,uri)} && knownKind==kind && (!anchored || pageReferences)
        if(!fresh && !same) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        return locations
    }
    /** The final recheck and registration are indivisible even if another caller updated the index during IO. */
    internal fun registerCreated(id:String,kind:String,name:String,uri:String,pageReferences:Boolean) = transaction {db->
        val verified = verifyCreation(db,id,kind,uri,true,pageReferences)
        register(db,id,kind,name,uri,pageReferences,creationLocations = verified)
    }
    /** Existing native recovery cannot reassign a location changed during PDF protection/IO. */
    internal fun registerObserved(header:com.xnotes.format.BundleHeader,kind:String,name:String,uri:String,uniqueSource:Boolean=false) = transaction {db->
        val id=header.identity?.documentId ?: throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISSING)
        if(kind !in setOf("xnote","xcanvas")) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        val known=db.rawQuery("SELECT kind FROM documents WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) it.getString(0) else null}
        if(known!=null && known!=kind) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        for (expected in listOfNotNull(expectationAt(uri), expectationForId(id))) {
            if (expected.documentId != id) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
            if (expected.requiresPages && header.anchors == null) throw ReferenceContractViolation(StorageMsg.REFERENCE_PAGE_METADATA_MISSING)
        }
        register(db,id,kind,name,uri,header.anchors!=null,uniqueSource)
    }
    /** One query freezes the location expectation and its monotonic contract together. */
    fun expectationAt(uri: String): ReferenceExpectation? {
        val key = locationKey(uri)
        return readableDatabase.rawQuery(
            "SELECT d.id,p.document_id IS NOT NULL FROM documents d " +
                "LEFT JOIN page_reference_contracts p ON p.document_id=d.id WHERE d.id=COALESCE(" +
                "(SELECT document_id FROM locations WHERE location_key=?)," +
                "(SELECT document_id FROM location_alias_expectations WHERE location_key=?))",
            arrayOf(key, key),
        ).use { c -> if (c.moveToFirst()) ReferenceExpectation(c.getString(0), c.getInt(1) != 0) else null }
    }
    /** Also works after every old location/document row was removed or the file moved externally. */
    fun expectationForId(id:String):ReferenceExpectation = readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM page_reference_contracts WHERE document_id=?)",arrayOf(id),
    ).use {c->check(c.moveToFirst());ReferenceExpectation(id,c.getInt(0)!=0)}
    internal fun locationKey(uri: String): String = runCatching { val parsed=android.net.Uri.parse(uri); "${parsed.authority}|${android.provider.DocumentsContract.getDocumentId(parsed)}" }.getOrDefault(uri)
    internal fun uriAtLocation(uri:String):String? = readableDatabase.rawQuery("SELECT uri FROM locations WHERE location_key=?",arrayOf(locationKey(uri))).use {if(it.moveToFirst()) it.getString(0) else null}
    internal fun nameForId(id:String):String? = readableDatabase.rawQuery("SELECT name FROM documents WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) it.getString(0) else null}
    internal fun kindForId(id:String):String? = readableDatabase.rawQuery("SELECT kind FROM documents WHERE id=?",arrayOf(id)).use {if(it.moveToFirst()) it.getString(0) else null}
    fun idAt(uri: String): String? = expectationAt(uri)?.documentId
    /** Provider document keys already identify tree and direct grants from the same provider.
     * Cross-key equivalence is always proved anew; never use it as a stored write permit. */
    internal fun sameLocation(a: String, b: String): Boolean =
        locationKey(a) == locationKey(b) || probe.sameFile(a, b) == true

    // Prefer a working-folder grant; make ties independent of observation order.
    private val locationPreference = compareByDescending<String> { DocumentsContract.isTreeUri(Uri.parse(it)) }.thenBy { it }

    private fun locationUris(db: SQLiteDatabase, id: String, availableOnly: Boolean): List<String> {
        val available = if (availableOnly) " AND available=1" else ""
        return db.rawQuery("SELECT uri FROM locations WHERE document_id=?$available ORDER BY uri", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    internal fun distinctLocations(uris: Collection<String>): List<String> {
        val kept = mutableListOf<String>()
        for (uri in uris.sortedWith(locationPreference)) {
            if (kept.none { sameLocation(it, uri) }) kept.add(uri)
        }
        return kept
    }

    /** Under an existing identity observation, verify the source without retiring our own ticket
     * by compacting legacy rows. An as-yet unregistered source has no competing location. */
    internal fun hasCompetingLocation(id: String, uri: String): Boolean =
        locationUris(readableDatabase, id, availableOnly = true).any { !sameLocation(it, uri) }

    /** Older registries can already contain both rows before the next folder observation. */
    fun locations(id: String): List<String> {
        val found = locationUris(readableDatabase, id, availableOnly = true)
        if (found.size < 2) return found
        return transaction { db ->
            val current = locationUris(db, id, availableOnly = true)
            val distinct = distinctLocations(current)
            for (uri in current - distinct.toSet()) archiveLocation(db, uri, id)
            distinct.sorted()
        }
    }
    fun resolve(id: String): String? {
        val found = locations(id)
        check(found.size <= 1) { StorageText.t(StorageMsg.DUPLICATE_DOCUMENT_ID) }
        return found.singleOrNull()
    }
    fun moved(old: String, new: String) = transaction { db ->
        val oldKey = locationKey(old)
        val newKey = locationKey(new)
        val source=identityPosition(db,oldKey)
        // Called only after a successful filesystem move. A reused destination may have a stale row.
        if (source != null && oldKey != newKey) {
            db.execSQL("DELETE FROM locations WHERE location_key=?", arrayOf(newKey))
        }
        db.execSQL("UPDATE locations SET uri=?,location_key=?,available=1 WHERE location_key=?", arrayOf(new,newKey,oldKey))
        if (source != null) db.delete("location_alias_expectations", "location_key=?", arrayOf(newKey))
    }
    fun markUnavailable(uri: String) = transaction {db->
        val key=locationKey(uri)
        db.execSQL("UPDATE locations SET available=0 WHERE location_key=?",arrayOf(key))
    }
    fun referenceIds(owner: String): Set<String> = readableDatabase.rawQuery("SELECT resource_id FROM resource_refs WHERE owner=?",arrayOf(owner)).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    fun owners(prefix: String): Set<String> = readableDatabase.rawQuery("SELECT DISTINCT owner FROM resource_refs WHERE owner LIKE ?",arrayOf("$prefix%" )).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    fun knownLocations(): List<String> = readableDatabase.rawQuery("SELECT uri FROM locations",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    internal data class MapLocation(val id:String,val name:String,val uri:String,val available:Boolean)
    /** Discovery hints only; callers still read the actual graph and classify its library location. */
    internal fun knownMapLocations(limit:Int=4097,documentId:String?=null):List<MapLocation> {
        require(limit in 1..4097)
        val where=if(documentId==null) "" else " AND d.id=?"
        val args=if(documentId==null) arrayOf(limit.toString()) else arrayOf(documentId,limit.toString())
        return readableDatabase.rawQuery(
            "SELECT d.id,d.name,l.uri,l.available FROM documents d JOIN locations l ON l.document_id=d.id WHERE d.kind='xmap'"+where+" ORDER BY l.available DESC,l.uri LIMIT ?",args,
        ).use {c->buildList {while(c.moveToNext()) add(MapLocation(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)!=0))}}
    }
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

data class ReferenceExpectation(val documentId:String,val requiresPages:Boolean) {
    init {require(UUID.fromString(documentId).toString()==documentId)}
}

internal class ReferenceContractViolation(val reason:StorageMsg,cause:Throwable?=null):IllegalStateException(StorageText.t(reason),cause)
