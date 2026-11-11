package com.xnotes.mindmap

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Checks the actual Main host and pixels; existence of a sized AndroidView is insufficient. */
class MapVisibleEditingRegressionTest {
    private val runner get() = PenTestRunner.instance
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        runner.runOnMainSync { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun await(label: String, condition: () -> Boolean) {
        repeat(500) { runner.waitForIdleSync(); if (condition()) return; Thread.sleep(20) }
        fail("Visible map regression: $label")
    }
    private fun nodes(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (n == null) emptyList() else
        listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
    private fun click(label: String) {
        var target: AccessibilityNodeInfo? = null
        await("clickable $label") {
            target = nodes(runner.uiAutomation.rootInActiveWindow).firstOrNull {
                it.isVisibleToUser && it.isEnabled && (it.text?.toString() == label || it.contentDescription?.toString() == label)
            }
            target != null
        }
        var clickable = requireNotNull(target)
        while (!clickable.isClickable) clickable = requireNotNull(clickable.parent)
        assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun scenario(test: (MainActivity, MapSession, MindMapView) -> Unit) {
        val base = runner.targetContext
        val file = File.createTempFile("visible-map-", ".xmap", base.cacheDir)
        file.writeBytes(MapCodec.encode(MindMapDocument(nodes = listOf(MapNode(id="root", parent=null, text="Visible topic")))))
        val activity = runner.startActivitySync(Intent(base, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await("Main workspace") { main { activity.mapWorkspace != null && activity.documentEditor != null } }
            val maps = main { requireNotNull(activity.mapWorkspace) }
            main { assertTrue(maps.open(Uri.fromFile(file), "Visibility regression", preparePresentation = {
                activity.documentEditor!!.awaitHostParking()?.let { proof -> ({ proof.current() }) }
            })) }
            await("loaded actual graph") { main { maps.visible && maps.current?.session?.loading == false &&
                views(activity.window.decorView).any { it is MindMapView && it.width > 0 && it.height > 0 } } }
            val graph = main { views(activity.window.decorView).filterIsInstance<MindMapView>().single() }
            test(activity, main { maps.current!!.session }, graph)
        } finally { main { activity.finish() }; runner.waitForIdleSync() }
        // Keep source files until host ownership has ended; test profile cleanup removes them.
    }
    @Test fun mainGraphBecomesInteractiveAndActuallyPaintsItsInitialNode() = scenario { _, _, graph ->
        await("canvas enabled after initial layout") { main { graph.isEnabled } }
        val bounds = main { Rect().also { assertTrue(graph.getGlobalVisibleRect(it)) } }
        val shot = requireNotNull(runner.uiAutomation.takeScreenshot())
        try {
            val background = shot.getPixel(bounds.left + 2, bounds.top + 2)
            var contrastPixels = 0
            for (y in bounds.top + 4 until minOf(bounds.bottom - 4, shot.height) step 2)
                for (x in bounds.left + 4 until minOf(bounds.right - 4, shot.width) step 2)
                    if (shot.getPixel(x,y) != background) contrastPixels++
            assertTrue("The actual screen must contain node borders/text, not just an enabled blank view", contrastPixels > 40)
        } finally { shot.recycle() }
    }
    @Test fun textChildToolbarActionOpensARealEditableDraft() = scenario { activity, session, _ ->
        click(activity.getString(R.string.map_add_text))
        await("new child committed") { main { session.document!!.nodes.size == 2 } }
        await("visible text transaction and native input connection") { main {
            session.textDraft != null && activity.currentFocus?.onCreateInputConnection(android.view.inputmethod.EditorInfo()) != null
        } }
        assertEquals(main { session.selected }, main { session.textDraft!!.nodeId })
    }
}
