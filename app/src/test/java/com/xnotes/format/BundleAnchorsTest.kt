package com.xnotes.format

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BundleAnchorsTest {
    private val identity=BundleIdentity("00000000-0000-0000-0000-000000000001")
    private fun directory(block:(File)->Unit) {
        val dir=Files.createTempDirectory("anchors-").toFile()
        try {block(dir)} finally {dir.deleteRecursively()}
    }
    private fun bundle(dir:File,canvas:Boolean=false,marker:ByteArray?=null,comment:Boolean=true,identityBytes:ByteArray?=null):File {
        val manifest=if(canvas) """{"format":"xcanvas","version":1,"items":[]}""" else """{"format":"xnote","version":1,"pages":[{"items":[{"kind":"stroke","samples":[[-0,1.2000,0.3333333333333333333,1e+02]]}]}]}"""
        return File.createTempFile("source-",".zip",dir).also { file -> ZipOutputStream(file.outputStream()).use { z ->
            if(comment) z.setComment("future archive metadata")
            fun entry(name:String,bytes:ByteArray) {z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}
            // Deliberately non-tail manifest with unknown and identity entries after it.
            entry("manifest.json",manifest.toByteArray());entry("assets/source.pdf",ByteArray(65537) {it.toByte()})
            entry("future/payload.bin",byteArrayOf(0,-1,2,3))
            if(identityBytes==null) identity.write(z) else entry(BundleIdentity.ENTRY,identityBytes)
            marker?.let {entry(BundleAnchors.ENTRY,it)}
        } }
    }
    private fun bytes(file:File,name:String)=ZipFile(file).use {z->z.getInputStream(z.getEntry(name)).use {it.readBytes()}}
    private fun lieAboutSize(source:File,name:String):ByteArray {
        val raw=source.readBytes();val signature=byteArrayOf(0x50,0x4b,0x01,0x02);var at=0
        while(at<raw.size-46) {
            if(raw.copyOfRange(at,at+4).contentEquals(signature)) {
                val length=(raw[at+28].toInt() and 255)+((raw[at+29].toInt() and 255) shl 8)
                if(String(raw,at+46,length)==name) {repeat(4) {raw[at+24+it]=0};raw[at+24]=1;break}
            }
            at++
        }
        assertTrue(at<raw.size-46);source.writeBytes(raw);return raw
    }
    @Test fun stagedUpgradeKeepsNonManifestPayloadAndSequentialReaderSeesExactlyTheNewManifest() = directory { dir ->
        val source=bundle(dir);val before=source.readBytes();val fingerprints=BundleMigration.fingerprints(source)
        val staged=BundleAnchors.prepare(source,dir)
        assertArrayEquals(before,source.readBytes())
        assertEquals(fingerprints.filterKeys {it!="manifest.json"},BundleMigration.fingerprints(staged.file).filterKeys {it!="manifest.json" && it!=BundleAnchors.ENTRY})
        val manifest=bytes(staged.file,"manifest.json")
        assertTrue(String(manifest).contains("reference_id"));assertEquals(1,staged.anchorCount)
        val sequential=mutableListOf<String>()
        ZipInputStream(staged.file.inputStream()).use {z->var e=z.nextEntry;while(e!=null) {sequential+=e.name;if(e.name=="manifest.json") assertArrayEquals(manifest,z.readBytes());z.closeEntry();e=z.nextEntry}}
        assertEquals(1,sequential.count {it=="manifest.json"});assertTrue("future/payload.bin" in sequential)
        assertEquals(ZipFile(staged.file).use {it.entries().asSequence().map {e->e.name}.toList()},sequential)
        BundleAnchors.publish(staged,source)
        val second=BundleAnchors.prepare(source,dir,expectedAnchored=true)
        assertArrayEquals(manifest,bytes(second.file,"manifest.json"));assertArrayEquals(bytes(source,BundleAnchors.ENTRY),bytes(second.file,BundleAnchors.ENTRY));second.file.delete()
        assertEquals(listOf(source.name),dir.list()!!.toList())
    }
    @Test fun canvasesNeedNoPageUpgradeAndKnownMissingPageIdentitiesAreRejected() = directory {dir->
        val empty=bundle(dir,canvas=true);val before=empty.readBytes()
        try {BundleAnchors.prepare(empty,dir);fail("canvas has no page anchors")} catch(_:IllegalArgumentException) {}
        assertArrayEquals(before,empty.readBytes())
        val lost=bundle(dir,marker=AnchorMetadata(identity.documentId).bytes())
        try {BundleAnchors.prepare(lost,dir);fail("marker with missing page IDs must fail")} catch(_:IllegalArgumentException) {}
        val legacy=bundle(dir)
        try {BundleAnchors.prepare(legacy,dir,expectedAnchored=true);fail("known upgrade missing marker")} catch(_:IllegalArgumentException) {}
    }
    @Test fun cancellationAndExternalChangeKeepSourceAndRecoveryCandidate() = directory {dir->
        val source=bundle(dir);val before=source.readBytes()
        try {BundleAnchors.prepare(source,dir,cancelled={true});fail("cancel")} catch(_:IllegalStateException) {}
        assertArrayEquals(before,source.readBytes());assertEquals(1,dir.list()!!.size)
        val staged=BundleAnchors.prepare(source,dir);source.appendText("changed")
        val changed=source.readBytes()
        try {BundleAnchors.publish(staged,source);fail("conflict")} catch(_:IllegalStateException) {}
        assertArrayEquals(changed,source.readBytes());assertTrue(staged.file.exists());staged.file.delete()
    }
    @Test fun orphanLocalManifestThatDisagreesWithCentralDirectoryIsRejected() = directory {dir->
        val source=bundle(dir,comment=false);val extra=File(dir,"extra.zip")
        ZipOutputStream(extra.outputStream()).use {z->z.putNextEntry(ZipEntry("manifest.json"));z.write("""{"format":"xcanvas","version":1,"items":[]}""".toByteArray());z.closeEntry()}
        RandomAccessFile(source,"rw").use {dest->FileInputStream(extra).use {tail->
            val old=ZipTail.read(dest.channel)!!;val addition=ZipTail.read(tail.channel)!!
            check(ZipTail.splice(dest.channel,old.offset,old.entries.filter {it.name!="manifest.json"},tail.channel,addition)>=0)
        }}
        val firstLocal=ZipInputStream(source.inputStream()).use {z->assertEquals("manifest.json",z.nextEntry.name);z.readBytes()}
        assertFalse(firstLocal.contentEquals(bytes(source,"manifest.json")))
        val before=source.readBytes()
        try {BundleAnchors.prepare(source,dir);fail("sequential reader would see old manifest")} catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {}
        assertArrayEquals(before,source.readBytes());assertEquals(2,dir.list()!!.size)
    }
    @Test fun sourceMutationsDuringPrepareDoNotAffectTheImmutableSnapshot() = directory {dir->
        val source=bundle(dir);val original=source.readBytes();var changed=false
        try {
            BundleAnchors.prepare(source,dir,cancelled={
                if(!changed && dir.listFiles()!!.any {it.name.startsWith("anchor-candidate-")}) {changed=true;source.appendText("external change")}
                false
            }).also {it.file.delete()}
            fail("changed original must refuse prepare")
        } catch(_:IllegalStateException) {}
        assertTrue(changed);assertArrayEquals(original+"external change".toByteArray(),source.readBytes());assertEquals(1,dir.list()!!.size)
    }
    @Test fun oversizedDeclaredAndActualMarkerAndCancellationDuringStagingPreserveSource() = directory {dir->
        val source=bundle(dir,marker=ByteArray(8192) { 'x'.code.toByte() })
        val before=source.readBytes()
        try {BundleAnchors.prepare(source,dir);fail("declared limit")} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("declared payload limit"))}
        assertArrayEquals(before,source.readBytes())
        // A lying central-directory size must not bypass the actual decompressed-byte budget.
        val raw=lieAboutSize(source,BundleAnchors.ENTRY)
        try {BundleAnchors.prepare(source,dir);fail("actual limit")} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("actual payload limit"))}
        assertArrayEquals(raw,source.readBytes());assertEquals(1,dir.list()!!.size)
        val good=bundle(dir);val original=good.readBytes()
        try {BundleAnchors.prepare(good,dir,cancelled={dir.listFiles()!!.any {it.name.startsWith("anchor-candidate-")}});fail("mid-stage cancel")} catch(_:IllegalStateException) {}
        assertArrayEquals(original,good.readBytes());assertEquals(2,dir.list()!!.size)
    }
    @Test fun identityMetadataIsBoundedBeforeParsingEvenWhenCentralSizeLies() = directory {dir->
        val source=bundle(dir,identityBytes=ByteArray(8192) {'x'.code.toByte()});val original=source.readBytes()
        try {BundleAnchors.prepare(source,dir);fail("declared identity limit")} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("declared payload limit"))}
        assertArrayEquals(original,source.readBytes());assertEquals(1,dir.list()!!.size)
        val forged=lieAboutSize(source,BundleIdentity.ENTRY)
        try {BundleAnchors.prepare(source,dir);fail("actual identity limit")} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("actual payload limit"))}
        assertArrayEquals(forged,source.readBytes());assertEquals(1,dir.list()!!.size)
    }

}
