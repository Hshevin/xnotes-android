package com.xnotes.pen.pencil

import com.xnotes.pen.PenActionIds

enum class PencilGesture { DOUBLE_TAP, SQUEEZE }

enum class PencilPreferredAction {
    IGNORE, SWITCH_ERASER, SWITCH_PREVIOUS, SHOW_COLOR_PALETTE, SHOW_INK_ATTRIBUTES, SHOW_CONTEXTUAL_PALETTE, RUN_SYSTEM_SHORTCUT,
}

sealed interface PencilBinding {
    data object FollowSystem : PencilBinding
    data class Tool(val id: String) : PencilBinding
    data class Command(val id: String) : PencilBinding
    data object None : PencilBinding
}

data class PencilOutcome(val tool: String, val commands: List<String> = emptyList())

class PencilGestures(initialTool: String) {
    var tool = initialTool
        private set
    private var previous: String? = null
    private val queued = mutableListOf<String>()

    fun manualSelect(tool: String) {
        if (this.tool == tool) return
        previous = this.tool
        this.tool = tool
    }

    @Suppress("UNUSED_PARAMETER")
    fun onGesture(
        gesture: PencilGesture, preferred: PencilPreferredAction, binding: PencilBinding = PencilBinding.FollowSystem,
        strokeActive: Boolean,
    ): PencilOutcome {
        val action = when (binding) {
            is PencilBinding.Tool -> binding.id.takeIf { it in TOOLS }
            is PencilBinding.Command -> binding.id.takeIf { it in COMMANDS }
            PencilBinding.None -> null
            PencilBinding.FollowSystem -> when (preferred) {
                PencilPreferredAction.IGNORE, PencilPreferredAction.RUN_SYSTEM_SHORTCUT -> null
                PencilPreferredAction.SWITCH_ERASER -> "toggle_eraser"
                PencilPreferredAction.SWITCH_PREVIOUS -> "toggle_previous"
                PencilPreferredAction.SHOW_COLOR_PALETTE, PencilPreferredAction.SHOW_INK_ATTRIBUTES,
                PencilPreferredAction.SHOW_CONTEXTUAL_PALETTE -> SHOW_TOOL_PALETTE
            }
        } ?: return PencilOutcome(tool)
        if (strokeActive) {
            queued.add(action)
            return PencilOutcome(tool)
        }
        val command = apply(action)
        return PencilOutcome(tool, listOfNotNull(command))
    }

    fun onStrokeEnd(cancelled: Boolean): PencilOutcome {
        val commands = if (cancelled) emptyList() else queued.mapNotNull(::apply)
        queued.clear()
        return PencilOutcome(tool, commands)
    }

    private fun apply(action: String): String? {
        when (action) {
            "toggle_previous" -> previous?.let(::manualSelect)
            "toggle_eraser", "toggle_pan" -> {
                val target = if (action == "toggle_eraser") "eraser" else "pan"
                manualSelect(if (tool == target) previous ?: "pen" else target)
            }
            in TOOLS -> manualSelect(action)
            else -> return action
        }
        return null
    }

    companion object {
        val TOOLS = PenActionIds.tools
        val COMMANDS = PenActionIds.commands
        const val SHOW_TOOL_PALETTE = "show_tool_palette"
    }
}
