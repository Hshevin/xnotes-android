package com.xnotes.storage

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.CanvasCodec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class PreparedProtectedCanvasTest {
    private fun scenario(block:(File,CanvasCodec)->Unit) {
        val root=Files.createTempDirectory("protected-canvas-assets-").toFile()
        try {block(root,CanvasCodec(FakeImageCodec(),FakeTextMeasurer()))} finally {root.deleteRecursively()}
    }
    @Test fun abortClosesOnlyItsSnapshotAndAssetsWhileAdoptionKeepsTheActualImageAlive()=scenario {root,codec->
        val image=File(root,"image.png").apply {writeBytes(byteArrayOf(1,2,3))}
        val document=InfiniteDocument().apply {add(ImageItem(ImageData(image,20,20),Rect(0.0,0.0,20.0,20.0)))}
        val source=File(root,"body.xcanvas").apply {outputStream().use {codec.write(document.snapshotForWrite(fixedItems=true),it)}}
        val before=source.readBytes();val assets=File(root,"assets").apply {mkdir()}
        val unrelated=File(assets,"another-request").apply {mkdir();File(this,"keep").writeText("keep")}
        val first=BundleSnapshot.take(source,root)
        PreparedProtectedCanvas.read(first,codec,assets).close()
        assertFalse(first.file.exists());assertEquals(listOf(unrelated),assets.listFiles()!!.toList())
        val second=BundleSnapshot.take(source,root)
        val prepared=PreparedProtectedCanvas.read(second,codec,assets)
        val loaded=(prepared.document.items.single() as ImageItem).image.file
        assertTrue(loaded.isFile)
        val lifetime=prepared.adopt(prepared.document);prepared.close();lifetime.close()
        assertTrue(loaded.isFile);assertFalse(second.file.exists())
        assertThrows(IllegalStateException::class.java) {prepared.adopt(prepared.document)}
        assertEquals("keep",File(unrelated,"keep").readText());assertArrayEquals(before,source.readBytes())
    }
    @Test fun protectedCanvasPreparationKeepsEveryDenseInkSampleAndTheOriginalBytes()=scenario {root,codec->
        val samples=(0 until 100).map {Sample(it/5.0,5.0,(it%10)/10.0,it*4.0)}
        // Canvas writes time only for a speed-sensitive pen; this fixture must actually store t.
        val document=InfiniteDocument().apply {add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN).copy(speedStrength=.8),samples))}
        val source=File(root,"body.xcanvas").apply {outputStream().use {codec.write(document.snapshotForWrite(fixedItems=true),it)}}
        val before=source.readBytes()
        PreparedProtectedCanvas.read(BundleSnapshot.take(source,root),codec,File(root,"assets")).use {prepared->
            val restored=(prepared.document.items.single() as Stroke).samples
            assertEquals(samples.size,restored.size)
            for(index in samples.indices) {
                assertEquals(samples[index].x,restored[index].x,.000001);assertEquals(samples[index].y,restored[index].y,.000001)
                assertEquals(samples[index].pressure,restored[index].pressure,.000001);assertEquals(samples[index].t,restored[index].t,.000001)
            }
            assertEquals(document.identity.documentId,prepared.persistedDocumentId)
            assertThrows(IllegalStateException::class.java) {prepared.adoptedRecordingEvidence(prepared.document)}
        }
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun anUnknownCanvasItemAndAChangedSnapshotCannotBecomeAnEditableCache()=scenario {root,codec->
        val source=File(root,"unknown.xcanvas")
        ZipOutputStream(source.outputStream()).use {zip->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"xcanvas","version":1,"items":[{"kind":"future-item"}]}""".toByteArray())
            zip.closeEntry()
        }
        val before=source.readBytes();val assets=File(root,"assets").apply {mkdir()}
        val snapshot=BundleSnapshot.take(source,root)
        assertThrows(Exception::class.java) {PreparedProtectedCanvas.read(snapshot,codec,assets)}
        assertFalse(snapshot.file.exists());assertTrue(assets.listFiles()!!.isEmpty());assertArrayEquals(before,source.readBytes())
        val changed=BundleSnapshot.take(source,root).also {it.file.appendText("changed private raw")}
        assertThrows(Exception::class.java) {PreparedProtectedCanvas.read(changed,codec,assets)}
        assertFalse(changed.file.exists());assertTrue(assets.listFiles()!!.isEmpty())
    }
    @Test fun cancellationAfterAnImageWasMaterializedReleasesOnlyThisRequestsAssets()=scenario {root,codec->
        val image=File(root,"image.png").apply {writeBytes(byteArrayOf(1,2,3))}
        val document=InfiniteDocument().apply {add(ImageItem(ImageData(image,20,20),Rect(0.0,0.0,20.0,20.0)))}
        val source=File(root,"body.xcanvas").apply {outputStream().use {codec.write(document.snapshotForWrite(fixedItems=true),it)}}
        val before=source.readBytes();val assets=File(root,"assets").apply {mkdir()}
        val unrelated=File(assets,"other-request").apply {mkdir();File(this,"keep").writeText("keep")}
        val snapshot=BundleSnapshot.take(source,root);var materialized=false
        assertThrows(CancellationException::class.java) {PreparedProtectedCanvas.read(snapshot,codec,assets) {
            if(assets.listFiles().orEmpty().filter {it!=unrelated}.any {owned->owned.walkTopDown().any {it.isFile}}) {
                materialized=true;throw CancellationException("cancel after Canvas image decode")
            }
        }}
        assertTrue(materialized);assertFalse(snapshot.file.exists())
        assertEquals(listOf(unrelated),assets.listFiles()!!.toList());assertEquals("keep",File(unrelated,"keep").readText())
        assertArrayEquals(before,source.readBytes())
    }
}
