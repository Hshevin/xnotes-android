package com.xnotes.mindmap

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleHeader
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.NativeReferenceTarget
import com.xnotes.storage.NativeSourceSnapshot
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.storage.PreparedNativeOpen
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Actual source preparation for one visual target-edit transaction. IO calls only. */
internal class MapSourceTargetAccess(context:Context,storageOverride:NotebookStorage?=null,
    originalsOverride:OriginalPdfAccess?=null) {
    private val app=context.applicationContext
    private val storage=storageOverride ?: NotebookStorage.get(app)
    private val originals=originalsOverride ?: OriginalPdfAccess(app,storageOverride=storage)
    private val catalog=OriginalPdfCatalog(storage.registry)
    private fun name(uri:Uri):String=runCatching {
        app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst()) it.getString(0) else null}
    }.getOrNull()?.take(4096) ?: uri.lastPathSegment.orEmpty().take(4096)
    private fun permission(uri:Uri,write:Boolean=false) {
        val read=Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {app.contentResolver.takePersistableUriPermission(uri,read or if(write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)}
            .onFailure {if(write) runCatching {app.contentResolver.takePersistableUriPermission(uri,read)}}
    }
    private fun nativeDomain(id:String,check:()->Unit):OriginalPdfCatalog.IdentityDomainsBatch = originals.identityDomains(setOf(id),check).also {
        kotlin.check(it.supported && it.current() && id !in it.reservedOriginalIds)
    }
    private fun unique(id:String) {require(storage.registry.locations(id).size==1)}
    fun openCurrent(target:MapSourceTarget,check:()->Unit={}):SourceRangeRead {
        check()
        if(target.scope==SourceScope.PDF_ORIGINAL) {
            val domain=originals.identityDomains(setOf(target.documentId),check)
            kotlin.check(domain.supported && domain.current() && target.documentId in domain.reservedOriginalIds && target.documentId !in domain.nativeIdentityIds)
            val record=requireNotNull(catalog.observe(target.documentId)).record
            val projection=requireNotNull(target.originalPdf)
            require(!record.deleted && projection.sha256==record.sha256)
            val index=record.pages.indexOf(target.pageId)
            if(index>=0) require(index==projection.pageIndex)
            val first=if(index>=0) index else 0
            val base=MapSourceTarget(record.id,SourceScope.PDF_ORIGINAL,record.pages[first],
                originalPdf=PdfOriginalProjection(record.sha256,first,projection.viewTurns))
            val read=originals.open(base,check)
            try {kotlin.check(domain.current());return originalRead(read,target)} catch(error:Throwable) {read.closeAndAwait();throw error}
        }
        target.validate();val domain=nativeDomain(target.documentId,check)
        val uri=Uri.parse(requireNotNull(storage.registry.resolve(target.documentId)))
        val offer=inspectFile(uri,check)
        try {
            when(offer) {
                is SourceTargetOffer.Native->{
                    require(offer.offer.fileSource.documentId==target.documentId)
                    if(target.scope==SourceScope.PAGE) require(offer.offer.kind==DocumentKind.NOTE)
                    if(target.scope==SourceScope.CANVAS_REGION) require(offer.offer.kind==DocumentKind.CANVAS)
                }
                is SourceTargetOffer.Map->require(target.scope==SourceScope.FILE && offer.documentId==target.documentId)
                is SourceTargetOffer.RawPdf->error("A native identity cannot turn into a raw PDF")
            }
            kotlin.check(domain.current());return open(offer,target,check)
        } catch(error:Throwable) {offer.closeAndAwait();throw error}
    }
    fun inspectFile(uri:Uri,check:()->Unit={}):SourceTargetOffer=MapSourceAccess.withRememberedPermission(app,uri) {
        check();val title=name(uri)
        val prefix=ByteArray(1024);var count=0
        requireNotNull(app.contentResolver.openInputStream(uri)).use {input->while(count<prefix.size) {
            check();val read=input.read(prefix,count,prefix.size-count);if(read<0) break;count+=read
        }}
        check()
        val head=prefix.copyOf(count)
        if(count>=2 && head[0]==80.toByte() && head[1]==75.toByte()) {
            return@withRememberedPermission storage.openSourceActual(uri.toString(),check).use {source->
                val observed=source.referenceCatalog();val domain=nativeDomain(observed.documentId,check)
                storage.registry.registerObserved(BundleHeader.read(source.file) {check();false},source.version.kind.extension,title,uri.toString(),uniqueSource=true)
                unique(observed.documentId);kotlin.check(domain.current())
                SourceTargetOffer.Native.issue(MapSourceOffer.observed(uri,source.version.kind,title,
                    MapSource(documentId=observed.documentId,fileName=title,sourceVersion=observed.sha256),observed),domain::current)
            }
        }
        val json=head.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        if(!json.startsWith("{")) {
            require(head.toString(Charsets.ISO_8859_1).contains("%PDF-")) {"Unsupported actual source format"}
            return@withRememberedPermission SourceTargetOffer.RawPdf.issue(uri,title,{true})
        }
        // A PDF signature quoted in an actual JSON document is content, not a new file kind.
        val bytes=mapBytes(uri,check);val document=MapCodec.decode(bytes);val domain=nativeDomain(document.id,check)
        storage.registry.registerMapObserved(document.id,title,uri.toString(),uniqueSource=true);unique(document.id);kotlin.check(domain.current())
        SourceTargetOffer.Map.issue(uri,title,document.id,hash(bytes),document,domain::current)
    }
    /** Success transfers offer ownership into the returned read; failure leaves it with the caller. */
    fun openOffer(offer:SourceTargetOffer,check:()->Unit={}):SourceRangeRead {
        kotlin.check(offer.current())
        val id=when(offer) {
            is SourceTargetOffer.Native->offer.offer.fileSource.documentId
            is SourceTargetOffer.Map->offer.documentId
            is SourceTargetOffer.RawPdf->error("Raw PDFs require separate original-library retention consent")
        }
        return open(offer,MapSourceTarget(id,SourceScope.FILE),check)
    }
    private fun open(offer:SourceTargetOffer,initial:MapSourceTarget,check:()->Unit):SourceRangeRead {
        check();kotlin.check(offer.current())
        return when(offer) {
            is SourceTargetOffer.Native->{
                val expected=requireNotNull(offer.offer.catalog);val domain=nativeDomain(expected.documentId,check)
                var prepared:PreparedNativeOpen?=null
                try {
                    storage.openSourceActual(offer.uri.toString(),check).use {source->
                        val actual=source.referenceCatalog()
                        require(actual.kind==offer.offer.kind && actual.documentId==expected.documentId && actual.sha256==expected.sha256 && actual.locationKey==expected.locationKey)
                        val text=AndroidTextMeasurer();val image=AndroidImageCodec()
                        val noteCodec=DocumentCodec(image,text).apply {resolvePdf={storage.pdfResources.resolveChecked(it,check)}}
                        prepared=PreparedNativeOpen.read(source,storage.pdfResources,noteCodec,CanvasCodec(image,text),
                            File(app.cacheDir,"source-target-pdfs"),File(app.cacheDir,"source-target-assets"),null,
                            requireCompleteCanvas=actual.kind==DocumentKind.CANVAS,requireCompleteNote=actual.kind==DocumentKind.NOTE,checkActive=check)
                    }
                    check();kotlin.check(domain.current() && offer.current());unique(expected.documentId)
                    val owned=requireNotNull(prepared)
                    ActualSourceRangeRead(app,SourceRangePayload.Native(owned,offer,domain),initial) {target,active->
                        active();storage.openSourceActual(offer.uri.toString(),active).use {source->
                            val actual=source.referenceCatalog()
                            require(actual.kind==expected.kind && actual.documentId==expected.documentId && actual.sha256==expected.sha256 && actual.locationKey==expected.locationKey)
                            NativeReferenceTarget(target.documentId,target.pageId,expected.kind).verify(actual)
                            unique(expected.documentId);kotlin.check(domain.current())
                        };active()
                    }.also {prepared=null}
                } finally {prepared?.close()}
            }
            is SourceTargetOffer.Map->{
                val domain=nativeDomain(offer.documentId,check);val bytes=mapBytes(offer.uri,check)
                require(hash(bytes)==offer.rawVersion && MapCodec.decode(bytes).id==offer.documentId)
                kotlin.check(domain.current() && offer.current())
                ActualSourceRangeRead(app,SourceRangePayload.Map(offer,domain),initial) {target,active->
                    active();require(target.scope==SourceScope.FILE)
                    val latest=mapBytes(offer.uri,active);require(hash(latest)==offer.rawVersion && MapCodec.decode(latest).id==offer.documentId)
                    unique(offer.documentId);kotlin.check(domain.current());active()
                }
            }
            is SourceTargetOffer.RawPdf->error("A raw PDF is not an accepted source identity")
        }
    }
    fun activatePages(offer:SourceTargetOffer.Native,check:()->Unit={}):SourceTargetOffer.Native {
        kotlin.check(offer.current());val expected=requireNotNull(offer.offer.catalog)
        require(expected.kind==DocumentKind.NOTE && expected.pages==NativeSourceSnapshot.ReferenceCatalog.Pages.NeedsActivation)
        permission(offer.uri,true)
        val updated=storage.openSourceActual(offer.uri.toString(),check).use {source->
            val actual=source.referenceCatalog();require(actual.documentId==expected.documentId && actual.sha256==expected.sha256 && actual.locationKey==expected.locationKey)
            storage.activatePages(offer.uri.toString(),source.version,offer.displayName,check)
        }
        return activated(offer,updated,check)
    }
    fun completePageActivation(offer:SourceTargetOffer.Native,check:()->Unit={}):SourceTargetOffer.Native {
        kotlin.check(offer.active());permission(offer.uri,true)
        return activated(offer,storage.completePageActivation(offer.uri.toString(),offer.displayName,check),check)
    }
    private fun activated(previous:SourceTargetOffer.Native,actual:NativeSourceSnapshot.ReferenceCatalog,check:()->Unit):SourceTargetOffer.Native {
        require(actual.kind==DocumentKind.NOTE && actual.documentId==previous.offer.fileSource.documentId)
        val domain=nativeDomain(actual.documentId,check);unique(actual.documentId)
        return SourceTargetOffer.Native.issue(MapSourceOffer.observed(previous.uri,DocumentKind.NOTE,previous.displayName,
            previous.offer.fileSource.copy(sourceVersion=actual.sha256),actual),domain::current)
    }
    fun originalPickerPage(cursor:OriginalPickerCursor?=null,check:()->Unit={}):OriginalPickerPage=
        OriginalSourcePickerAccess(storage.registry).page(cursor,check)
    fun openOriginal(entry:OriginalPickerEntry,check:()->Unit={}):SourceRangeRead {
        kotlin.check(entry.current());require(entry.state==OriginalPickerState.ROW_AVAILABLE)
        val id=requireNotNull(entry.documentId);val domain=originals.identityDomains(setOf(id),check)
        kotlin.check(domain.supported && domain.current() && id in domain.reservedOriginalIds && id !in domain.nativeIdentityIds)
        val record=requireNotNull(catalog.observe(id)).record
        require(!record.deleted && record.generation==entry.generation && record.sha256==entry.sha256)
        val target=MapSourceTarget(record.id,SourceScope.PDF_ORIGINAL,record.pages.first(),originalPdf=PdfOriginalProjection(record.sha256,0))
        val read=originals.open(target,check)
        try {kotlin.check(entry.current() && domain.current());return originalRead(read,target)} catch(error:Throwable) {read.closeAndAwait();throw error}
    }
    fun retainPdf(offer:SourceTargetOffer.RawPdf,check:()->Unit={}):SourcePdfRetention {
        kotlin.check(offer.current());check()
        val root=File(app.cacheDir,"source-target-raw").apply {kotlin.check(isDirectory || mkdirs())}
        val file=File.createTempFile("original-",".pdf",root)
        var transferred=false;var candidate:OriginalPdfAccess.Candidate?=null
        try {
            requireNotNull(app.contentResolver.openInputStream(offer.uri)).use {input->FileOutputStream(file).use {output->
                val bytes=ByteArray(65536)
                while(true) {check();kotlin.check(offer.current());val count=input.read(bytes);if(count<0) break;output.write(bytes,0,count)}
                output.fd.sync()
            }}
            val opened=originals.candidate(file,offer.displayName,check);candidate=opened
            val result=catalog.retain(opened)
            opened.closeAndAwait();candidate=null
            return when(result) {
                is OriginalPdfCatalog.Retention.RestoreRequired->{
                    val pending=SourcePdfRestoreOffer(file,offer.displayName,result.observation)
                    transferred=true;offer.closeAndAwait();SourcePdfRetention.RestoreRequired(pending)
                }
                is OriginalPdfCatalog.Retention.Retained->{
                    val record=result.observation.record
                    val target=MapSourceTarget(record.id,SourceScope.PDF_ORIGINAL,record.pages.first(),originalPdf=PdfOriginalProjection(record.sha256,0))
                    val read=originals.open(target,check)
                    try {val prepared=originalRead(read,target);offer.closeAndAwait();SourcePdfRetention.Retained(prepared)}
                    catch(error:Throwable) {read.closeAndAwait();throw error}
                }
            }
        } finally {try {candidate?.closeAndAwait()} finally {if(!transferred) file.delete()}}
    }
    fun restorePdf(offer:SourcePdfRestoreOffer,check:()->Unit={}):SourceRangeRead {
        kotlin.check(offer.current());check()
        val opened=originals.candidate(offer.file,offer.displayName,check)
        var candidate:OriginalPdfAccess.Candidate?=opened
        try {
            val record=catalog.restore(offer.expected,opened).record
            opened.closeAndAwait();candidate=null
            val target=MapSourceTarget(record.id,SourceScope.PDF_ORIGINAL,record.pages.first(),originalPdf=PdfOriginalProjection(record.sha256,0))
            val read=originals.open(target,check)
            try {return originalRead(read,target).also {offer.closeAndAwait()}} catch(error:Throwable) {read.closeAndAwait();throw error}
        } finally {candidate?.closeAndAwait()}
    }
    private fun originalRead(read:OriginalPdfAccess.Read,initial:MapSourceTarget):SourceRangeRead =
        ActualSourceRangeRead(app,SourceRangePayload.Original(read),initial) {target,active->
            active();val latest=originals.open(target,active)
            try {active();kotlin.check(latest.current() && read.current())} finally {latest.closeAndAwait()}
        }
    private fun mapBytes(uri:Uri,check:()->Unit):ByteArray {
        val lock=MapSaves.lockFor(uri);val owner=Any()
        while(!lock.tryLock(owner)) {check();Thread.sleep(5)}
        try {check();return MapFiles.target(app,uri,check).read().also {check()}} finally {lock.unlock(owner)}
    }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
}
