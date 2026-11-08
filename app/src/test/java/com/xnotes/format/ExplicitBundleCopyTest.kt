package com.xnotes.format

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ExplicitBundleCopyTest {
    private val owner="00000000-0000-0000-0000-000000000001"
    private val target="00000000-0000-0000-0000-000000000004"
    private val p1="00000000-0000-0000-0000-000000000002"
    private val p2="00000000-0000-0000-0000-000000000003"
    private val future="""{"kind":"future-ink","samples":[[-0,1.2500e+02,0.1234567890123456789,17]],"unknown":"保留 🖊"}"""
    private fun note(pdf:Boolean=false)=""" {"format":"xnote","version":1,"pages":[{"items":[$future]}],"has_pdf":$pdf,"future":{"raw":"untouched"}} """
    private fun canvas()=""" {"format":"xcanvas","version":1,"items":[$future],"future":{"raw":"untouched"}} """
    private fun marker(id:String=owner)=AnchorMetadata(id).bytes()
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun directory(block:(File)->Unit) {
        val dir=Files.createTempDirectory("explicit-bundle-copy-").toFile()
        try {block(dir)} finally {dir.deleteRecursively()}
    }
    private fun bundle(dir:File,manifest:String=note(),identity:BundleIdentity?=BundleIdentity(owner),
                       anchors:ByteArray?=null,pdf:ByteArray?=null,comment:Boolean=false):File =
        File.createTempFile("source-",".zip",dir).also {file->ZipOutputStream(file.outputStream()).use {zip->
            if(comment) zip.setComment("preserve archive comment")
            fun entry(name:String,bytes:ByteArray) {zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}
            entry("manifest.json",manifest.toByteArray(Charsets.UTF_8))
            entry("future/raw.bin",ByteArray(65537) {it.toByte()})
            pdf?.let {entry(BundlePdf.ENTRY,it)}
            identity?.write(zip);anchors?.let {entry(AnchorMetadata.ENTRY,it)}
        }}
    private fun entry(file:File,name:String)=ZipFile(file).use {zip->zip.getInputStream(zip.getEntry(name)).use {it.readBytes()}}
    private fun names(dir:File)=dir.list()!!.toSet()
    private fun assertRawPayload(source:File,candidate:File,except:Set<String> = emptySet()) {
        val ignored=except+BundleIdentity.ENTRY
        assertEquals(BundleMigration.fingerprints(source).filterKeys {it !in ignored},
            BundleMigration.fingerprints(candidate).filterKeys {it !in ignored})
    }

    @Test fun oldPreparePositionalArgumentsAndTrailingCancellationLambdaRemainCompatible()=directory {dir->
        val source=bundle(dir,identity=null);val original=source.readBytes()
        val migration=BundleMigration.prepare(source,dir,false,{false})
        try {assertRawPayload(source,migration.file)} finally {migration.file.delete()}
        val copy=BundleMigration.prepare(source,dir,true) {false}
        try {assertRawPayload(source,copy.file);assertNotNull(copy.identity.documentId)} finally {copy.file.delete()}
        val before=names(dir)
        assertThrows(IllegalStateException::class.java) {BundleMigration.prepare(source,dir,true) {true}}
        assertArrayEquals(original,source.readBytes());assertEquals(before,names(dir))
        val trailing=bundle(dir,note()+" trailing")
        assertEquals("xnote",BundleReferenceInfo.inspect(trailing,true).format)
        assertThrows(JsonPullException::class.java) {BundleReferenceInfo.inspectStrict(trailing)}
    }

    @Test fun legacyNoteAndCanvasUseTheRequestedIdWithoutReencodingAnyPayload()=directory {dir->
        val largeCanvas=canvas().replace("untouched","x".repeat(1_048_600))
        for(manifest in listOf(note(),canvas(),largeCanvas)) for(comment in listOf(false,true)) {
            val source=bundle(dir,manifest,identity=null,comment=comment)
            val original=source.readBytes();val before=names(dir)
            val copy=BundleMigration.prepareCopy(source,dir,target) {false}
            try {
                assertEquals(target,copy.identity.documentId);assertEquals(target,BundleIdentity.read(copy.file)!!.documentId)
                assertEquals(BundleMigration.hash(source),copy.sourceHash);assertNull(BundleHeader.read(copy.file).anchors)
                assertArrayEquals(manifest.toByteArray(Charsets.UTF_8),entry(copy.file,"manifest.json"))
                assertRawPayload(source,copy.file);assertArrayEquals(original,source.readBytes())
            } finally {copy.file.delete()}
            assertEquals(before,names(dir))
        }
    }

    @Test fun copyingOnlyChangesDocumentIdAndPreservesEmbeddedAndExternalPdfIdentityFields()=directory {dir->
        val pdf="%PDF-1.7\noriginal bytes\u0000\u0001".toByteArray();val digest=hash(pdf)
        val cases=listOf(
            Triple<BundleIdentity?,ByteArray?,Boolean>(BundleIdentity(owner),null,false),
            Triple(BundleIdentity(owner,digest),pdf,true),
            Triple(BundleIdentity(owner,digest,true),null,true),
            Triple(null,pdf,true))
        for((identity,embedded,hasPdf) in cases) {
            val source=bundle(dir,note(hasPdf),identity,pdf=embedded);val original=source.readBytes()
            val copy=BundleMigration.prepareCopy(source,dir,target)
            try {
                val actual=BundleIdentity.read(copy.file)!!
                assertEquals(target,actual.documentId);assertEquals(identity?.pdfResource,actual.pdfResource)
                assertEquals(identity?.externalPdf ?: false,actual.externalPdf)
                assertEquals(BundleIdentity(target,identity?.pdfResource,identity?.externalPdf ?: false),copy.identity)
                assertRawPayload(source,copy.file);assertArrayEquals(original,source.readBytes())
                if(embedded!=null) assertArrayEquals(pdf,entry(copy.file,BundlePdf.ENTRY))
                BundleReferenceInfo.inspect(copy.file,true)
            } finally {copy.file.delete()}
        }
    }

    @Test fun pageCopyRebindsTheRequestedDocumentAndOnlyTheOrderedPageIdLiterals()=directory {dir->
        val pattern=""" {"format":"xnote","version":1,"pages":[{"reference_id" : "@FIRST@","items":[$future],"future":"$p2"},{"reference_id":"@SECOND@","items":[]}],"has_pdf":false,"future":{"pages":[{"reference_id":"$p1"}]}} """
        val manifest=pattern.replace("@FIRST@",p2).replace("@SECOND@",p1)
        val source=bundle(dir,manifest,anchors=marker());val original=source.readBytes();val before=names(dir)
        val copy=BundleMigration.prepareCopy(source,dir,target)
        try {
            val header=BundleHeader.read(copy.file)
            assertEquals(target,header.identity!!.documentId);assertEquals(target,header.anchors!!.documentId)
            val raw=entry(copy.file,"manifest.json");val pages=JSONObject(String(raw,Charsets.UTF_8)).getJSONArray("pages")
            val ids=(0 until pages.length()).map {pages.getJSONObject(it).getString("reference_id")}
            assertEquals(2,ids.toSet().size);assertTrue(ids.none {it==p1 || it==p2})
            assertEquals(ids,BundleReferenceInfo.inspect(copy.file,true).orderedPageIds)
            assertArrayEquals(pattern.replace("@FIRST@",ids[0]).replace("@SECOND@",ids[1]).toByteArray(Charsets.UTF_8),raw)
            assertArrayEquals(marker(target),entry(copy.file,AnchorMetadata.ENTRY))
            assertRawPayload(source,copy.file,setOf("manifest.json",AnchorMetadata.ENTRY));assertArrayEquals(original,source.readBytes())
        } finally {copy.file.delete()}
        assertEquals(before,names(dir))
    }

    @Test fun sameOrInvalidTargetIdsAreRejectedWithoutChangingSourceOrLeavingCandidates()=directory {dir->
        val source=bundle(dir);val original=source.readBytes();val before=names(dir)
        for(id in listOf(owner,"not-a-uuid","1-1-1-1-1","AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")) {
            assertThrows(IllegalArgumentException::class.java) {BundleMigration.prepareCopy(source,dir,id)}
            assertArrayEquals(original,source.readBytes());assertEquals(before,names(dir))
        }
    }

    @Test fun invalidPageManifestAndPdfContractsAreRejectedWithAllOriginalBytesKept()=directory {dir->
        data class Invalid(val manifest:String=note(),val anchors:ByteArray?=null,val identity:BundleIdentity?=BundleIdentity(owner),
                           val pdf:ByteArray?=null,val failure:Class<out Throwable> = IllegalArgumentException::class.java)
        val one="""{"format":"xnote","version":1,"pages":[{"reference_id":"$p1","items":[]}]}"""
        val duplicate="""{"format":"xnote","version":1,"pages":[{"reference_id":"$p1"},{"reference_id":"$p1"}]}"""
        val pdf="%PDF-1.7 original".toByteArray();val pdfId=hash(pdf)
        val invalid=listOf(
            Invalid(one,marker(target)),
            Invalid(one,"""{"format":"xnotes-page-anchors","version":2,"documentId":"$owner"}""".toByteArray()),
            Invalid(duplicate,marker()),Invalid(note(),marker()),Invalid(canvas(),marker()),Invalid(one),
            Invalid(note()+" trailing",failure=JsonPullException::class.java),
            Invalid(note().replace("\"version\":1","\"version\":2")),
            Invalid(note(true)),Invalid(note(),pdf=pdf),
            Invalid(note(true),identity=BundleIdentity(owner,"a".repeat(64)),pdf=pdf),
            Invalid(note(true),identity=BundleIdentity(owner,pdfId,true),pdf=pdf))
        for(case in invalid) {
            val source=bundle(dir,case.manifest,case.identity,case.anchors,case.pdf)
            val original=source.readBytes();val before=names(dir)
            assertThrows(case.failure) {BundleMigration.prepareCopy(source,dir,target)}
            assertArrayEquals(original,source.readBytes());assertEquals(before,names(dir))
        }
    }

    @Test fun cancellationVerificationAndExternalChangesKeepSourceAndCleanOnlyOwnedFiles()=directory {dir->
        val source=bundle(dir);val original=source.readBytes();val before=names(dir)
        for(phase in 0..2) {
            var reached=false
            assertThrows(IllegalStateException::class.java) {BundleMigration.prepareCopy(source,dir,target,cancelled={
                val cancel=when(phase) {
                    0->true
                    1->dir.list()!!.any {it.startsWith("identity-")}
                    else->dir.listFiles().orEmpty().filter {it.name.startsWith("identity-") && !it.name.startsWith("identity-tail-")}
                        .any {runCatching {BundleIdentity.read(it)?.documentId==target}.getOrDefault(false)}
                }
                if(cancel) reached=true
                cancel
            })}
            assertTrue("Cancellation phase $phase was not reached",reached)
            assertArrayEquals(original,source.readBytes());assertEquals(before,names(dir))
        }
        var changed=false
        assertThrows(IllegalStateException::class.java) {BundleMigration.prepareCopy(source,dir,target,cancelled={
            if(!changed && dir.list()!!.any {it.startsWith("identity-tail-")}) {source.appendText("external edit");changed=true}
            false
        })}
        assertTrue(changed);assertArrayEquals(original+"external edit".toByteArray(),source.readBytes());assertEquals(before,names(dir))
        source.writeBytes(original);var phase=0
        val copy=BundleMigration.prepareCopy(source,dir,target,cancelled={
            if(dir.list()!!.any {it.startsWith("identity-tail-")}) {
                if(phase==0) {source.writeText("temporary B");phase=1}
                else if(phase==1) {source.writeBytes(original);phase=2}
            }
            false
        })
        try {assertEquals(2,phase);assertEquals(target,copy.identity.documentId);assertRawPayload(source,copy.file);assertArrayEquals(original,source.readBytes())}
        finally {copy.file.delete()}
        assertEquals(before,names(dir))
    }
}
