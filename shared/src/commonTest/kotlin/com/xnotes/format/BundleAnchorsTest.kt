package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.testkit.*
import kotlin.test.Test

class BundleAnchorsTest : BundleTestFiles() {
    private val identity=BundleIdentity("00000000-0000-0000-0000-000000000001")
    private fun directory(block:(AssetFile)->Unit) {
        val dir=testDirectory("anchors-")
        try {block(dir)} finally {dir.deleteTree()}
    }
    private fun bundle(dir:AssetFile,canvas:Boolean=false,marker:ByteArray?=null,comment:Boolean=true,identityBytes:ByteArray?=null):AssetFile {
        val manifest=if(canvas) """{"format":"xcanvas","version":1,"items":[]}""" else """{"format":"xnote","version":1,"pages":[{"items":[{"kind":"stroke","samples":[[-0,1.2000,0.3333333333333333333,1e+02]]}]}]}"""
        return temporaryAsset("source-",".zip",dir).also { file -> bundleWriter(file.fileSink(), comment = "future archive metadata".takeIf { comment }).use { z ->
            fun entry(name:String,bytes:ByteArray) {z.putDeflated(name,bytes)}
            // Deliberately non-tail manifest with unknown and identity entries after it.
            entry("manifest.json",manifest.encodeToByteArray());entry("assets/source.pdf",ByteArray(65537) {it.toByte()})
            entry("future/payload.bin",byteArrayOf(0,-1,2,3))
            if(identityBytes==null) z.addIdentity(identity) else entry(BundleIdentity.ENTRY,identityBytes)
            marker?.let {entry(BundleAnchors.ENTRY,it)}
        } }
    }
    private fun bytes(file:AssetFile,name:String)=bundleDirectory(file).use {z->z.open(name).buffer().use {it.readByteArray()}}
    private fun lieAboutSize(source:AssetFile,name:String):ByteArray {
        val raw=source.fileBytes();val signature=byteArrayOf(0x50,0x4b,0x01,0x02);var at=0
        while(at<raw.size-46) {
            if(raw.copyOfRange(at,at+4).contentEquals(signature)) {
                val length=(raw[at+28].toInt() and 255)+((raw[at+29].toInt() and 255) shl 8)
                if(raw.decodeToString(at+46,at+46+length)==name) {repeat(4) {raw[at+24+it]=0};raw[at+24]=1;break}
            }
            at++
        }
        assertTrue(at<raw.size-46);source.setBytes(raw);return raw
    }
    @Test fun stagedUpgradeKeepsNonManifestPayloadAndSequentialReaderSeesExactlyTheNewManifest() = directory { dir ->
        val source=bundle(dir);val before=source.fileBytes();val fingerprints=BundleMigration.fingerprints(source)
        val staged=BundleAnchors.prepare(source,dir)
        assertArrayEquals(before,source.fileBytes())
        assertEquals(fingerprints.filterKeys {it!="manifest.json"},BundleMigration.fingerprints(staged.file).filterKeys {it!="manifest.json" && it!=BundleAnchors.ENTRY})
        val manifest=bytes(staged.file,"manifest.json")
        assertTrue(manifest.decodeToString().contains("reference_id"));assertEquals(1,staged.anchorCount)
        val sequential=mutableListOf<String>()
        bundleReader(staged.file.input()).use {z->var e=z.next();while(e!=null) {sequential+=e.name;if(e.name=="manifest.json") assertArrayEquals(manifest,e.data.readByteArray());e.finish();e=z.next()}}
        assertEquals(1,sequential.count {it=="manifest.json"});assertTrue("future/payload.bin" in sequential)
        assertEquals(bundleDirectory(staged.file).use {it.entries.map {e->e.name}.toList()},sequential)
        BundleAnchors.publish(staged,source)
        val second=BundleAnchors.prepare(source,dir,expectedAnchored=true)
        assertArrayEquals(manifest,bytes(second.file,"manifest.json"));assertArrayEquals(bytes(source,BundleAnchors.ENTRY),bytes(second.file,BundleAnchors.ENTRY));second.file.remove()
        assertEquals(listOf(source.assetPath().name),dir.fileNames().toList())
    }
    @Test fun canvasesNeedNoPageUpgradeAndKnownMissingPageIdentitiesAreRejected() = directory {dir->
        val empty=bundle(dir,canvas=true);val before=empty.fileBytes()
        try {BundleAnchors.prepare(empty,dir);fail("canvas has no page anchors")} catch(_:IllegalArgumentException) {}
        assertArrayEquals(before,empty.fileBytes())
        val lost=bundle(dir,marker=AnchorMetadata(identity.documentId).bytes())
        try {BundleAnchors.prepare(lost,dir);fail("marker with missing page IDs must fail")} catch(_:IllegalArgumentException) {}
        val legacy=bundle(dir)
        try {BundleAnchors.prepare(legacy,dir,expectedAnchored=true);fail("known upgrade missing marker")} catch(_:IllegalArgumentException) {}
    }
    @Test fun cancellationAndExternalChangeKeepSourceAndRecoveryCandidate() = directory {dir->
        val source=bundle(dir);val before=source.fileBytes()
        try {BundleAnchors.prepare(source,dir,cancelled={true});fail("cancel")} catch(_:IllegalStateException) {}
        assertArrayEquals(before,source.fileBytes());assertEquals(1,dir.fileNames().size)
        val staged=BundleAnchors.prepare(source,dir);source.appendUtf8("changed")
        val changed=source.fileBytes()
        try {BundleAnchors.publish(staged,source);fail("conflict")} catch(_:IllegalStateException) {}
        assertArrayEquals(changed,source.fileBytes());assertTrue(KFiles.system.exists(staged.file.assetPath()));staged.file.remove()
    }
    @Test fun orphanLocalManifestThatDisagreesWithCentralDirectoryIsRejected() = directory {dir->
        val source=bundle(dir,comment=false);val extra=dir.child("extra.zip")
        bundleWriter(extra.fileSink()).use {z->z.putDeflated("manifest.json","""{"format":"xcanvas","version":1,"items":[]}""".encodeToByteArray())}
        openReadWrite(source.assetPath()).use {dest->openRead(extra.assetPath()).use {tail->
            val old=ZipTail.read(dest)!!;val addition=ZipTail.read(tail)!!
            check(ZipTail.splice(dest,old.offset,old.entries.filter {it.name!="manifest.json"},tail,addition)>=0)
        }}
        val firstLocal=bundleReader(source.input()).use {z->val first=z.next()!!;assertEquals("manifest.json",first.name);first.data.readByteArray()}
        assertFalse(firstLocal.contentEquals(bytes(source,"manifest.json")))
        val before=source.fileBytes()
        try {BundleAnchors.prepare(source,dir);fail("sequential reader would see old manifest")} catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {}
        assertArrayEquals(before,source.fileBytes());assertEquals(2,dir.fileNames().size)
    }
    @Test fun sourceMutationsDuringPrepareDoNotAffectTheImmutableSnapshot() = directory {dir->
        val source=bundle(dir);val original=source.fileBytes();var changed=false
        try {
            BundleAnchors.prepare(source,dir,cancelled={
                if(!changed && dir.fileNames().any {it.startsWith("anchors-")}) {changed=true;source.appendUtf8("external change")}
                false
            }).also {it.file.remove()}
            fail("changed original must refuse prepare")
        } catch(_:IllegalStateException) {}
        assertTrue(changed);assertArrayEquals(original+"external change".encodeToByteArray(),source.fileBytes());assertEquals(1,dir.fileNames().size)
    }
    @Test fun oversizedDeclaredAndActualMarkerAndCancellationDuringStagingPreserveSource() = directory {dir->
        val source=bundle(dir,marker=ByteArray(8192) { 'x'.code.toByte() })
        val before=source.fileBytes()
        try {BundleAnchors.prepare(source,dir);fail("declared limit")} catch(e:IllegalArgumentException) {assertFalse(e.message.isNullOrBlank())}
        assertArrayEquals(before,source.fileBytes())
        // A lying central-directory size must not bypass the actual decompressed-byte budget.
        val raw=lieAboutSize(source,BundleAnchors.ENTRY)
        try {BundleAnchors.prepare(source,dir);fail("actual limit")} catch(e:IllegalArgumentException) {assertFalse(e.message.isNullOrBlank())}
        assertArrayEquals(raw,source.fileBytes());assertEquals(1,dir.fileNames().size)
        val good=bundle(dir);val original=good.fileBytes()
        try {BundleAnchors.prepare(good,dir,cancelled={dir.fileNames().any {it.startsWith("anchors-")}});fail("mid-stage cancel")} catch(_:IllegalStateException) {}
        assertArrayEquals(original,good.fileBytes());assertEquals(2,dir.fileNames().size)
    }
    @Test fun identityMetadataIsBoundedBeforeParsingEvenWhenCentralSizeLies() = directory {dir->
        val source=bundle(dir,identityBytes=ByteArray(8192) {'x'.code.toByte()});val original=source.fileBytes()
        try {BundleAnchors.prepare(source,dir);fail("declared identity limit")} catch(e:IllegalArgumentException) {assertFalse(e.message.isNullOrBlank())}
        assertArrayEquals(original,source.fileBytes());assertEquals(1,dir.fileNames().size)
        val forged=lieAboutSize(source,BundleIdentity.ENTRY)
        try {BundleAnchors.prepare(source,dir);fail("actual identity limit")} catch(e:IllegalArgumentException) {assertFalse(e.message.isNullOrBlank())}
        assertArrayEquals(forged,source.fileBytes());assertEquals(1,dir.fileNames().size)
    }

}
