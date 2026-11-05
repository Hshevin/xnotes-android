package com.xnotes.pen

import android.view.KeyEvent
import android.view.MotionEvent
import com.xnotes.core.tools.Tool

/** Shared by the paged and infinite editors. Never consumes or rewrites ink samples. */
class ProPenInput(
    private val bindings: () -> List<PenShortcut>,
    private val currentTool: () -> Tool,
    private val armTool: (Tool) -> Unit,
    private val command: (String) -> Unit,
    private val selectionChanged: (Tool) -> Unit = {},
) {
    private val buttons by lazy { ProPenButtons(currentTool().id) }
    private val signals = PenShortcutState()
    private var applying = false
    private var contact = false
    val stylusInContact: Boolean get() = contact
    private var device: Int? = null

    // Store transitions, not samples. Nothing mutates the committed tool until the stroke ends.
    private data class Transition(val mask: Int, val pressed: Int, var bindings: Map<Int, PenBinding>)
    private val deferred = mutableListOf<Transition>()

    fun selected(tool: Tool) {
        if (!applying) {
            buttons.select(tool.id)
            selectionChanged(tool)
            // A later explicit selection supersedes queued tools, but not queued commands.
            deferred.forEach { it.bindings = it.bindings.filterValues { b -> b.action in PenBinding.commands } }
        }
    }

    fun reset() {
        val shortcuts = bindings()
        val observed = signals.mask(shortcuts)
        contact = false
        signals.clear()
        deferred.clear()
        val keySlots = shortcuts.foldIndexed(0) { index, mask, shortcut ->
            if (shortcut.primary.kind == PenTrigger.Kind.KEY) mask or (1 shl index) else mask
        }
        apply(buttons.reset(releasedButtons = keySlots, observedButtons = observed))
    }

    /** Host reparenting hides hover, but is not a physical button release or a window focus loss. */
    fun surfaceDetached() {
        if (contact) reset() else apply(buttons.suspend())
    }

    val snapshot: PenInputSnapshot get() {
        val shortcuts = bindings()
        val bySlot = shortcuts.mapIndexed { index, shortcut -> (1 shl index) to shortcut.primary }.toMap()
        return PenInputSnapshot(signals.heldSignals, buttons.activeHoldButtons.mapNotNull { bySlot[it] }, device)
    }

    /** Adopt identity and effective holds; never replay a command or a SELECT on focus change. */
    fun inherit(snapshot: PenInputSnapshot) {
        check(!contact)
        deferred.clear()
        device = snapshot.motionDeviceId
        signals.replace(snapshot.held)
        val shortcuts = bindings()
        val slots = shortcuts.mapIndexed { index, shortcut -> shortcut.primary to (1 shl index) }.toMap()
        apply(buttons.inherit(signals.mask(shortcuts), snapshot.activeHolds.mapNotNull { slots[it] }, signals.bindings(shortcuts)))
    }

    private fun stylusIndex(e: MotionEvent): Int =
        (0 until e.pointerCount).firstOrNull {
            e.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
        } ?: -1

    private fun trackDevice(e: MotionEvent) {
        if (device != e.deviceId) {
            if (device != null) {
                reset()
                buttons.reset(releasedButtons = -1) // A different physical device starts a new press stream.
            }
            device = e.deviceId
        }
    }

    fun touch(e: MotionEvent, dispatch: () -> Boolean): Boolean {
        val index = stylusIndex(e)
        if (index < 0) return dispatch()
        val action = e.actionMasked
        trackDevice(e)
        // Cancellation reaches the drawing engine before a temporary tool is restored.
        if (action == MotionEvent.ACTION_CANCEL) {
            val handled = dispatch()
            signals.motion(PenSignals.motion(e))
            reset()
            return handled
        }
        updateMotion(e)
        if (action == MotionEvent.ACTION_DOWN ||
            (action == MotionEvent.ACTION_POINTER_DOWN && e.actionIndex == index)) contact = true
        val handled = dispatch()
        if (action == MotionEvent.ACTION_UP ||
            (action == MotionEvent.ACTION_POINTER_UP && e.actionIndex == index)) {
            if ((e.flags and MotionEvent.FLAG_CANCELED) != 0) reset()
            else { contact = false; commitDeferred() }
        }
        return handled
    }

    fun motion(e: MotionEvent) {
        if (stylusIndex(e) < 0) return
        trackDevice(e)
        if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            if (!contact) apply(buttons.suspend())
        } else updateMotion(e)
    }

    private fun updateMotion(e: MotionEvent) {
        signals.motion(PenSignals.motion(e))
        update()
    }

    fun key(e: KeyEvent): Boolean {
        val trigger = PenSignals.key(e) ?: return false
        val shortcuts = bindings()
        if (shortcuts.none { trigger in it.triggers }) return false
        // An alias cannot revive a suspended motion HOLD or cancel another input's work.
        val slot = shortcuts.indexOfFirst { it.primary == trigger }
        if (slot < 0) return true
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount != 0) return true
        val wasHeld = trigger in signals.heldSignals
        signals.keyOrButton(trigger, e.action == KeyEvent.ACTION_DOWN && !e.isCanceled)
        if (e.isCanceled && wasHeld) {
            val mask = 1 shl slot
            deferred.lastOrNull { it.pressed and mask != 0 }?.let { it.bindings = it.bindings - mask }
        }
        update()
        return true
    }

    private fun update() {
        val shortcuts = bindings()
        val mask = signals.mask(shortcuts)
        val configured = signals.bindings(shortcuts)
        if (!contact) { apply(buttons.update(mask, configured)); return }
        val previous = deferred.lastOrNull()?.mask ?: buttons.observedButtons
        if (mask != previous) deferred.add(Transition(mask, mask and previous.inv(), configured.toMap()))
    }

    private fun commitDeferred() {
        var result: PenButtonResult? = null
        val commands = mutableListOf<String>()
        deferred.forEach {
            result = buttons.update(it.mask, it.bindings)
            commands.addAll(result!!.commands)
        }
        deferred.clear()
        result?.let { apply(it.copy(commands = commands)) }
    }

    private fun apply(result: PenButtonResult) {
        applying = true
        try {
            Tool.fromId(buttons.selectedTool)?.let(selectionChanged)
            Tool.fromId(result.tool)?.let { if (it != currentTool()) armTool(it) }
            result.commands.forEach(command)
        } finally { applying = false }
    }
}
