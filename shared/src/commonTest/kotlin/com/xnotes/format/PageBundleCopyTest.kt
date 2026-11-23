package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.testkit.*
import kotlin.test.Test

class PageBundleCopyTest : BundleTestFiles() {
    private val owner="00000000-0000-0000-0000-000000000001"
    private val p1="00000000-0000-0000-0000-000000000002"
    private val p2="00000000-0000-0000-0000-000000000003"
    private val pattern=""" {"format":"xnote","version":1,"pages":[{"reference_id" : "@P1@","items":[{"kind":"future","reference_id":"$p1","samples":[[-0,1.2500e+02,0.1234567890123456789]]}]},{"reference_id":"@P2@","items":[]}],"has_pdf":true,"future":{"pages":[{"reference_id":"$p2"}]}} """
    private fun directory(block:(AssetFile)->Unit) {
        val dir=testDirectory("page-copy-bundle-")
        try {block(dir)} finally {dir.deleteTree()}
    }
    private fun make(dir:AssetFile,identity:BundleIdentity=BundleIdentity(owner),manifest:String=pattern.replace("@P1@",p1).replace("@P2@",p2),anchored:Boolean=true,pdf:AssetFile?=null,comment:Boolean=true):AssetFile =
        temporaryAsset("input-",".zip",dir).also {file->bundleWriter(file.fileSink(),comment="archive comment".takeIf {comment}).use {z->
            fun entry(name:String,bytes:ByteArray) {z.putDeflated(name,bytes)}
            entry("manifest.json",manifest.encodeToByteArray());entry("future/raw",byteArrayOf(0,1,2,-1))
            pdf?.let {entry(BundlePdf.ENTRY,it.fileBytes())};z.addIdentity(identity)
            if(anchored) entry(AnchorMetadata.ENTRY,""" { "documentId":"${identity.documentId}", "format":"xnotes-page-anchors", "version":1 } """.encodeToByteArray())
        }}
    private fun entry(file:AssetFile,name:String)=bundleDirectory(file).use {z->z.open(name).buffer().use {it.readByteArray()}}
    private fun assertIndependentCopy(source:AssetFile,copy:AssetFile) {
        val header=BundleHeader.read(copy)
        assertNotEquals(owner,header.identity!!.documentId)
        assertEquals(header.identity!!.documentId,header.anchors!!.documentId)
        val manifest=entry(copy,"manifest.json");val pages=TestJsonTree.parse(manifest.decodeToString()).field("pages")
        val fresh=(0 until pages.size).map {pages.item(it).string("reference_id")}
        assertEquals(2,fresh.toSet().size);assertTrue(fresh.none {it==p1 || it==p2})
        assertArrayEquals(pattern.replace("@P1@",fresh[0]).replace("@P2@",fresh[1]).encodeToByteArray(),manifest)
        assertArrayEquals(entry(source,"future/raw"),entry(copy,"future/raw"))
    }
    @Test fun identityCopyRebindsMarkerAndRemintsOnlyPageIds() = directory {dir->
        val source=make(dir);val original=source.fileBytes()
        val staged=BundleMigration.prepare(source,dir,copy=true)
        try {assertIndependentCopy(source,staged.file);assertArrayEquals(original,source.fileBytes())}
        finally {staged.file.remove()}
        assertEquals(setOf(source.assetPath().name),dir.fileNames().toSet())
    }
    @Test fun portableCopyEmbedsUnchangedPdfAndRebindsPageReferences() = directory {dir->
        val pdf=dir.child("original.pdf").apply {setText("%PDF-1.7 immutable original")}
        val id=BundleIdentity(owner,BundleMigration.hash(pdf),true)
        val source=make(dir,id);val original=source.fileBytes();val bytes=pdf.fileBytes()
        val copy=dir.child("copy.xnote")
        copy.fileSink().use {BundlePdf.write(source,it,id.copy(documentId=Uuids.random()),pdf,false)}
        assertIndependentCopy(source,copy)
        assertArrayEquals(bytes,entry(copy,BundlePdf.ENTRY));assertArrayEquals(bytes,pdf.fileBytes());assertArrayEquals(original,source.fileBytes())
        assertFalse(BundleHeader.read(copy).identity!!.externalPdf)
    }
    @Test fun sameIdentityPortableExportRetainsManifestAndRawMarkerBytes() = directory {dir->
        val pdf=dir.child("original.pdf").apply {setText("%PDF-1.7 immutable")}
        val id=BundleIdentity(owner,BundleMigration.hash(pdf),true);val source=make(dir,id)
        val output=dir.child("portable.xnote")
        output.fileSink().use {BundlePdf.write(source,it,id,pdf,false)}
        assertArrayEquals(entry(source,"manifest.json"),entry(output,"manifest.json"))
        assertArrayEquals(entry(source,AnchorMetadata.ENTRY),entry(output,AnchorMetadata.ENTRY))
        assertEquals(owner,BundleHeader.read(output).identity!!.documentId)
    }
    @Test fun sameIdentityExportRefusesBrokenPageContractBeforeWritingAnyOutput() = directory {dir->
        for(manifest in listOf("""{"format":"xnote","version":1,"pages":[{}]}""", """{"format":"xnote","version":1,"pages":[{"reference_id":"$p1"},{"reference_id":"$p1"}]}""")) {
            val source=make(dir,manifest=manifest);val out=Buffer()
            assertFailsWith<IllegalArgumentException> {BundlePdf.write(source,out,BundleIdentity(owner),null,false)}
            assertEquals(0,out.size.toInt())
        }
    }
    @Test fun sameIdentityPdfMigrationCannotBindAnotherDocumentEvenWhenPdfIsShared() = directory {dir->
        val pdf=dir.child("original.pdf").apply {setText("shared PDF")};val hash=BundleMigration.hash(pdf)
        val a=BundleIdentity(owner,hash);val b=a.copy(documentId=Uuids.random())
        val source=make(dir,b,pdf=pdf);val before=source.fileBytes();val files=dir.fileNames().toSet()
        assertFailsWith<IllegalArgumentException> {BundlePdf.prepare(source,dir,a,pdf)}
        assertArrayEquals(before,source.fileBytes());assertEquals(files,dir.fileNames().toSet())
    }
    @Test fun pageMarkerOnCanvasIsRejectedAndLegacyCanvasCopiesKeepTheirRawPayload() = directory {dir->
        val manifest="""{"format":"xcanvas","version":1,"items":[],"future":"${"x".repeat(1048600)}"}"""
        val bad=make(dir,manifest=manifest)
        assertFailsWith<IllegalArgumentException> {BundleMigration.prepare(bad,dir)}
        val canvas=make(dir,manifest=manifest,anchored=false);val staged=BundleMigration.prepare(canvas,dir,copy=true)
        try {
            assertNotEquals(owner,staged.identity.documentId)
            assertArrayEquals(manifest.encodeToByteArray(),entry(staged.file,"manifest.json"))
            assertNull(BundleHeader.read(staged.file).anchors)
        } finally {staged.file.remove()}
    }
    @Test fun anchoredReferenceOnlySourceCannotSilentlyDiscardAnUnexpectedEmbeddedPdf() = directory {dir->
        val a=dir.child("A.pdf").apply {setText("protected PDF A")}
        val b=dir.child("B.pdf").apply {setText("unexpected PDF B")}
        val identity=BundleIdentity(owner,BundleMigration.hash(a),true)
        val source=make(dir,identity,pdf=b);val before=source.fileBytes();val out=Buffer()
        assertFailsWith<IllegalArgumentException> {BundlePdf.write(source,out,identity,a,false)}
        assertEquals(0,out.size.toInt());assertArrayEquals(before,source.fileBytes())
        assertEquals("protected PDF A",a.fileText());assertEquals("unexpected PDF B",b.fileText())
    }
    @Test fun cancellationAndSourceChangeDuringCopyKeepExternalDataAndCleanOwnedCandidates() = directory {dir->
        val source=make(dir);val original=source.fileBytes()
        assertFailsWith<IllegalStateException> {BundleMigration.prepare(source,dir,copy=true,cancelled={dir.fileNames().any {it.startsWith("identity-")}})}
        assertArrayEquals(original,source.fileBytes());assertEquals(setOf(source.assetPath().name),dir.fileNames().toSet())
        var changed=false
        assertFailsWith<IllegalStateException> {BundleMigration.prepare(source,dir,copy=true,cancelled={
            if(!changed && dir.fileNames().any {it.startsWith("identity-")}) {changed=true;source.appendUtf8("external edit")}
            false
        })}
        assertTrue(changed);assertTrue(source.fileText().endsWith("external edit"));assertEquals(setOf(source.assetPath().name),dir.fileNames().toSet())
    }
    @Test fun sourceAtoBtoAWhileCopyingCannotMixPayloadFromOutsideTheSnapshot() = directory {dir->
        val source=make(dir);val original=source.fileBytes();var phase=0
        val staged=BundleMigration.prepare(source,dir,copy=true,cancelled={
            if(dir.fileNames().any {it.startsWith("identity-tail-")}) {
                if(phase==0) {source.setText("temporary replacement B");phase=1}
                else if(phase==1) {source.setBytes(original);phase=2}
            }
            false
        })
        try {assertEquals(2,phase);assertIndependentCopy(source,staged.file);assertArrayEquals(original,source.fileBytes())}
        finally {staged.file.remove()}
        assertEquals(setOf(source.assetPath().name),dir.fileNames().toSet())
    }
    @Test fun capturedStreamSnapshotStaysConsistentWhenTheExternalFileChangesAfterCapture() = directory {dir->
        val source=make(dir);val original=source.fileBytes();val output=dir.child("copy.xnote")
        BundleSnapshot.fromInput(source.input(),dir).use {snapshot->
            source.setText("a different external file")
            output.fileSink().use {BundlePdf.write(snapshot,it,BundleIdentity(),null,false)}
            assertIndependentCopy(dir.child("captured.zip").apply {setBytes(original)},output)
            assertEquals("a different external file",source.fileText())
        }
    }
    @Test fun fileExportDetectsExternalChangeAndCancellationWithoutDeletingBorrowedPdf() = directory {dir->
        val pdf=dir.child("original.pdf").apply {setText("immutable PDF")}
        val identity=BundleIdentity(owner,BundleMigration.hash(pdf),true);val source=make(dir,identity)
        val out=Buffer();var changed=false
        assertFailsWith<IllegalStateException> {BundlePdf.write(source,out,identity,pdf,false,cancelled={
            if(!changed && out.size.toInt()>0) {source.appendUtf8("changed while exporting");changed=true}
            false
        })}
        assertTrue(changed);assertEquals("immutable PDF",pdf.fileText())
        val partial=Buffer()
        assertFailsWith<IllegalStateException> {BundlePdf.write(source,partial,identity,pdf,false,cancelled={partial.size.toInt()>0})}
        assertTrue(KFiles.system.exists(pdf.assetPath()));assertFalse(dir.fileNames().any {it.startsWith("bundle-source-") || it.startsWith("copy-manifest-")})
    }

    @Test fun legacyFormatAfterALargePrefixCanBeCancelledWhileSkippingThePrefix() {
        val bytes=("{\"prefix\":\""+"x".repeat(200000)+"\",\"format\":\"xcanvas\",\"version\":1,\"items\":[]}").encodeToByteArray()
        var read=0
        val input=object:ForwardingSource(bytes.asSource()) {
            override fun read(sink:Buffer,byteCount:Long)=super.read(sink,byteCount).also {if(it>0) read+=it.toInt()}
        }
        input.use {
            assertFailsWith<IllegalStateException> {BundleCopyPlan.validateLegacyFormat(it,cancelled={read>=32768})}
        }
        assertTrue("must stop inside the prefix, not after consuming it",read<bytes.size/2)
    }

    @Test fun exportingCannotReplaceTheRecordedPdfFingerprintEvenWithAMatchingReplacementFile() = directory {dir->
        val a=dir.child("A.pdf").apply {setText("original PDF A")}
        val b=dir.child("B.pdf").apply {setText("replacement PDF B")}
        val original=BundleIdentity(owner,BundleMigration.hash(a),true);val source=make(dir,original)
        for(id in listOf(owner,Uuids.random())) {
            val out=Buffer()
            assertFailsWith<IllegalArgumentException> {BundlePdf.write(source,out,original.copy(documentId=id,pdfResource=BundleMigration.hash(b)),b,false)}
            assertEquals(0,out.size.toInt())
        }
        assertEquals("original PDF A",a.fileText());assertEquals("replacement PDF B",b.fileText())
    }
    @Test fun copiesAndPortableExportsRejectOrphanLocalManifestsBeforeWriting() = directory {dir->
        val source=make(dir,comment=false);val extra=dir.child("extra.zip")
        bundleWriter(extra.fileSink()).use {z->
            z.putDeflated("manifest.json",pattern.replace("@P1@",p2).replace("@P2@",p1).encodeToByteArray())
        }
        openReadWrite(source.assetPath()).use {dest->openRead(extra.assetPath()).use {tail->
            val old=ZipTail.read(dest)!!;val addition=ZipTail.read(tail)!!
            check(ZipTail.splice(dest,old.offset,old.entries.filter {it.name!="manifest.json"},tail,addition)>=0)
        }}
        val before=source.fileBytes();val files=dir.fileNames().toSet();val out=Buffer()
        assertFailsWith<IllegalArgumentException> {BundleMigration.prepare(source,dir,copy=true)}
        assertFailsWith<IllegalArgumentException> {BundlePdf.write(source,out,BundleIdentity(owner),null,false)}
        assertEquals(0,out.size.toInt());assertArrayEquals(before,source.fileBytes());assertEquals(files,dir.fileNames().toSet())
    }

}
