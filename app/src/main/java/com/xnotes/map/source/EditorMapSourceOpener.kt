package com.xnotes.map.source

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.map.SourceRef
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferenceRequest

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

    /** Whether "Back to map" is offered: a trip is pending and the map's canvas is not what is open. */
    var canGoBack by mutableStateOf(false)
        private set

    override suspend fun follow(ref: SourceRef, from: MapReturn): FollowResult = TODO("phase 3 backend")
    override suspend fun back(): BackResult = TODO("phase 3 backend")

    /** The user opened something themselves (home, file list, recent): forget the way back. */
    fun userOpenedElsewhere(): Unit = TODO("phase 3 backend")
}
