package com.xnotes.map.source

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.core.util.DocumentKind
import com.xnotes.map.SourceKind
import com.xnotes.map.SourceRef
import com.xnotes.storage.NativeReferenceIssue
import com.xnotes.storage.NativeReferenceTarget
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferenceRequest
import com.xnotes.ui.NativeReferencePreparation
import com.xnotes.ui.NativeReferenceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The activity's unsaved-changes prompt before a reference opens, as the opener sees it. */
internal sealed interface GuardDecision {
    data class Go(val discarded: Set<Editor.OpenDiscard>) : GuardDecision
    data object Cancelled : GuardDecision
    data object Failed : GuardDecision
    data object Changed : GuardDecision
}

/**
 * [MapSourceOpener] over the editor's own reference navigation: `Editor.prepareReference` with a
 * [com.xnotes.storage.NativeReferenceTarget] built from the link (required kind CANVAS for a
 * canvas link), the activity's [guard], then `Editor.openReference`. Back reopens the canvas with
 * `Editor.openCanvasAsync` and restores the remembered view and selection. Main thread only.
 * [resolver] explains a failure as a [SourceStatus] when it can (read off the main thread).
 * [canGoBack] is Compose state: true while a trip is pending and the map's canvas is not the
 * document open in the root pane.
 */
internal class EditorMapSourceOpener(
    private val editor: Editor,
    private val guard: suspend (NativeReferenceRequest, current: () -> Boolean) -> GuardDecision,
    private val resolver: SourceResolver,
    /** Called right after the target (or, going back, the canvas) is adopted, so the activity can leave home and menus. */
    private val onShown: () -> Unit,
) : MapSourceOpener {
    override val trip = SourceTrip()
    private var running by mutableStateOf(false)
    override val busy: Boolean get() = running

    private var bookmarkId by mutableStateOf<String?>(null)
    private var generation = 0L
    val canGoBack: Boolean get() {
        if (bookmarkId == null) return false
        editor.presentationVersion // The canvas model can change while the pane stays in canvas mode.
        return offersMapReturn(bookmarkId, if (editor.noteOpen && editor.canvasOpen) editor.visibleDocumentId else null)
    }

    override suspend fun follow(ref: SourceRef, from: MapReturn): FollowResult {
        if (running) return FollowResult.Failed(null)
        running = true
        return try { followSource(ref, from, generation) } finally { running = false }
    }

    private suspend fun followSource(ref: SourceRef, from: MapReturn, token: Long): FollowResult {
        if (!editor.canvasOpen) return FollowResult.Failed(null)
        val canvas = editor.infinite
        val doc = canvas.document
        fun valid() = token == generation && editor.noteOpen && editor.canvasOpen && canvas.document === doc &&
            doc.identity.documentId == from.canvasDocumentId && doc.path == from.canvasLocation &&
            doc.map.state.nodes[from.node]?.sources?.contains(ref) == true
        if (!valid()) return FollowResult.Failed(null)
        canvas.map.commitTitle()
        val kind = if (ref.kind == SourceKind.CANVAS) DocumentKind.CANVAS else DocumentKind.NOTE
        if ((kind == DocumentKind.CANVAS) != (ref.pageId == null)) return FollowResult.Failed(null)
        val target = try { NativeReferenceTarget(ref.documentId, ref.pageId, requiredKind = kind) }
            catch (_: IllegalArgumentException) { return FollowResult.Failed(null) }
        suspend fun failure(issue: NativeReferenceIssue): FollowResult.Failed {
            if (!valid() || issue !in explainableIssues) return FollowResult.Failed(null)
            val status = withContext(Dispatchers.IO) {
                try { resolver.resolve(ref) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
            return FollowResult.Failed(if (valid()) sourceFailureStatus(issue, status) else null)
        }
        val prepared = editor.prepareReference(target)
        if (prepared is NativeReferencePreparation.Failed) return failure(prepared.issue)
        val request = (prepared as NativeReferencePreparation.Ready).request
        try {
            if (!valid() || !request.current()) return FollowResult.Failed(null)
            val decision = guard(request, ::valid)
            if (!valid() || !request.current()) return FollowResult.Failed(null)
            return when (decision) {
                GuardDecision.Cancelled -> FollowResult.Cancelled
                GuardDecision.Failed, GuardDecision.Changed -> FollowResult.Failed(null)
                is GuardDecision.Go -> {
                    val opened = editor.openReference(request, decision.discarded, ::valid) {
                        trip.leave(from)
                        bookmarkId = from.canvasDocumentId
                        onShown()
                    }
                    when (opened) {
                        is NativeReferenceResult.Opened -> FollowResult.Opened
                        is NativeReferenceResult.Failed -> failure(opened.issue)
                    }
                }
            }
        } finally { request.cancel() }
    }

    private suspend fun canvasIdAt(location: String): String? = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        try {
            NotebookStorage.get(editor.view.context).referenceCatalog(location, DocumentKind.CANVAS) { coroutine.ensureActive() }.documentId
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    override suspend fun back(): BackResult {
        if (running) return BackResult.STAYED
        val from = trip.pending ?: return BackResult.STAYED
        val token = generation
        val model = editor.penInputOwnerToken
        val wasOpen = editor.noteOpen
        fun current() = token == generation && editor.noteOpen == wasOpen && editor.penInputOwnerToken === model
        running = true
        try {
            val id = canvasIdAt(from.canvasLocation)
            if (!current() || trip.pending !== from) return BackResult.STAYED
            // SourceTrip is main-thread owned. Supply the read-only IO result for exactly this location.
            val check = trip.back { location -> id.takeIf { location == from.canvasLocation } }
            bookmarkId = null
            if (check !is ReturnCheck.Go) return BackResult.CANVAS_GONE
            val result = backTo(check.to, ::current)
            if (result == BackResult.STAYED && current()) {
                // A dismissed guard leaves the source and its return action available.
                trip.leave(from)
                bookmarkId = from.canvasDocumentId
            }
            return result
        } finally { running = false }
    }

    private suspend fun backTo(from: MapReturn, current: () -> Boolean): BackResult {
        val location = from.canvasLocation
        val target = NativeReferenceTarget(from.canvasDocumentId, requiredKind = DocumentKind.CANVAS)
        val prepared = editor.prepareReference(target, selectedUri = location)
        if (!current()) return BackResult.STAYED
        suspend fun unopened() = if (canvasIdAt(location) != from.canvasDocumentId && current()) BackResult.CANVAS_GONE else BackResult.STAYED
        if (prepared !is NativeReferencePreparation.Ready) return unopened()
        val request = prepared.request
        try {
            if (!request.current()) return BackResult.STAYED
            val decision = guard(request, current)
            if (decision !is GuardDecision.Go || !current() || !request.current()) return BackResult.STAYED
            val opened = editor.openCanvasAsync(location, request, decision.discarded, current) {
                val canvas = editor.infinite
                canvas.restoreMapView(from.viewX, from.viewY, from.zoom)
                if (canvas.map.shown.rectOf(from.node) != null) canvas.map.select(from.node)
                onShown()
            }
            return if (opened) BackResult.BACK else unopened()
        } finally { request.cancel() }
    }

    fun userOpenedElsewhere() {
        generation++
        trip.forget()
        bookmarkId = null
    }
}

internal fun offersMapReturn(bookmark: String?, openCanvas: String?) = bookmark != null && bookmark != openCanvas

private val explainableIssues = setOf(NativeReferenceIssue.DELETED, NativeReferenceIssue.UNAVAILABLE, NativeReferenceIssue.UNSUPPORTED)

internal fun sourceFailureStatus(issue: NativeReferenceIssue, status: SourceStatus?): SourceStatus? = when (issue) {
    NativeReferenceIssue.DELETED -> status as? SourceStatus.PageDeleted
    NativeReferenceIssue.UNSUPPORTED -> status?.takeIf { it == SourceStatus.WrongKind }
    NativeReferenceIssue.UNAVAILABLE -> status?.takeUnless { it is SourceStatus.Ready }
    NativeReferenceIssue.DUPLICATE, NativeReferenceIssue.CHANGED, NativeReferenceIssue.BUSY -> null
}
