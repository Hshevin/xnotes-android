package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Completeness is observed during the actual note decode; page IDs alone cannot prove its body survived. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PagedRegionCompleteReadTest {
    private class Fixture(val root:File) {
        val uri="content://paged-region-complete/document/source"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val source=File(root,"source");val assets=File(root,"assets").apply {mkdir()};val pdfs=File(root,"pdfs").apply {mkdir()}
        val note=DocumentCodec(FakeImageCodec(),FakeTextMeasurer());val canvas=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        var writes=0
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read()=source.inputStream()
            override fun write(file:File) {writes++;fail("An observed reference preview cannot write its original")}
        }})
        fun prepare(complete:Boolean=false,checkActive:()->Unit={})=storage.openSource(uri,DocumentKind.NOTE,checkActive).use {held->
            PreparedNativeOpen.read(held,storage.pdfResources,note,canvas,pdfs,assets,null,
                requireCompleteCanvas=false,requireCompleteNote=complete,checkActive=checkActive)
        }
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("paged-region-complete-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    private fun bundle(file:File,items:String,image:Boolean=false,twoPages:Boolean=false,extras:Map<String,ByteArray> = emptyMap()) {
        val owner=UUID.randomUUID().toString();val page=UUID.randomUUID().toString()
        ZipOutputStream(file.outputStream()).use {zip->
            BundleIdentity(owner).write(zip);AnchorMetadata(owner).write(zip)
            if(image) {zip.putNextEntry(ZipEntry("assets/image-000.png"));zip.write(byteArrayOf(1,2,3));zip.closeEntry()}
            for((key,value) in extras) {zip.putNextEntry(ZipEntry(key));zip.write(value);zip.closeEntry()}
            val pages="""{"reference_id":"$page","width":200,"height":200,"items":$items}"""+
                if(twoPages) """,{"reference_id":"${UUID.randomUUID()}","width":200,"height":200,"items":[]}""" else ""
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write("""{"format":"xnote","version":1,"writer":1000,"has_pdf":false,"pages":[$pages]}""".toByteArray());zip.closeEntry()
        }
    }
    private val stroke="""{"kind":"stroke","samples":[[10,20,1],[30,40,0.5]]}"""
    @Test fun defaultReadRemainsTolerantButNeverSignsACompleteOriginAfterAnyBodyItemWasDropped()=scenario {f->
        for(dropped in listOf("""{"kind":"future-kind"}""","42","null","{}",
            """{"kind":"image","asset":"assets/image-missing.png","src_w":20,"src_h":20}""",
            """{"kind":"audio","asset":"assets/audio-missing.m4a"}""","""{"kind":"tape"}""")) {
            bundle(f.source,"[$stroke,$dropped]");val bytes=f.source.readBytes()
            f.prepare().use {prepared->
                val doc=requireNotNull(prepared.note);assertEquals(1,doc.pages.single().items.size)
                doc.path=f.uri;prepared.protectForAdoption();prepared.adopt(doc)
                assertFalse("Unrepresented item: $dropped",prepared.completeNoteOrigin(doc))
            }
            val retained=f.assets.listFiles().orEmpty().toSet()
            assertThrows(Exception::class.java) {f.prepare(complete=true)}
            assertArrayEquals(bytes,f.source.readBytes());assertEquals(0,f.writes);assertEquals(retained,f.assets.listFiles().orEmpty().toSet())
        }
    }
    @Test fun completeOriginRequiresTheActuallyDecodedPageAndItemOrderAndStillAllowsNormalEditsAfterAdoption()=scenario {f->
        bundle(f.source,"[$stroke,$stroke]",twoPages=true);val bytes=f.source.readBytes()
        f.prepare(complete=true).use {prepared->
            val doc=requireNotNull(prepared.note);doc.path=f.uri;prepared.protectForAdoption()
            java.util.Collections.swap(doc.pages,0,1)
            assertThrows(ReferenceContractViolation::class.java) {prepared.adopt(doc)}
            java.util.Collections.swap(doc.pages,0,1);prepared.adopt(doc);assertTrue(prepared.completeNoteOrigin(doc))
            val baseline=prepared.sourceVersion(doc);doc.pages.first().items.clear();doc.dirty=true
            assertSame(baseline,prepared.sourceVersion(doc));assertFalse(prepared.completeNoteOrigin(doc))
        }
        assertArrayEquals(bytes,f.source.readBytes());assertEquals(0,f.writes)
    }
    @Test fun supportedPendingImagesKeepTheirOriginalPositionAndCompleteObservation()=scenario {f->
        val image="""{"kind":"image","asset":"assets/image-000.png","rect":[0,0,20,20],"src_w":20,"src_h":20}"""
        bundle(f.source,"[$image,$stroke]",image=true)
        f.prepare(complete=true).use {prepared->
            val doc=requireNotNull(prepared.note);assertEquals(listOf("image","stroke"),doc.pages.single().items.map {it.kind})
            doc.path=f.uri;prepared.protectForAdoption();prepared.adopt(doc);assertTrue(prepared.completeNoteOrigin(doc))
        }
        assertEquals(0,f.writes)
    }
    @Test fun cancellingStrictActualReadCleansOnlyItsTemporaryAssetsAndNeverWritesItsOriginal()=scenario {f->
        val image="""{"kind":"image","asset":"assets/image-000.png","rect":[0,0,20,20],"src_w":20,"src_h":20}"""
        bundle(f.source,"[$image,$stroke]",image=true);val original=f.source.readBytes()
        val unrelated=File(f.root,"unrelated").apply {mkdir()};val keep=File(unrelated,"keep").apply {writeText("keep")}
        assertThrows(java.util.concurrent.CancellationException::class.java) {f.prepare(complete=true) {
            if(f.assets.walkTopDown().any {it.isFile}) throw java.util.concurrent.CancellationException("cancel after actual asset allocation")
        }}
        assertTrue(f.assets.listFiles().orEmpty().isEmpty());assertTrue(keep.isFile)
        assertArrayEquals(original,f.source.readBytes());assertEquals(0,f.writes)
    }
    @Test fun clearlyUnreadableFlowOrMarkupCannotBecomeACompleteOriginByBeingDecodedAsEmpty()=scenario {f->
        for(entry in listOf(FlowXml.ENTRY_NAME,MarkupsXfdf.ENTRY_NAME)) {
            bundle(f.source,"[]",extras=mapOf(entry to "<broken".toByteArray()));val bytes=f.source.readBytes()
            f.prepare().use {prepared->
                val doc=requireNotNull(prepared.note);doc.path=f.uri;prepared.protectForAdoption();prepared.adopt(doc)
                assertFalse(prepared.completeNoteOrigin(doc))
            }
            assertArrayEquals(bytes,f.source.readBytes());assertEquals(0,f.writes)
        }
    }
}
