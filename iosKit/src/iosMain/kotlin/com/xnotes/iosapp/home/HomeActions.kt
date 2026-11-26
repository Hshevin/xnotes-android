package com.xnotes.iosapp.home

import androidx.compose.runtime.*
import com.xnotes.core.model.Rgba
import com.xnotes.iosapp.store
import com.xnotes.iosapp.text
import com.xnotes.kio.KFiles
import com.xnotes.storage.ios.IosStorageLocations
import com.xnotes.storage.library.Library
import com.xnotes.storage.library.LibraryFailure
import com.xnotes.storage.library.LibraryResult
import com.xnotes.storage.library.TrashItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/** The top folder's colours, pins and Trash, kept on disk the way Android keeps them. */
internal val library by lazy { Library(KFiles.system, IosStorageLocations.documents, store, ::nowMillis) }

/** How long Trash keeps things before they go for good, Android's default. */
internal const val TRASH_DAYS = 30

/**
 * What the explorer does to files: each runs off the main thread, then the folders are read again and
 * Recent and the sidebar's pins follow what moved. Failures say so at the bottom of the screen.
 */
internal class HomeActions(private val state: HomeState, private val scope: CoroutineScope) {
    /** Notes pinned to the top of the top folder, and the names given to colours: both live in its sidecar. */
    var notePins by mutableStateOf(emptyList<Path>())
        private set
    var colorNames by mutableStateOf(emptyMap<Rgba, String>())
        private set

    /** Reads pins and colour names again, which another device may have changed. */
    fun reload() {
        scope.launch {
            val (pins, names) = withContext(Dispatchers.Default) { runCatching { library.notePins() to library.colorNames() }.getOrDefault(emptyList<Path>() to emptyMap()) }
            notePins = pins
            colorNames = names
        }
    }

    private fun io(after: () -> Unit = {}, work: suspend () -> Unit) {
        scope.launch {
            withContext(Dispatchers.Default) { work() }
            state.revision++
            after()
        }
    }

    /** Trash drops what has waited longer than Android's default. */
    fun purgeTrash() = io { library.purgeExpired(TRASH_DAYS) }

    private fun failed(results: List<LibraryResult>): Int = results.count { it !is LibraryResult.Success }

    fun rename(item: Path, name: String, done: (String?) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.Default) { library.rename(item, name) }
            if (result is LibraryResult.Success) {
                state.followMove(item, result.path)
                state.revision++
                done(null)
            } else done(errorText(result))
        }
    }

    fun trash(items: List<Path>) {
        if (items.isEmpty()) return
        state.selected = emptySet()
        var results = emptyList<LibraryResult>()
        io(after = {
            val gone = items.zip(results).filter { it.second is LibraryResult.Success }
            gone.forEach { (from, _) -> state.followMove(from, null) }
            when {
                gone.isEmpty() -> state.say(batchError(results, "err_move_to_trash"))
                else -> {
                    val message = if (gone.size == 1) fmt("moved_one_to_trash", shownName(gone[0].first))
                        else plural("moved_items_to_trash", gone.size)
                    state.say(message, text("undo")) { undoTrash(gone.map { it.second }) }
                }
            }
        }) { results = library.trash(items) }
    }

    private fun undoTrash(done: List<LibraryResult>) {
        val trashed = done.mapNotNull { (it as? LibraryResult.Success)?.path }.toSet()
        io {
            library.listTrash().filter { it.path in trashed }.forEach { library.restore(it) }
        }
    }

    fun moveInto(items: List<Path>, target: Path) {
        state.selected = emptySet()
        var results = emptyList<LibraryResult>()
        io(after = {
            items.zip(results).forEach { (from, r) -> if (r is LibraryResult.Success) state.followMove(from, r.path) }
            reload()
            if (failed(results) > 0) state.say(batchError(results, "err_move_some"))
        }) { results = library.moveInto(items, target) }
    }

    fun copy(items: List<Path>, cut: Boolean) {
        state.clip = Clip(items, cut)
        state.selected = emptySet()
    }

    fun paste(target: Path) {
        val clip = state.clip ?: return
        var results = emptyList<LibraryResult>()
        io(after = {
            if (clip.cut) clip.items.zip(results).forEach { (from, r) -> if (r is LibraryResult.Success) state.followMove(from, r.path) }
            if (failed(results) > 0) state.say(batchError(results, "err_paste_some")) else state.clip = null
        }) { results = if (clip.cut) library.moveInto(clip.items, target) else library.copyInto(clip.items, target) }
    }

    fun color(items: List<Path>, color: Rgba?) {
        state.selected = emptySet()
        io(after = ::reload) {
            items.groupBy { it.parent!! }.forEach { (dir, inDir) -> library.setColors(dir, inDir.associate { it.name to color }) }
        }
    }

    fun nameColor(color: Rgba, name: String?) = io(after = ::reload) { library.setColorName(color, name) }

    fun setNotePinned(doc: Path, pinned: Boolean) = io(after = ::reload) {
        if (!library.setNotePinned(doc, pinned)) withContext(Dispatchers.Main) { state.say(text("err_pin_note")) }
    }

    fun restore(item: TrashItem) = io {
        if (library.restore(item) !is LibraryResult.Success) withContext(Dispatchers.Main) { state.say(fmt("err_restore_item", shownName(item.path))) }
        else withContext(Dispatchers.Main) { state.say(fmt("restored_item", shownName(item.path))) }
    }

    fun deleteForever(item: TrashItem) = io { library.delete(item) }

    fun emptyTrash() = io { library.emptyTrash() }

    private fun errorText(result: LibraryResult): String = text(when ((result as? LibraryResult.Failure)?.reason) {
        LibraryFailure.NAME_CONFLICT -> "err_name_taken"
        LibraryFailure.INVALID_NAME -> "err_invalid_name"
        LibraryFailure.PENDING_RECOVERY -> "err_pending_recovery"
        else -> "err_rename"
    })

    /** Why some of a batch failed, when they all failed for one reason worth telling. */
    private fun batchError(results: List<LibraryResult>, fallback: String): String =
        if (results.any { (it as? LibraryResult.Failure)?.reason == LibraryFailure.PENDING_RECOVERY }) text("err_pending_recovery") else text(fallback)
}
