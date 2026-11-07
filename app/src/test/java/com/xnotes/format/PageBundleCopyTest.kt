package com.xnotes.format

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageBundleCopyTest {
    private val owner="00000000-0000-0000-0000-000000000001"
    private val p1="00000000-0000-0000-0000-000000000002"
    private val p2="00000000-0000-0000-0000-000000000003"
    private val pattern=""" {"format":"xnote","version":1,"pages":[{"reference_id" : "@P1@","items":[{"kind":"future","reference_id":"$p1","samples":[[-0,1.2500e+02,0.1234567890123456789]]}]},{"reference_id":"@P2@","items":[]}],"has_pdf":true,"future":{"pages":[{"reference_id":"$p2"}]}} """
    private fun directory(block:(File)->Unit) {
        val dir=Files.createTempDirectory("page-copy-bundle-").toFile()
        try {block(dir)} finally {dir.deleteRecursively()}
    }
    private fun make(dir:File,identity:BundleIdentity=BundleIdentity(owner),manifest:String=pattern.replace("@P1@",p1).replace("@P2@",p2),anchored:Boolean=true,pdf:File?=null,comment:Boolean=true):File =
        File.createTempFile("input-",".zip",dir).also {file->ZipOutputStream(file.outputStream()).use {z->
            if(comment) z.setComment("archive comment")
            fun entry(name:String,bytes:ByteArray) {z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}
            entry("manifest.json",manifest.toByteArray());entry("future/raw",byteArrayOf(0,1,2,-1))
            pdf?.let {entry(BundlePdf.ENTRY,it.readBytes())};identity.write(z)
            if(anchored) entry(AnchorMetadata.ENTRY,""" { "documentId":"${identity.documentId}", "format":"xnotes-page-anchors", "version":1 } """.toByteArray())
        }}
    private fun entry(file:File,name:String)=ZipFile(file).use {z->z.getInputStream(z.getEntry(name)).use {it.readBytes()}}
    private fun assertIndependentCopy(source:File,copy:File) {
        val header=BundleHeader.read(copy)
        assertNotEquals(owner,header.identity!!.documentId)
        assertEquals(header.identity.documentId,header.anchors!!.documentId)
        val manifest=entry(copy,"manifest.json");val pages=JSONObject(String(manifest)).getJSONArray("pages")
        val fresh=(0 until pages.length()).map {pages.getJSONObject(it).getString("reference_id")}
        assertEquals(2,fresh.toSet().size);assertTrue(fresh.none {it==p1 || it==p2})
        assertArrayEquals(pattern.replace("@P1@",fresh[0]).replace("@P2@",fresh[1]).toByteArray(),manifest)
        assertArrayEquals(entry(source,"future/raw"),entry(copy,"future/raw"))
    }
    @Test fun identityCopyRebindsMarkerAndRemintsOnlyPageIds() = directory {dir->
        val source=make(dir);val original=source.readBytes()
        val staged=BundleMigration.prepare(source,dir,copy=true)
        try {assertIndependentCopy(source,staged.file);assertArrayEquals(original,source.readBytes())}
        finally {staged.file.delete()}
        assertEquals(setOf(source.name),dir.list()!!.toSet())
    }
    @Test fun portableCopyEmbedsUnchangedPdfAndRebindsPageReferences() = directory {dir->
        val pdf=File(dir,"original.pdf").apply {writeText("%PDF-1.7 immutable original")}
        val id=BundleIdentity(owner,BundleMigration.hash(pdf),true)
        val source=make(dir,id);val original=source.readBytes();val bytes=pdf.readBytes()
        val copy=File(dir,"copy.xnote")
        copy.outputStream().use {BundlePdf.write(source,it,id.copy(documentId=UUID.randomUUID().toString()),pdf,false)}
        assertIndependentCopy(source,copy)
        assertArrayEquals(bytes,entry(copy,BundlePdf.ENTRY));assertArrayEquals(bytes,pdf.readBytes());assertArrayEquals(original,source.readBytes())
        assertFalse(BundleHeader.read(copy).identity!!.externalPdf)
    }
    @Test fun sameIdentityPortableExportRetainsManifestAndRawMarkerBytes() = directory {dir->
        val pdf=File(dir,"original.pdf").apply {writeText("%PDF-1.7 immutable")}
        val id=BundleIdentity(owner,BundleMigration.hash(pdf),true);val source=make(dir,id)
        val output=File(dir,"portable.xnote")
        output.outputStream().use {BundlePdf.write(source,it,id,pdf,false)}
        assertArrayEquals(entry(source,"manifest.json"),entry(output,"manifest.json"))
        assertArrayEquals(entry(source,AnchorMetadata.ENTRY),entry(output,AnchorMetadata.ENTRY))
        assertEquals(owner,BundleHeader.read(output).identity!!.documentId)
    }
    @Test fun sameIdentityExportRefusesBrokenPageContractBeforeWritingAnyOutput() = directory {dir->
        for(manifest in listOf("""{"format":"xnote","version":1,"pages":[{}]}""", """{"format":"xnote","version":1,"pages":[{"reference_id":"$p1"},{"reference_id":"$p1"}]}""")) {
            val source=make(dir,manifest=manifest);val out=ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {BundlePdf.write(source,out,BundleIdentity(owner),null,false)}
            assertEquals(0,out.size())
        }
    }
    @Test fun sameIdentityPdfMigrationCannotBindAnotherDocumentEvenWhenPdfIsShared() = directory {dir->
        val pdf=File(dir,"original.pdf").apply {writeText("shared PDF")};val hash=BundleMigration.hash(pdf)
        val a=BundleIdentity(owner,hash);val b=a.copy(documentId=UUID.randomUUID().toString())
        val source=make(dir,b,pdf=pdf);val before=source.readBytes();val files=dir.list()!!.toSet()
        assertThrows(IllegalArgumentException::class.java) {BundlePdf.prepare(source,dir,a,pdf)}
        assertArrayEquals(before,source.readBytes());assertEquals(files,dir.list()!!.toSet())
    }
    @Test fun pageMarkerOnCanvasIsRejectedAndLegacyCanvasCopiesKeepTheirRawPayload() = directory {dir->
        val manifest="""{"format":"xcanvas","version":1,"items":[],"future":"${"x".repeat(1048600)}"}"""
        val bad=make(dir,manifest=manifest)
        assertThrows(IllegalArgumentException::class.java) {BundleMigration.prepare(bad,dir)}
        val canvas=make(dir,manifest=manifest,anchored=false);val staged=BundleMigration.prepare(canvas,dir,copy=true)
        try {
            assertNotEquals(owner,staged.identity.documentId)
            assertArrayEquals(manifest.toByteArray(),entry(staged.file,"manifest.json"))
            assertNull(BundleHeader.read(staged.file).anchors)
        } finally {staged.file.delete()}
    }
    @Test fun anchoredReferenceOnlySourceCannotSilentlyDiscardAnUnexpectedEmbeddedPdf() = directory {dir->
        val a=File(dir,"A.pdf").apply {writeText("protected PDF A")}
        val b=File(dir,"B.pdf").apply {writeText("unexpected PDF B")}
        val identity=BundleIdentity(owner,BundleMigration.hash(a),true)
        val source=make(dir,identity,pdf=b);val before=source.readBytes();val out=ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {BundlePdf.write(source,out,identity,a,false)}
        assertEquals(0,out.size());assertArrayEquals(before,source.readBytes())
        assertEquals("protected PDF A",a.readText());assertEquals("unexpected PDF B",b.readText())
    }
    @Test fun cancellationAndSourceChangeDuringCopyKeepExternalDataAndCleanOwnedCandidates() = directory {dir->
        val source=make(dir);val original=source.readBytes()
        assertThrows(IllegalStateException::class.java) {BundleMigration.prepare(source,dir,copy=true,cancelled={dir.list()!!.any {it.startsWith("identity-")}})}
        assertArrayEquals(original,source.readBytes());assertEquals(setOf(source.name),dir.list()!!.toSet())
        var changed=false
        assertThrows(IllegalStateException::class.java) {BundleMigration.prepare(source,dir,copy=true,cancelled={
            if(!changed && dir.list()!!.any {it.startsWith("identity-")}) {changed=true;source.appendText("external edit")}
            false
        })}
        assertTrue(changed);assertTrue(source.readText().endsWith("external edit"));assertEquals(setOf(source.name),dir.list()!!.toSet())
    }
    @Test fun sourceAtoBtoAWhileCopyingCannotMixPayloadFromOutsideTheSnapshot() = directory {dir->
        val source=make(dir);val original=source.readBytes();var phase=0
        val staged=BundleMigration.prepare(source,dir,copy=true,cancelled={
            if(dir.list()!!.any {it.startsWith("identity-tail-")}) {
                if(phase==0) {source.writeText("temporary replacement B");phase=1}
                else if(phase==1) {source.writeBytes(original);phase=2}
            }
            false
        })
        try {assertEquals(2,phase);assertIndependentCopy(source,staged.file);assertArrayEquals(original,source.readBytes())}
        finally {staged.file.delete()}
        assertEquals(setOf(source.name),dir.list()!!.toSet())
    }
    @Test fun capturedStreamSnapshotStaysConsistentWhenTheExternalFileChangesAfterCapture() = directory {dir->
        val source=make(dir);val original=source.readBytes();val output=File(dir,"copy.xnote")
        BundleSnapshot.fromInput(source.inputStream(),dir).use {snapshot->
            source.writeText("a different external file")
            output.outputStream().use {BundlePdf.write(snapshot,it,BundleIdentity(),null,false)}
            assertIndependentCopy(File(dir,"captured.zip").apply {writeBytes(original)},output)
            assertEquals("a different external file",source.readText())
        }
    }
    @Test fun fileExportDetectsExternalChangeAndCancellationWithoutDeletingBorrowedPdf() = directory {dir->
        val pdf=File(dir,"original.pdf").apply {writeText("immutable PDF")}
        val identity=BundleIdentity(owner,BundleMigration.hash(pdf),true);val source=make(dir,identity)
        val out=ByteArrayOutputStream();var changed=false
        assertThrows(IllegalStateException::class.java) {BundlePdf.write(source,out,identity,pdf,false,cancelled={
            if(!changed && out.size()>0) {source.appendText("changed while exporting");changed=true}
            false
        })}
        assertTrue(changed);assertEquals("immutable PDF",pdf.readText())
        val partial=ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) {BundlePdf.write(source,partial,identity,pdf,false,cancelled={partial.size()>0})}
        assertTrue(pdf.exists());assertFalse(dir.list()!!.any {it.startsWith("bundle-source-") || it.startsWith("copy-manifest-")})
    }

    @Test fun legacyFormatAfterALargePrefixCanBeCancelledWhileSkippingThePrefix() {
        val bytes=("{\"prefix\":\""+"x".repeat(200000)+"\",\"format\":\"xcanvas\",\"version\":1,\"items\":[]}").toByteArray()
        var read=0
        val input=object:java.io.ByteArrayInputStream(bytes) {
            override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,len).also {if(it>0) read+=it}
        }
        input.use {
            assertThrows(IllegalStateException::class.java) {BundleCopyPlan.validateLegacyFormat(it,cancelled={read>=32768})}
        }
        assertTrue("must stop inside the prefix, not after consuming it",read<bytes.size/2)
    }

    @Test fun exportingCannotReplaceTheRecordedPdfFingerprintEvenWithAMatchingReplacementFile() = directory {dir->
        val a=File(dir,"A.pdf").apply {writeText("original PDF A")}
        val b=File(dir,"B.pdf").apply {writeText("replacement PDF B")}
        val original=BundleIdentity(owner,BundleMigration.hash(a),true);val source=make(dir,original)
        for(id in listOf(owner,UUID.randomUUID().toString())) {
            val out=ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {BundlePdf.write(source,out,original.copy(documentId=id,pdfResource=BundleMigration.hash(b)),b,false)}
            assertEquals(0,out.size())
        }
        assertEquals("original PDF A",a.readText());assertEquals("replacement PDF B",b.readText())
    }
    @Test fun copiesAndPortableExportsRejectOrphanLocalManifestsBeforeWriting() = directory {dir->
        val source=make(dir,comment=false);val extra=File(dir,"extra.zip")
        ZipOutputStream(extra.outputStream()).use {z->
            z.putNextEntry(ZipEntry("manifest.json"));z.write(pattern.replace("@P1@",p2).replace("@P2@",p1).toByteArray());z.closeEntry()
        }
        java.io.RandomAccessFile(source,"rw").use {dest->java.io.FileInputStream(extra).use {tail->
            val old=ZipTail.read(dest.channel)!!;val addition=ZipTail.read(tail.channel)!!
            check(ZipTail.splice(dest.channel,old.offset,old.entries.filter {it.name!="manifest.json"},tail.channel,addition)>=0)
        }}
        val before=source.readBytes();val files=dir.list()!!.toSet();val out=ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {BundleMigration.prepare(source,dir,copy=true)}
        assertThrows(IllegalArgumentException::class.java) {BundlePdf.write(source,out,BundleIdentity(owner),null,false)}
        assertEquals(0,out.size());assertArrayEquals(before,source.readBytes());assertEquals(files,dir.list()!!.toSet())
    }

}
