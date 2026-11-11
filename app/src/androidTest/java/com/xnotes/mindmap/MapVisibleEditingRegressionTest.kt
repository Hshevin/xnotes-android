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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
    private fun scenario(initialNodes:List<MapNode> = listOf(MapNode(id="root", parent=null, text="Visible topic")), test: (MainActivity, MapSession, MindMapView) -> Unit) {
        val base = runner.targetContext
        val file = File.createTempFile("visible-map-", ".xmap", base.cacheDir)
        file.writeBytes(MapCodec.encode(MindMapDocument(nodes = initialNodes)))
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
        } finally {
            val closing = main {
                val maps = activity.mapWorkspace
                maps?.current?.let { entry ->
                    entry.session.textDraft?.let { entry.session.cancelTextDraft(it.token) }
                    maps.close(entry)
                }
            }
            if (closing != null) runBlocking { withTimeout(15000) { closing.join() } }
            main { activity.finish() }; runner.waitForIdleSync()
        }
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
        click(activity.getString(R.string.map_create_confirm))
        await("new child committed") { main { session.document!!.nodes.size == 2 } }
        await("visible text transaction and native input connection") { main {
            session.textDraft != null && activity.currentFocus?.onCreateInputConnection(android.view.inputmethod.EditorInfo()) != null
        } }
        assertEquals(main { session.selected }, main { session.textDraft!!.nodeId })
    }
    @Test fun enlargedInkShowsItsBackButtonAboveThePadAndReturnsToInteractiveGraph() = scenario { activity, session, graph ->
        await("initial canvas enabled") { main { graph.isEnabled } }
        click(activity.getString(R.string.map_add_ink))
        click(activity.getString(R.string.map_create_confirm))
        await("enlarged pad") { main { views(activity.window.decorView).any { it is InkPadView && it.height > 0 } } }
        val padBounds = main { Rect().also { rect ->
            assertTrue(views(activity.window.decorView).filterIsInstance<InkPadView>().single().getGlobalVisibleRect(rect))
        } }
        val backs = nodes(runner.uiAutomation.rootInActiveWindow).filter {
            it.isVisibleToUser && (it.text?.toString() == activity.getString(R.string.map_close) ||
                it.contentDescription?.toString() == activity.getString(R.string.map_close))
        }
        val back = backs.firstOrNull { n -> Rect().also(n::getBoundsInScreen).let { it.bottom <= padBounds.top } }
        assertNotNull("Back must have visible bounds above the native ink pad", back)
        val rect = Rect().also(requireNotNull(back)::getBoundsInScreen)
        val shot = requireNotNull(runner.uiAutomation.takeScreenshot())
        try {
            val colours = hashSetOf<Int>()
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right)
                colours += shot.getPixel(x,y)
            assertTrue("Back icon/text must be painted, not covered by a uniform pad background", colours.size > 2)
        } finally { shot.recycle() }
        click(activity.getString(R.string.map_close))
        await("enlarged editor closed and graph interactive") { main {
            views(activity.window.decorView).none { it is InkPadView } && graph.isEnabled
        } }
        assertEquals(1, main { session.document!!.nodes.count { it.kind == NodeKind.INK } })
    }

    @Test fun openingAnExistingInkNodeAndCreatingItsSiblingBothShowThePad() = scenario(initialNodes=listOf(
        MapNode(id="root",parent=null,text="Visible topic"),
        MapNode(id="ink",parent="root",text="Existing ink",kind=NodeKind.INK)
    )) { activity, session, graph ->
        await("interactive graph") {main {graph.isEnabled}}
        click(activity.getString(R.string.map_menu));click(activity.getString(R.string.map_node_list));click("Existing ink")
        click(activity.getString(R.string.ink_enlarge))
        await("existing ink pad drawn") {main {views(activity.window.decorView).any {it is InkPadView && it.isShown && it.width>0}}}
        runner.waitForIdleSync()
        click(activity.getString(R.string.map_close))
        await("existing ink pad closed") {main {views(activity.window.decorView).none {it is InkPadView}}}
        click(activity.getString(R.string.map_add_sibling))
        click(activity.getString(R.string.map_create_confirm))
        await("ink sibling and pad") {main {
            session.document!!.nodes.count {it.kind==NodeKind.INK}==2 &&
                views(activity.window.decorView).any {it is InkPadView && it.isShown && it.width>0}
        }}
        runner.waitForIdleSync()
        assertEquals("root",main {session.document!!.nodes.single {it.id==session.selected}.parent})
        click(activity.getString(R.string.map_close))
    }

    @Test fun inkPadCannotPaintOutsideItsOwnBoundsOnAnUnclippedHostCanvas() = main {
        val bitmap = android.graphics.Bitmap.createBitmap(240, 200, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val sentinel = android.graphics.Color.MAGENTA
            bitmap.eraseColor(sentinel)
            val canvas = android.graphics.Canvas(bitmap)
            val pad = InkPadView(runner.targetContext)
            pad.layout(0, 0, 100, 80)
            canvas.save(); canvas.translate(50f, 60f)
            val hostCount=canvas.saveCount
            repeat(3) {pad.draw(canvas);assertEquals("Drawing must preserve the host canvas stack",hostCount,canvas.saveCount)}
            canvas.restore()
            assertEquals("Drawing must preserve the toolbar area above the pad", sentinel, bitmap.getPixel(70, 30))
            assertEquals("Drawing must preserve the neighboring pane", sentinel, bitmap.getPixel(190, 100))
            assertNotEquals("The pad itself must still paint", sentinel, bitmap.getPixel(100, 100))
        } finally { bitmap.recycle() }
    }

}
