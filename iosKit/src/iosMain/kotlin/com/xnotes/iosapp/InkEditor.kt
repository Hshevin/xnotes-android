package com.xnotes.iosapp

import androidx.compose.runtime.*
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.*
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.EraseSession
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.*
import com.xnotes.edit.PageSpace
import com.xnotes.edit.PagedStrokeCapture
import com.xnotes.pen.pencil.*
import com.xnotes.platform.ios.MetalViewport
import com.xnotes.storage.lite.*
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults

internal class InkEditor(
    val session: LiteSession,
    private val worker: SaveWorker = SaveWorker.Background,
    private val leave: () -> Unit,
) {
    private val history = History()
    private val gestures = PencilGestures(Tool.PEN.id)
    var tool by mutableStateOf(Tool.PEN); private set
    var active by mutableStateOf(false); private set
    var revision by mutableIntStateOf(0); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var popup by mutableStateOf<ToolPopup?>(null)
    val tools = ToolSettings()
    var dark by mutableStateOf(false)
    var finger by mutableStateOf(NSUserDefaults.standardUserDefaults.boolForKey("fingerDrawing")); private set
    var warning by mutableStateOf<String?>(null); private set
    var canLeaveProtected by mutableStateOf(false); private set
    /** A write is running off the main thread; editing carries on and the next save waits for it. */
    var saving by mutableStateOf(false); private set
    private var pendingBack = false
    private var pendingBackground = false
    var invalidate: (() -> Unit)? = null
    var liveCanvasView: MetalViewport? = null
    var selection by mutableStateOf<ItemSelection?>(null)
    val selectionClipboard = SelectionClipboard()
    var textSurface by mutableStateOf<TextSurface?>(null)
    fun recordText(command: Command) { history.push(command); edited() }
    val panels = DocumentPanels(this)
    val canChangePanel get() = !active || selection?.tracking == true
    private var cancelPointer: (() -> Unit)? = null
    private class PanelCommand(command: Command) : Command by command

    fun preparePanelChange(): Boolean {
        if (!canChangePanel) return false
        textSurface?.finishEditing()
        if (selection?.tracking == true) cancelPointer?.invoke()
        selection?.clear()
        return !active
    }

    fun editPanel(edit: () -> Command?): Boolean {
        if (!preparePanelChange()) return false
        edit()?.let { history.push(PanelCommand(it)) }; edited(); invalidate?.invoke()
        return true
    }
    private var surfaces = 0
    private var released = false
    fun attachSurface() { check(!released); surfaces++ }
    fun releaseSurface() { surfaces--; closeIfDone() }
    fun releaseOwner() { cancelPointer?.invoke(); selection?.clear(); textSurface?.close(); released = true; closeIfDone() }
    private fun closeIfDone() { if (surfaces == 0 && released && !saving) session.close() }

    fun select(next: Tool) {
        if (next != tool) { cancelPointer?.invoke(); if (!active) leaveTool() }
        if (!active) { gestures.manualSelect(next.id); tool = next; popup = null }
    }
    private fun leaveTool() { selection?.clear(); textSurface?.close() }
    fun toggleFinger() { if (!active) { finger = !finger; NSUserDefaults.standardUserDefaults.setBool(finger, "fingerDrawing") } }
    fun gesture(event: PencilGesture, action: PencilPreferredAction) {
        if (selection?.tracking == true) cancelPointer?.invoke()
        apply(gestures.onGesture(event, action, strokeActive = active))
    }
    private fun apply(outcome: PencilOutcome) {
        val next = Tool.fromId(outcome.tool) ?: tool
        if (tool != next) { leaveTool(); popup = null }
        tool = next
        if (PencilGestures.SHOW_TOOL_PALETTE in outcome.commands) popup = if (popup != null) null else ToolPopup.of(tool) ?: ToolPopup.SETTINGS
    }

    fun begin(cancel: (() -> Unit)? = null) { check(!active); cancelPointer = cancel; active = true; revision++ }
    fun end(command: Command?, cancelled: Boolean) {
        if (command != null) {
            if (cancelled) command.undo() else { history.push(command); edited() }
            invalidate?.invoke()
        }
        cancelPointer = null; active = false
        if (cancelled) revision++
        apply(gestures.onStrokeEnd(cancelled))
        if (pendingBack || pendingBackground) save(pendingBack, pendingBackground)
    }

    fun replay(undo: Boolean) {
        if (selection?.tracking == true) cancelPointer?.invoke()
        textSurface?.settle()
        if (active || !(if (undo) canUndo else canRedo)) return
        if ((if (undo) history.nextUndo else history.nextRedo) is PanelCommand) textSurface?.finishEditing()
        selection?.clear()
        if (undo) history.undo() else history.redo()
        edited(); invalidate?.invoke(); textSurface?.replayed()
    }

    private fun edited() {
        session.markEdited(); revision++
        canUndo = history.canUndo; canRedo = history.canRedo
        canLeaveProtected = false; warning = null
    }

    fun save(back: Boolean = false, background: Boolean = false) {
        if ((back || background) && selection?.tracking == true) cancelPointer?.invoke()
        if (active) { pendingBack = pendingBack || back; pendingBackground = pendingBackground || background; return }
        val text = textSurface
        if (text?.target?.input?.marked != null) {
            if (!back) { text.withCommittedText { saveStable(false, background) }; return }
            text.settle()
        }
        saveStable(back, background)
    }

    private fun saveStable(back: Boolean, background: Boolean) {
        if (saving) { pendingBack = pendingBack || back; pendingBackground = pendingBackground || background; return }
        pendingBack = false; pendingBackground = false
        val job = session.beginSave() ?: return finishSave(LiteSave.Saved, back, background)
        saving = true
        worker.launch({ try { job.run() } catch (_: Exception) { null } }) { result ->
            saving = false
            finishSave(result, back, background)
        }
    }

    private fun finishSave(result: LiteSave?, back: Boolean, background: Boolean) {
        // Edits made while the file was being written stay unsaved; going to the background keeps them in a private copy.
        val recovery = if (background && !released && session.dirty) session.beginRecovery() else null
        if (recovery == null) return report(result, back, recovered = false)
        saving = true
        worker.launch({ recovery.run() }) { recovered ->
            saving = false
            report(result, back, recovered)
        }
    }

    private fun report(result: LiteSave?, back: Boolean, recovered: Boolean) {
        val backgroundCopy = if (recovered) session.registeredRecoveryCopy else null
        val location = backgroundCopy ?: when (result) {
            is LiteSave.Failed -> result.protectedCopy
            is LiteSave.Conflict -> result.protectedCopy
            else -> null
        }
        canLeaveProtected = location != null && location == session.registeredRecoveryCopy
        val displayPath = location?.toString()?.removePrefix(NSHomeDirectory().trimEnd('/') + "/")
        warning = when {
            result == LiteSave.Saved -> null
            location == null -> text("save_unprotected")
            !canLeaveProtected -> text("save_unverified") + " " + displayPath
            else -> text(if (result is LiteSave.Conflict) "save_conflict" else "save_failed") + " " + displayPath
        }
        if (released) return closeIfDone()
        if (back && result == LiteSave.Saved && !session.dirty) return leave()
        if (pendingBack || pendingBackground || (back && result == LiteSave.Saved)) save(pendingBack || back, pendingBackground)
    }

    fun leaveProtected() { if (!active && canLeaveProtected) leave() }
}

internal class InkGesture(private val editor: InkEditor, private val space: PageSpace, pressure: Boolean) {
    private val session = editor.session
    private val tool = editor.tool
    private val config = editor.tools.config(tool, editor.dark)
    private val feed = PencilFeed(pressure)
    private var finished = false
    private val capture = PagedStrokeCapture(space)
    private val retired = mutableListOf<PagedStrokeCapture.Segment>()
    private val canvasEraser = if (tool == Tool.ERASER) session.canvas?.let(::EraseSession) else null
    private val pagedEraser = if (tool == Tool.ERASER) session.note?.let(::PageEraser) else null

    fun begin(sample: PencilSample, cancel: () -> Unit = { finish(emptyList(), true) }) {
        editor.begin(cancel)
        val first = feed.begin(sample)
        val point = Pt(first.x, first.y)
        if (tool == Tool.ERASER) { if (erase(point)) editor.invalidate?.invoke() }
        else capture.begin(point, first.pressure, tool, config, first.timeMs, space.zoom)
    }

    fun move(actual: List<PencilSample>, predicted: List<PencilSample>) { if (!finished) accept(feed.move(actual, predicted)) }
    fun update(index: Long, force: Double, maxForce: Double) { if (!finished) accept(feed.update(index, force, maxForce)) }

    fun needsLayerComposition(handles: (Int) -> Boolean) = capture.active && (capture.page?.let(handles) == true ||
        retired.any { handles(it.page) } || feed.wetTail().any { space.pageAt(Pt(it.x, it.y))?.let(handles) == true })

    fun preview(draw: (Int, Stroke) -> Unit) {
        if (finished) return
        retired.forEach { draw(it.page, it.stroke) }
        val live = capture.live ?: return
        val page = checkNotNull(capture.page)
        draw(page, live)
        val tail = feed.wetTail()
        if (tail.isEmpty()) return
        val last = live.samples.last()
        val preview = PagedStrokeCapture(space)
        preview.beginOnPage(page, space.fromPage(page, last.pos), last.pressure, tool, live.config, tail.first().timeMs, live.speedScale)
        tail.forEach { sample -> preview.move(Pt(sample.x, sample.y), sample.pressure, sample.timeMs, force = true)?.let { draw(it.fromPage, it.retired) } }
        draw(checkNotNull(preview.page), checkNotNull(preview.live))
    }

    private fun accept(points: List<PencilFeed.Released>, up: Boolean = false) {
        var erased = false
        points.forEachIndexed { index, sample ->
            val point = Pt(sample.x, sample.y)
            if (tool == Tool.ERASER) erased = erase(point) || erased
            else capture.move(point, sample.pressure, sample.timeMs, force = up && index == points.lastIndex)?.let {
                retired += PagedStrokeCapture.Segment(it.retired, it.fromPage)
            }
        }
        if (erased) editor.invalidate?.invoke()
    }

    private fun erase(point: Pt): Boolean {
        val radius = config.baseWidth / if (config.scale) 1.0 else space.zoom
        if (canvasEraser != null) return canvasEraser.erase(point.x, point.y, radius, config.eraseMode == EraseMode.AREA) != null
        return pagedEraser?.erase(point, space, radius, config) == true
    }

    fun finish(last: List<PencilSample>, cancelled: Boolean) {
        if (finished) return
        finished = true
        val command = if (cancelled) {
            feed.cancel(); capture.cancel(); null
        } else {
            accept(feed.end(last), up = true)
            val additions = capture.finish().filter { space.writable(it.page) }.map { (stroke, index) ->
                stroke.trimToSize()
                (session.note?.let { AddItem(it.pages[index], stroke) } ?: AddCanvasItem(checkNotNull(session.canvas), stroke)).also { it.redo() }
            }
            additions.takeIf { it.isNotEmpty() }?.let(::CompositeCommand)
        }
        editor.end(command ?: canvasEraser?.buildCommand() ?: pagedEraser?.command(), cancelled)
    }
}
