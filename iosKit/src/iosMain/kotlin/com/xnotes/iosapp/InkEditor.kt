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
import com.xnotes.storage.lite.*
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults

internal class InkEditor(val session: LiteSession, private val leave: () -> Unit) {
    private val history = History()
    private val gestures = PencilGestures(Tool.PEN.id)
    var tool by mutableStateOf(Tool.PEN); private set
    var active by mutableStateOf(false); private set
    var revision by mutableIntStateOf(0); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var settings by mutableStateOf(false)
    var finger by mutableStateOf(NSUserDefaults.standardUserDefaults.boolForKey("fingerDrawing")); private set
    var area by mutableStateOf(NSUserDefaults.standardUserDefaults.boolForKey("areaEraser")); private set
    var warning by mutableStateOf<String?>(null); private set
    var canLeaveProtected by mutableStateOf(false); private set
    private var pendingBack = false
    private var pendingBackground = false
    var invalidate: (() -> Unit)? = null
    private var surfaces = 0
    private var released = false
    fun attachSurface() { check(!released); surfaces++ }
    fun releaseSurface() { surfaces--; if (surfaces == 0 && released) session.close() }
    fun releaseOwner() { released = true; if (surfaces == 0) session.close() }

    fun select(next: Tool) { if (!active) { gestures.manualSelect(next.id); tool = next } }
    fun toggleFinger() { if (!active) { finger = !finger; NSUserDefaults.standardUserDefaults.setBool(finger, "fingerDrawing") } }
    fun toggleArea() { if (!active) { area = !area; NSUserDefaults.standardUserDefaults.setBool(area, "areaEraser") } }
    fun gesture(event: PencilGesture, action: PencilPreferredAction) = apply(gestures.onGesture(event, action, strokeActive = active))
    private fun apply(outcome: PencilOutcome) {
        tool = Tool.fromId(outcome.tool) ?: tool
        if (PencilGestures.SHOW_TOOL_PALETTE in outcome.commands) settings = !settings
    }

    fun begin() { check(!active); active = true; revision++ }
    fun end(command: Command?, cancelled: Boolean) {
        if (command != null) {
            if (cancelled) command.undo() else { history.push(command); edited() }
            invalidate?.invoke()
        }
        active = false
        apply(gestures.onStrokeEnd(cancelled))
        if (pendingBack || pendingBackground) save(pendingBack, pendingBackground)
    }

    fun replay(undo: Boolean) {
        if (active || !(if (undo) canUndo else canRedo)) return
        if (undo) history.undo() else history.redo()
        edited(); invalidate?.invoke()
    }

    private fun edited() {
        session.markEdited(); revision++
        canUndo = history.canUndo; canRedo = history.canRedo
        canLeaveProtected = false; warning = null
    }

    fun save(back: Boolean = false, background: Boolean = false) {
        if (active) { pendingBack = pendingBack || back; pendingBackground = pendingBackground || background; return }
        pendingBack = false; pendingBackground = false
        val result = try { session.save() } catch (_: Exception) { null }
        val backgroundCopy = if (background && session.dirty && session.writeRecovery()) session.registeredRecoveryCopy else null
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
        if (back && result == LiteSave.Saved) leave()
    }

    fun leaveProtected() { if (!active && canLeaveProtected) leave() }
}

internal class InkGesture(private val editor: InkEditor, private val space: PageSpace, pressure: Boolean) {
    private val session = editor.session
    private val tool = editor.tool
    private val config = ToolDefaults.configFor(tool).let { when (tool) {
        Tool.ERASER -> it.copy(eraseMode = if (editor.area) EraseMode.AREA else EraseMode.STROKE)
        Tool.HIGHLIGHTER -> it.copy(rgba = Rgba(170, 170, 170))
        Tool.PEN -> it.copy(rgba = black)
        else -> it
    } }
    private val feed = PencilFeed(pressure)
    private val capture = PagedStrokeCapture(space)
    private val retired = mutableListOf<PagedStrokeCapture.Segment>()
    private val canvasEraser = if (tool == Tool.ERASER) session.canvas?.let(::EraseSession) else null
    private val pagedEraser = if (tool == Tool.ERASER) session.note?.let(::PageEraser) else null

    fun begin(sample: PencilSample) {
        editor.begin()
        val first = feed.begin(sample)
        val point = Pt(first.x, first.y)
        if (tool == Tool.ERASER) { if (erase(point)) editor.invalidate?.invoke() }
        else capture.begin(point, first.pressure, tool, config, first.timeMs, space.zoom)
    }

    fun move(actual: List<PencilSample>, predicted: List<PencilSample>) = accept(feed.move(actual, predicted))
    fun update(index: Long, force: Double, maxForce: Double) = accept(feed.update(index, force, maxForce))

    fun preview(draw: (Int, Stroke) -> Unit) {
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
