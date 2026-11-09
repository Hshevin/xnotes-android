package com.xnotes.gl

import android.os.SharedMemory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.MeshBuilder
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/** Layer visibility is a draw-time filter: hidden records stay resident and nothing re-uploads. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CanvasSceneHiddenLayersTest {
    @Test fun hidingALayerCullsItsRecordsWithoutDroppingGeometry() = withScene { f ->
        val base = strokeAt(20.0, "base")
        val upper = strokeAt(40.0, "upper")
        f.put(base)
        f.put(upper)
        f.scene.setOrder(listOf(base, upper))
        f.drain()
        val vertices = f.store.usedVertices
        assertEquals(listOf<CanvasItem>(base, upper), f.visibleItems())

        f.scene.setHiddenLayers(setOf("upper"))
        f.drain()
        assertEquals(listOf<CanvasItem>(base), f.visibleItems())
        assertNotNull("a hidden record stays resident", f.record(upper))
        assertEquals(vertices, f.store.usedVertices)

        f.scene.setHiddenLayers(emptySet())
        f.drain()
        assertEquals(listOf<CanvasItem>(base, upper), f.visibleItems())
        assertEquals("showing again re-uploads nothing", vertices, f.store.usedVertices)
    }

    @Test fun relayerMovesARecordIntoAHiddenLayerWithoutNewGeometry() = withScene { f ->
        val ink = strokeAt(20.0, "base")
        f.put(ink)
        f.scene.setHiddenLayers(setOf("hidden"))
        f.drain()
        val vertices = f.store.usedVertices
        assertEquals(listOf<CanvasItem>(ink), f.visibleItems())

        ink.layerId = "hidden"
        f.scene.relayer(listOf(ink))
        f.drain()
        assertTrue(f.visibleItems().isEmpty())
        assertEquals(vertices, f.store.usedVertices)
    }

    @Test fun anItemWithoutALayerIsNeverHidden() = withScene { f ->
        val fading = strokeAt(20.0, null)
        f.put(fading)
        f.scene.setHiddenLayers(setOf("default", "base"))
        f.drain()
        assertEquals(listOf<CanvasItem>(fading), f.visibleItems())
    }

    @Test fun upsertSnapshotsTheLayerOnTheProducer() = withScene { f ->
        val ink = strokeAt(20.0, "base")
        f.put(ink)
        // Changed after posting and before the consumer drains: the record keeps what was sent.
        ink.layerId = "upper"
        f.scene.setHiddenLayers(setOf("upper"))
        f.drain()
        assertEquals(listOf<CanvasItem>(ink), f.visibleItems())
    }

    private class Fixture {
        val scene = CanvasScene()
        val store = field<GeometryStore>(scene, "store")

        init { scene.decodeOn = { error("Hidden layer fixture must not decode images") } }

        fun put(item: Stroke) {
            val y = item.yAt(0)
            scene.upsert(item, partsAt(y), Rect(8.0, y - 4.0, 48.0, 8.0))
        }

        fun drain() {
            CanvasScene::class.java.getDeclaredMethod("drainEdits").apply { isAccessible = true }.invoke(scene)
        }

        fun record(item: CanvasItem): Any? = field<Map<CanvasItem, Any>>(scene, "records")[item]

        fun visibleItems(): List<CanvasItem> {
            val frame = FrameState(1.0, 0.0, 0.0, 200, 200, CanvasBackground(), Rgba(255, 255, 255, 255))
            CanvasScene::class.java.getDeclaredMethod("collectVisible", FrameState::class.java)
                .apply { isAccessible = true }.invoke(scene, frame)
            return field<List<Any>>(scene, "visible").map { field<CanvasItem>(it, "item") }
        }

        fun close() {
            for (name in listOf("store", "wetStore", "tailStore")) {
                val bufferStore = field<GeometryStore>(scene, name)
                val mapped = field<MutableSet<ByteBuffer>>(bufferStore, "mapped")
                mapped.toList().forEach(SharedMemory::unmap)
                mapped.clear()
            }
        }
    }

    private fun withScene(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.close() }
    }

    companion object {
        private fun strokeAt(y: Double, layer: String?) = Stroke(
            Tool.PEN, ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(0, 0, 0), baseWidth = 8.0),
        ).apply {
            addSample(Sample(8.0, y, 1.0, 0.0))
            addSample(Sample(56.0, y, 1.0, 10.0))
            layerId = layer
        }

        private fun partsAt(y: Double): List<MeshPart> {
            val builder = MeshBuilder(4, 6)
            builder.rect(8.0, y - 4.0, 48.0, 8.0)
            return listOf(MeshPart(builder.build(), Rgba(0, 0, 0), InkPass.OPAQUE))
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
