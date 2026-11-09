package com.xnotes.mindmap

import android.graphics.Bitmap
import com.xnotes.core.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceRangeImageOwnershipTest {
    @Test fun cancelledUnpublishedImageIsRecycledExactlyOnce() {
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        val frame=OwnedVisualFrame(bitmap,Rect(0.0,0.0,8.0,8.0))
        frame.close();frame.close();assertTrue(bitmap.isRecycled)
        assertThrows(IllegalStateException::class.java) {frame.publish()}
    }
    @Test fun publishedImageIsNeverManuallyRecycledWhileARendererMayStillReferenceIt() {
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        val frame=OwnedVisualFrame(bitmap,Rect(0.0,0.0,8.0,8.0))
        frame.publish();frame.close();frame.close();assertFalse(bitmap.isRecycled)
        // End of this test's simulated renderer ownership, not a production detach assumption.
        bitmap.recycle()
    }
}
