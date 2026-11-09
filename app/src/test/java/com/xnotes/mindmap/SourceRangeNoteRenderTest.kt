package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual saved-page painter and a wholly outside blank viewport. No controller error translation,
 * live Editor, source adoption or source write hides the renderer boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceRangeNoteRenderTest {
    private fun scenario()=MapBacklinkLibraryTest().scenario {f->withContext(Dispatchers.IO) {
        val doc=Document(mutableListOf(Page(500.0,400.0)),dpi=72).apply {enableReferenceAnchors()}
        val file=File(f.notes,"actual-note-render.xnote")
        file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc.fixedWriteSnapshot(),it)}
        val original=file.readBytes();val access=MapSourceTargetAccess(f.context,f.storage)
        val read=access.openOffer(access.inspectFile(f.uri(file)))
        try {
            val frame=read.frame(read.pages.single().id)
            val image=read.render(frame,frame.bounds,128)
            try {assertEquals(frame.bounds,image.frame);assertEquals(128,image.bitmap.width);assertEquals(102,image.bitmap.height)}
            finally {image.close()}
            val max=MindMapDocument.MAX_COORD;val outside=Rect(max,max,max,max)
            val blank=read.render(frame,outside,128)
            try {assertEquals(outside,blank.frame);assertEquals(128,blank.bitmap.width);assertEquals(128,blank.bitmap.height)}
            finally {blank.close()}
        } finally {read.closeAndAwait()}
        assertArrayEquals(original,file.readBytes());assertEquals(0,f.writes.get())
    }}
    @Test fun nativeSavedPageAndWhollyOutsideViewportRenderWithoutChangingTheSource()=scenario()
}
