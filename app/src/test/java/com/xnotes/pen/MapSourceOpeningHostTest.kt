package com.xnotes.pen

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.BundleHeader
import com.xnotes.format.CanvasCodec
import com.xnotes.map.*
import com.xnotes.map.source.*
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MapSourceOpeningHostTest {
    private suspend fun origin(f: EditorPageCopyFixture): Pair<MapReturn, SourceRef> {
        f.seed()
        val note = f.source.inputStream().use { f.codec.read(it) }
        val ref = SourceRef(UUID.randomUUID().toString(), SourceKind.NOTE_PAGE, note.identity.documentId,
            note.pages[1].referenceIdentity.id, "stale page 1")
        f.storage.registry.registerObserved(BundleHeader.read(f.source), "xnote", "source.xnote", f.sourceUri)
        f.targetDisplayName = "map.xcanvas"
        val node = NodeId("root")
        val doc = InfiniteDocument().apply {
            map = MapModel(MapState(nodes = mapOf(node to MapNode(node, "Topic", sources = listOf(ref))),
                roots = listOf(node), rootOrigin = mapOf(node to Pt(0.0, 0.0)), layout = mapOf(node to LayoutKind.RIGHT)))
        }
        f.target.outputStream().use { CanvasCodec(AndroidImageCodec(), AndroidTextMeasurer()).write(doc, it) }
        withContext(Dispatchers.Main) { assertTrue(f.editor.openCanvasAsync(f.targetUri, f.targetDisplayName)) }
        return MapReturn(doc.identity.documentId, f.targetUri, node, -80.0, 140.0, 1.75) to ref
    }

    private val resolver = SourceResolver(object : SourceCatalogs {
        override fun locations(documentId: String): List<String> = error("No resolution needed for this route")
        override fun read(location: String): SourceCatalog? = error("No resolution needed for this route")
    })

    @Test fun aPendingGuardRejectsBothOtherOperationsAndCancellationKeepsTheMap() = runParkingScenario { f ->
        val (from, ref) = origin(f)
        withContext(Dispatchers.Main) {
            lateinit var opener: EditorMapSourceOpener
            opener = EditorMapSourceOpener(f.editor, { _, current ->
                assertTrue(current())
                assertTrue(opener.busy)
                assertEquals(FollowResult.Failed(null), opener.follow(ref, from))
                assertEquals(BackResult.STAYED, opener.back())
                GuardDecision.Cancelled
            }, resolver) { fail("Cancelled navigation adopted a target") }
            assertEquals(FollowResult.Cancelled, opener.follow(ref, from))
            assertTrue(f.editor.canvasOpen)
            assertFalse(opener.busy)
            assertFalse(opener.canGoBack)
        }
    }

    @Test fun aDeletedPageIsExplainedOffMainWithoutLeavingTheMap() = runParkingScenario { f ->
        val (from, ref) = origin(f)
        val note = f.source.inputStream().use { f.codec.read(it) }.apply { pages.removeAt(1) }
        f.source.outputStream().use { f.codec.write(note, it) }
        val catalogs = object : SourceCatalogs {
            override fun locations(documentId: String) = listOf(f.sourceUri)
            override fun read(location: String): SourceCatalog {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                return SourceCatalog(note.identity.documentId, SourceCatalog.Kind.NOTE, "source.xnote",
                    note.pages.map { requireNotNull(it.referenceIdentity.id) })
            }
        }
        withContext(Dispatchers.Main) {
            val opener = EditorMapSourceOpener(f.editor, { _, _ -> GuardDecision.Go(emptySet()) }, SourceResolver(catalogs)) {
                fail("Deleted page adopted")
            }
            assertEquals(FollowResult.Failed(SourceStatus.PageDeleted(f.sourceUri, "source.xnote")), opener.follow(ref, from))
            assertTrue(f.editor.canvasOpen)
            assertFalse(opener.canGoBack)
        }
    }

    @Test fun replacingTheBookmarkedFileDoesNotOpenTheReplacement() = runParkingScenario { f ->
        val (from, ref) = origin(f)
        withContext(Dispatchers.Main) {
            val opener = EditorMapSourceOpener(f.editor, { _, _ -> GuardDecision.Go(emptySet()) }, resolver) {}
            assertEquals(FollowResult.Opened, opener.follow(ref, from))
            withContext(Dispatchers.IO) {
                f.target.outputStream().use { CanvasCodec(AndroidImageCodec(), AndroidTextMeasurer()).write(InfiniteDocument(), it) }
            }
            assertEquals(BackResult.CANVAS_GONE, opener.back())
            assertFalse(opener.canGoBack)
            assertEquals(ref.documentId, f.editor.visibleDocumentId)
        }
    }

    @Test fun openingADocumentFromTheFileListRetiresTheReturnAction() = runParkingScenario { f ->
        val (from, ref) = origin(f)
        withContext(Dispatchers.Main) {
            val opener = EditorMapSourceOpener(f.editor, { _, _ -> GuardDecision.Go(emptySet()) }, resolver) {}
            assertEquals(FollowResult.Opened, opener.follow(ref, from))
            opener.userOpenedElsewhere()
            assertFalse(opener.canGoBack)
            assertEquals(BackResult.STAYED, opener.back())
            assertNull(opener.trip.pending)
        }
    }

    @Test fun guardedNativeHandoffsLocateTheLinkedPageAndRestoreTheMap() = runParkingScenario { f ->
        val (from, ref) = origin(f)
        withContext(Dispatchers.Main) {
            var shown = 0
            var cancel = false
            val opener = EditorMapSourceOpener(f.editor, { _, current ->
                assertTrue(current())
                if (cancel) GuardDecision.Cancelled else GuardDecision.Go(emptySet())
            }, resolver) { shown++ }
            f.editor.infinite.map.editTitle(from.node)
            f.editor.infinite.map.typing("Committed before leaving")
            assertEquals(FollowResult.Opened, opener.follow(ref, from))
            assertFalse(f.editor.canvasOpen)
            assertTrue(opener.canGoBack)
            assertEquals(from, opener.trip.pending)
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            val holder = FrameLayout(activity.get())
            try {
                activity.get().setContentView(holder)
                f.editor.pad.visibility = View.GONE
                holder.addView(f.editor.surfacesForAttach(), FrameLayout.LayoutParams(-1, -1))
                val size = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
                holder.measure(size, size)
                holder.layout(0, 0, 800, 800)
                repeat(3) { f.editor.view.viewTreeObserver.dispatchOnPreDraw() }
                assertEquals(ref.pageId, f.editor.state.document.pages[f.editor.state.currentPageIndex()].referenceIdentity.id)
            } finally {
                holder.removeAllViews()
                activity.pause().stop().destroy()
            }
            cancel = true
            assertEquals(BackResult.STAYED, opener.back())
            assertEquals(from, opener.trip.pending)
            assertTrue(opener.canGoBack)
            cancel = false
            assertEquals(BackResult.BACK, opener.back())
            assertFalse(opener.canGoBack)
            assertNull(opener.trip.pending)
            assertEquals(2, shown)
            val canvas = f.editor.infinite
            assertTrue(f.editor.canvasOpen)
            assertEquals(from.canvasDocumentId, canvas.document.identity.documentId)
            assertEquals("Committed before leaving", canvas.document.map.state.nodes.getValue(from.node).title)
            assertEquals(from.node, canvas.map.selected)
            assertEquals(from.viewX, canvas.viewport.scrollX, 0.0)
            assertEquals(from.viewY, canvas.viewport.scrollY, 0.0)
            assertEquals(from.zoom, canvas.viewport.zoom, 0.0)
        }
    }
}
