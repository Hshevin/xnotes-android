package com.xnotes.mindmap

import android.graphics.BitmapFactory
import android.graphics.Color
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.format.FlowXml
import com.xnotes.format.MarkupsXfdf
import com.xnotes.platform.NativeDocumentPreview
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real source snapshots and CPU PNGs. PDFium and target-device rendering remain separate acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagedRegionPreviewTest {
    private fun shape(x:Double,y:Double)=ShapeItem(ShapeKind.RECTANGLE,Pt(x,y),Pt(x+40,y+40),Rgba(0,0,0),fillRgba=Rgba(0,0,0))
    private fun note()=Document(mutableListOf(Page(200.0,200.0,mutableListOf(shape(20.0,20.0)))),dpi=72)
        .apply {style=PageStyle(template=PageTemplates.NONE);enableReferenceAnchors()}
    private fun target(doc:Document,region:Rect)=MapSourceTarget(doc.identity.documentId,SourceScope.PAGE,doc.pages.single().referenceIdentity.id,region)
    private fun rewrite(file:File,edit:(MutableMap<String,ByteArray>)->Unit) {
        val entries=ZipFile(file).use {zip->zip.entries().asSequence().filterNot {it.isDirectory}
            .associateTo(linkedMapOf()) {it.name to zip.getInputStream(it).use {input->input.readBytes()}}}
        edit(entries)
        ZipOutputStream(file.outputStream()).use {zip->entries.forEach {(name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}}
    }
    @Test fun partialOutsideRegionKeepsTheOriginalFrameAndBlankSpaceInTheActualPng()=MapSourcePreviewBackendTest.Fixture().use {f->
        val doc=note();val uri=f.note(doc);val original=f.provider.file(uri).readBytes()
        val read=f.load(listOf(target(doc,Rect(-50.0,-50.0,100.0,100.0)))).single()
        assertEquals(SourceStatus.CURRENT,read.status);assertFalse(read.failedPreview)
        val bytes=requireNotNull(read.asset).pngBytes();val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        try {
            assertEquals(256,bitmap.width);assertEquals(256,bitmap.height)
            assertEquals("The fixed frame center is page coordinate (0,0), not a newly fitted (25,25)",Color.WHITE,bitmap.getPixel(128,128))
            assertEquals(Color.BLACK,bitmap.getPixel(230,230))
        } finally {bitmap.recycle()}
        val wide=f.load(listOf(target(doc,Rect(-50.0,0.0,200.0,100.0)))).single().asset!!
        val wb=wide.pngBytes();val wi=requireNotNull(BitmapFactory.decodeByteArray(wb,0,wb.size))
        try {assertEquals(256,wi.width);assertEquals(128,wi.height)} finally {wi.recycle()}
        assertArrayEquals(original,f.provider.file(uri).readBytes());assertEquals(0,f.provider.writes);assertTrue(f.assetsGone())
    }
    @Test fun blankPaperGridAndHiddenLayersAreEmptyButWholePageBehaviorStaysAvailable()=MapSourcePreviewBackendTest.Fixture().use {f->
        val doc=note();val page=doc.pages.single();page.layers.update(DrawingLayers.DEFAULT) {it.copy(visible=false)}
        for(template in listOf(PageTemplates.NONE,PagePattern.GRID.id)) {
            doc.style=PageStyle(template=template,pageColor=Rgba(240,240,240));val uri=f.note(doc);val original=f.provider.file(uri).readBytes()
            val range=target(doc,Rect(0.0,0.0,150.0,150.0));val whole=range.copy(region=null)
            val reads=f.load(listOf(range,whole))
            assertEquals(SourceStatus.EMPTY,reads[0].status);assertNull(reads[0].asset);assertFalse(reads[0].failedPreview)
            assertEquals(SourceStatus.CURRENT,reads[1].status);assertNotNull(reads[1].asset)
            assertArrayEquals(original,f.provider.file(uri).readBytes());assertEquals(0,f.provider.writes);assertTrue(f.assetsGone())
        }
    }
    @Test fun bodyLossFailsActualRegionReadAndNeverProvesEmptyOrWritesTheOriginal()=MapSourcePreviewBackendTest.Fixture().use {f->
        val doc=note();doc.pages.single().items.clear()
        for(item in listOf(JSONObject().put("kind","future-kind"),JSONObject().put("kind","image").put("asset","assets/missing.png"),
            JSONObject().put("kind","audio").put("asset","assets/missing.m4a"),JSONObject().put("kind","tape"))) {
            val uri=f.note(doc)
            rewrite(f.provider.file(uri)) {entries->
                val manifest=JSONObject(String(entries.getValue("manifest.json")))
                manifest.getJSONArray("pages").getJSONObject(0).getJSONArray("items").put(item)
                entries["manifest.json"]=manifest.toString().toByteArray()
                entries["assets/image-unused.png"]=byteArrayOf(1,2,3)
            }
            val original=f.provider.file(uri).readBytes();val read=f.load(listOf(target(doc,Rect(0.0,0.0,100.0,100.0)))).single()
            assertEquals(SourceStatus.UNAVAILABLE,read.status);assertTrue(read.failedPreview);assertNull(read.asset)
            assertArrayEquals(original,f.provider.file(uri).readBytes());assertEquals(0,f.provider.writes);assertTrue(f.assetsGone())
        }
    }
    @Test fun customTemplateAndRawXmlPresenceConservativelyKeepCurrentInsteadOfClaimingEmpty()=MapSourcePreviewBackendTest.Fixture().use {f->
        val doc=note();doc.pages.single().items.clear();val range=target(doc,Rect(0.0,0.0,100.0,100.0))
        doc.style=PageStyle(template="unknown-custom-template");f.note(doc)
        assertEquals(SourceStatus.CURRENT,f.load(listOf(range)).single().status)
        doc.style=PageStyle(template=PageTemplates.NONE)
        for(entry in listOf(FlowXml.ENTRY_NAME,MarkupsXfdf.ENTRY_NAME)) {
            val uri=f.note(doc);rewrite(f.provider.file(uri)) {it[entry]="<future-content/>".toByteArray()}
            val original=f.provider.file(uri).readBytes();val read=f.load(listOf(range)).single()
            assertEquals(SourceStatus.CURRENT,read.status);assertFalse(read.failedPreview);assertNotNull(read.asset)
            assertArrayEquals(original,f.provider.file(uri).readBytes());assertEquals(0,f.provider.writes);assertTrue(f.assetsGone())
            rewrite(f.provider.file(uri)) {it[entry]="<broken".toByteArray()}
            val broken=f.provider.file(uri).readBytes();val failed=f.load(listOf(range)).single()
            assertEquals(SourceStatus.UNAVAILABLE,failed.status);assertTrue(failed.failedPreview);assertNull(failed.asset)
            assertArrayEquals(broken,f.provider.file(uri).readBytes());assertTrue(f.assetsGone())
        }
    }
    @Test fun pdfBackgroundIsNeverEmptyMerelyBecauseThereAreNoDrawingItems()=MapSourcePreviewBackendTest.Fixture().use {f->
        val doc=note();doc.pages.single().items.clear();doc.pages.single().pdfPage=0
        val pdf=File(f.root,"original.pdf").apply {writeText("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n")}
        doc.pdfFile=pdf;val pdfBytes=pdf.readBytes();val uri=f.note(doc);val original=f.provider.file(uri).readBytes()
        val read=f.load(listOf(target(doc,Rect(0.0,0.0,100.0,100.0)))).single()
        // The host cannot render this original; failure must keep a useful old image, never prove EMPTY.
        assertEquals(SourceStatus.CURRENT,read.status);assertTrue(read.failedPreview);assertNull(read.asset)
        assertArrayEquals(original,f.provider.file(uri).readBytes());assertArrayEquals(pdfBytes,pdf.readBytes())
        assertEquals(0,f.provider.writes);assertTrue(f.assetsGone())
    }
    @Test fun builtInRulingPhaseUsesPageCoordinatesAcrossFixedCrops()=MapSourcePreviewBackendTest.Fixture().use {f->
        val doc=note();doc.style=PageStyle(template=PagePattern.GRID.id,spacing=16.0,patternColor=Rgba(0,0,0))
        NativeDocumentPreview(f.context).use {painter->
            val a=requireNotNull(painter.note(doc,0,80,Rect(0.0,0.0,80.0,80.0)))
            val b=requireNotNull(painter.note(doc,0,80,Rect(20.0,0.0,80.0,80.0)))
            try {for(y in 2 until 78) for(x in 22 until 78) assertEquals(a.getPixel(x,y),b.getPixel(x-20,y))}
            finally {a.recycle();b.recycle()}
        }
    }
}
