package com.xnotes.pen

enum class PenBindingMode { HOLD, SELECT }

/**
 * What one recorded button does: [action] on a press (held for [PenBindingMode.HOLD], picked for
 * [PenBindingMode.SELECT]), and optionally [double] on a quick second press. A double press only
 * selects a tool or runs a command; there is nothing to hold.
 */
data class PenBinding(
    val action: String = "none",
    val mode: PenBindingMode = PenBindingMode.HOLD,
    val double: String = "none",
) {
    companion object {
        val tools = setOf("pen", "highlighter", "eraser", "pan", "select", "lasso")
        /** One-shot actions, the same ids the finger-tap gestures dispatch. */
        val commands = setOf("undo", "redo", "toggle_previous", "toggle_eraser", "toggle_pan")
        val actions = tools + commands + "none"
        const val DEFAULT_DOUBLE_PRESS_MS = 300
        val DOUBLE_PRESS_RANGE = 150..800
    }
}

data class PenButtonResult(val tool: String, val commands: List<String> = emptyList())

/**
 * Pure edge tracking. Bits may be raw buttons in replay tests or recorded shortcut slots.
 *
 * A button with a [PenBinding.double] action: a hold still starts at once (the borrowed tool is
 * restored on release anyway), but a picked tool or a command waits [doublePressMs] for a second
 * press; [expire] runs it when nothing came. A second press in time runs only the double action.
 */
class ProPenButtons(initialTool: String, private val doublePressMs: () -> Long = { PenBinding.DEFAULT_DOUBLE_PRESS_MS.toLong() }) {
    private var baseTool = initialTool
    private var previousMask = 0
    private val holds = linkedMapOf<Int, String>()
    /** Press time of each double-capable button awaiting its second press, and the single action it holds back. */
    private data class FirstPress(val at: Long, val waiting: PenBinding?)
    private val firstPress = linkedMapOf<Int, FirstPress>()

    /** When the oldest single press still waiting for a second runs, or null when none waits. */
    val nextDeadline: Long? get() = firstPress.values.filter { it.waiting != null }.minOfOrNull { it.at + doublePressMs() }

    /** [at] is the event's uptime in ms; it only matters for buttons with a double action. */
    fun update(rawMask: Int, bindings: Map<Int, PenBinding>, at: Long = 0L): PenButtonResult {
        val commands = mutableListOf<String>()
        expireInto(at, commands)
        val pressed = rawMask and previousMask.inv()
        previousMask = rawMask // Unbound bits still participate in edge tracking.
        holds.keys.removeAll { rawMask and it == 0 }
        for (mask in bindings.keys) {
            if (pressed and mask == 0) continue
            val binding = bindings[mask] ?: continue
            if (binding.double != "none") {
                val first = firstPress.remove(mask)
                if (first != null && at - first.at in 0..doublePressMs()) {
                    run(binding.double, commands)
                    continue
                }
                if (binding.action in PenBinding.tools && binding.mode == PenBindingMode.HOLD) {
                    holds[mask] = binding.action
                    firstPress[mask] = FirstPress(at, null)
                } else {
                    firstPress[mask] = FirstPress(at, binding)
                }
                continue
            }
            when (binding.action) {
                in PenBinding.commands -> commands.add(binding.action)
                in PenBinding.tools -> if (binding.mode == PenBindingMode.HOLD) holds[mask] = binding.action else select(binding.action)
            }
        }
        return PenButtonResult(holds.values.lastOrNull() ?: baseTool, commands)
    }

    /** Run the single presses whose double-press window has closed by [at]. */
    fun expire(at: Long): PenButtonResult {
        val commands = mutableListOf<String>()
        expireInto(at, commands)
        return PenButtonResult(holds.values.lastOrNull() ?: baseTool, commands)
    }

    private fun expireInto(at: Long, commands: MutableList<String>) {
        val window = doublePressMs()
        val due = firstPress.filterValues { at - it.at > window }
        due.forEach { (mask, first) ->
            firstPress.remove(mask)
            first.waiting?.let { run(it.action, commands) }
        }
    }

    private fun run(action: String, commands: MutableList<String>) {
        when (action) {
            in PenBinding.commands -> commands.add(action)
            in PenBinding.tools -> select(action)
        }
    }

    /** Explicit selection, excluding any borrowed HOLD tool. */
    val selectedTool: String get() = baseTool

    val observedButtons: Int get() = previousMask
    val activeHoldButtons: List<Int> get() = holds.keys.toList()

    fun inherit(rawMask: Int, activeHolds: List<Int>, bindings: Map<Int, PenBinding>): PenButtonResult {
        previousMask = rawMask
        holds.clear()
        firstPress.clear()
        activeHolds.forEach { mask ->
            val binding = bindings[mask]
            if (rawMask and mask != 0 && binding?.mode == PenBindingMode.HOLD && binding.action in PenBinding.tools) {
                holds[mask] = binding.action
            }
        }
        return PenButtonResult(holds.values.lastOrNull() ?: baseTool)
    }

    /** An explicit selection wins over all older temporary selections, even the same tool. */
    fun select(tool: String) {
        baseTool = tool
        holds.clear()
    }

    /** Losing focus cancels holds and presses still waiting for a second, retaining edges so a still-held command cannot fire twice. */
    fun reset(releasedButtons: Int = 0, observedButtons: Int = previousMask): PenButtonResult {
        previousMask = observedButtons and releasedButtons.inv()
        holds.clear()
        firstPress.clear()
        return PenButtonResult(baseTool)
    }

    /** Hover exit also precedes contact: hide the temporary tool without inventing a release. */
    fun suspend(): PenButtonResult = PenButtonResult(baseTool)

}
