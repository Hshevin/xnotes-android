package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.xnotes.core.util.DocumentKind
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.NativeSourceSnapshot
import com.xnotes.storage.ReferenceContractViolation
import com.xnotes.storage.StorageMsg
import com.xnotes.format.BundleHeader

/** Frozen display data only. Final selection and activation re-open the actual source. */
internal class MapSourceOffer private constructor(val uri:Uri,val kind:DocumentKind,val name:String,
    val fileSource:MapSource,val catalog:NativeSourceSnapshot.ReferenceCatalog?) {
    companion object {
        internal fun observed(uri:Uri,kind:DocumentKind,name:String,source:MapSource,catalog:NativeSourceSnapshot.ReferenceCatalog?)=
            MapSourceOffer(uri,kind,name,source,catalog)
    }
}

/** Actual read-only observations; only the separately confirmed activation method may write. IO only. */
internal object MapSourceAccess {
    // Persisted permissions are shared for an exact URI. A failed concurrent offer must not
    // revoke the read grant another successful offer has just retained.
    private val permissionLocks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private fun name(context:Context,uri:Uri)=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)
        ?.use {if(it.moveToFirst()) it.getString(0) else null} ?: uri.lastPathSegment.orEmpty()
    private fun rememberPermission(context:Context,uri:Uri,write:Boolean=false) {
        val read=android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val flags=read or if(write) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        runCatching {context.contentResolver.takePersistableUriPermission(uri,flags)}
            .onFailure {if(write) runCatching {context.contentResolver.takePersistableUriPermission(uri,read)}}
    }
    private fun <T> withRememberedPermission(context:Context,uri:Uri,writeAccess:Boolean=false,block:()->T):T=
        synchronized(permissionLocks.getOrPut(uri.toString()) {Any()}) {
        val resolver=context.contentResolver
        val read=android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write=android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        fun held():Int=resolver.persistedUriPermissions.filter {it.uri==uri}.fold(0) {flags,permission->
            flags or (if(permission.isReadPermission) read else 0) or (if(permission.isWritePermission) write else 0)
        }
        val before=held()
        rememberPermission(context,uri,writeAccess)
        val acquired=held() and before.inv()
        try {block()} catch(error:Throwable) {
            // Explicit page activation may already have durable recovery work. Retain that
            // authorized write access for continuation; only failed read-only offers roll back.
            if(!writeAccess && acquired!=0) runCatching {resolver.releasePersistableUriPermission(uri,acquired)}.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
    private fun unique(storage:NotebookStorage,id:String) {require(storage.registry.locations(id).size==1)}
    fun offer(context:Context,uri:Uri,checkActive:()->Unit={}):MapSourceOffer=withRememberedPermission(context,uri) {
        checkActive();val name=name(context,uri);val kind=requireNotNull(DocumentKind.ofName(name))
        val storage=NotebookStorage.get(context)
        when(kind) {
            DocumentKind.NOTE,DocumentKind.CANVAS -> storage.openSource(uri.toString(),kind,checkActive).use {source->
                val catalog=source.referenceCatalog()
                val header=BundleHeader.read(source.file) {checkActive();false}
                MapSourceOffer.observed(uri,kind,name,MapSource(documentId=catalog.documentId,fileName=name,sourceVersion=catalog.sha256),catalog) to header
            }.let {(offer,header)->
                checkActive();storage.registry.registerObserved(header,kind.extension,name,uri.toString(),uniqueSource=true)
                offer
            }
            DocumentKind.MIND_MAP -> {
                val bytes=MapFiles.target(context,uri,checkActive).read();val doc=MapCodec.decode(bytes);checkActive()
                val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
                val offer=MapSourceOffer.observed(uri,kind,name,MapSource(documentId=doc.id,fileName=name,sourceVersion=sha),null)
                checkActive();storage.registry.registerMapObserved(doc.id,name,uri.toString(),uniqueSource=true)
                offer
            }
            DocumentKind.SPLIT -> error("Unsupported source kind")
        }
    }
    private fun same(expected:MapSourceOffer,actual:MapSourceOffer) {
        if(expected.kind!=actual.kind || expected.fileSource.documentId!=actual.fileSource.documentId)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        if(expected.catalog?.locationKey!=actual.catalog?.locationKey || expected.fileSource.sourceVersion!=actual.fileSource.sourceVersion)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_SOURCE_CHANGED)
    }
    /** Called only after explicit consent; the writable version comes from this actual holder. */
    fun activatePages(context:Context,expected:MapSourceOffer,checkActive:()->Unit={}):MapSourceOffer=
        withRememberedPermission(context,expected.uri,writeAccess=true) {
        val prior=requireNotNull(expected.catalog)
        require(expected.kind==DocumentKind.NOTE && prior.pages==NativeSourceSnapshot.ReferenceCatalog.Pages.NeedsActivation)
        checkActive()
        val storage=NotebookStorage.get(context)
        val catalog=storage.openSource(expected.uri.toString(),expected.kind,checkActive).use {source->
            val current=source.referenceCatalog()
            if(current.documentId!=prior.documentId || current.locationKey!=prior.locationKey || current.sha256!=prior.sha256)
                throw ReferenceContractViolation(StorageMsg.REFERENCE_SOURCE_CHANGED)
            storage.activatePages(expected.uri.toString(),source.version,expected.name,checkActive)
        }
        unique(storage,catalog.documentId);checkActive()
        MapSourceOffer.observed(expected.uri,expected.kind,expected.name,expected.fileSource.copy(sourceVersion=catalog.sha256),catalog)
    }
    fun completeActivation(context:Context,uri:Uri,checkActive:()->Unit={}):MapSourceOffer=withRememberedPermission(context,uri,writeAccess=true) {
        checkActive()
        val name=name(context,uri);val storage=NotebookStorage.get(context)
        val catalog=storage.completePageActivation(uri.toString(),name,checkActive)
        unique(storage,catalog.documentId);checkActive()
        MapSourceOffer.observed(uri,DocumentKind.NOTE,name,MapSource(documentId=catalog.documentId,fileName=name,sourceVersion=catalog.sha256),catalog)
    }
    /** Run after pen release; never turn an ordinal into a different page's permanent identity. */
    fun select(context:Context,expected:MapSourceOffer,pageId:String?,checkActive:()->Unit={}):MapSource {
        if(pageId!=null) require((expected.catalog?.pages as? NativeSourceSnapshot.ReferenceCatalog.Pages.Available)?.ids?.contains(pageId)==true)
        val actual=offer(context,expected.uri,checkActive);same(expected,actual)
        val lastKnown=pageId?.let {id->
            val pages=requireNotNull(actual.catalog?.pages as? NativeSourceSnapshot.ReferenceCatalog.Pages.Available)
            val index=pages.orderedIds.indexOf(id);require(index>=0);index+1
        }
        return actual.fileSource.copy(id=expected.fileSource.id,scope=if(pageId==null) SourceScope.FILE else SourceScope.PAGE,
            pageId=pageId,lastKnownPage=lastKnown).also {it.validate();checkActive()}
    }
    fun inspect(context: Context, uri: Uri,checkActive:()->Unit={}): MapSource {
        return offer(context,uri,checkActive).fileSource
    }
}
