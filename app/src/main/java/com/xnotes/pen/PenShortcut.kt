package com.xnotes.pen

/** Persistent input identity. Android's transient deviceId is deliberately not stored. */
data class PenTrigger(
    val kind: Kind,
    val code: Int,
    val descriptor: String,
    val source: Int,
    val scanCode: Int = 0,
) {
    enum class Kind { MOTION, KEY }
    val valid: Boolean get() = if (kind == Kind.MOTION) Integer.bitCount(code) == 1 else code > 0
}

data class PenShortcut(
    val name: String,
    val triggers: List<PenTrigger>,
    val binding: PenBinding = PenBinding(),
) {
    // A deterministic primary prevents delayed, non-overlapping aliases from double-firing.
    // Recording away from the display yields a key-only mapping when remote use is desired.
    val primary: PenTrigger get() = triggers.firstOrNull { it.kind == PenTrigger.Kind.MOTION } ?: triggers.first()
    companion object {
        const val MAX_SHORTCUTS = 32
        const val MAX_SIGNALS = 2
        fun valid(shortcuts: List<PenShortcut>): Boolean =
            shortcuts.size <= MAX_SHORTCUTS && shortcuts.all {
                it.name.isNotBlank() && it.name.length <= 80 &&
                    it.triggers.isNotEmpty() && it.triggers.size <= MAX_SIGNALS &&
                    it.triggers.all(PenTrigger::valid) &&
                    it.triggers.map { t -> t.kind }.distinct().size == it.triggers.size &&
                    it.binding.action in PenBinding.actions
            } && shortcuts.flatMap { it.triggers }.let { it.distinct().size == it.size }
    }
}

/** Records one press including aliases reported by different Android event routes. */
class PenRecording {
    enum class Failure { CANCELED, MULTIPLE_BUTTONS }
    private val pressed = linkedSetOf<PenTrigger>()
    private val seen = linkedSetOf<PenTrigger>()
    var failure: Failure? = null
        private set
    var revision = 0
        private set
    val released: Boolean get() = failure == null && seen.isNotEmpty() && pressed.isEmpty()

    fun event(trigger: PenTrigger, down: Boolean) {
        if (failure != null || !trigger.valid) return
        if (down) {
            if (seen.any { it.kind == trigger.kind && it != trigger }) {
                failure = Failure.MULTIPLE_BUTTONS
                revision++
                return
            }
            seen.add(trigger)
            if (pressed.add(trigger)) revision++
        } else if (pressed.remove(trigger)) revision++
    }
    fun finish(): List<PenTrigger>? = if (released) seen.toList() else null
    fun cancel() { failure = Failure.CANCELED; pressed.clear(); seen.clear(); revision++ }
}

/** Maps recorded identities to transient bits for the existing pure tool state machine. */
class PenShortcutState {
    private val held = linkedSetOf<PenTrigger>()
    fun keyOrButton(trigger: PenTrigger, down: Boolean) {
        if (down) held.add(trigger) else held.remove(trigger)
    }
    fun motion(triggers: Set<PenTrigger>) {
        held.removeAll { it.kind == PenTrigger.Kind.MOTION }
        held.addAll(triggers)
    }
    val heldSignals: Set<PenTrigger> get() = held.toSet()
    fun replace(signals: Set<PenTrigger>) { held.clear(); held.addAll(signals) }
    fun clear() = held.clear()
    fun mask(shortcuts: List<PenShortcut>): Int {
        return shortcuts.foldIndexed(0) { index, mask, shortcut ->
            if (shortcut.primary in held) mask or (1 shl index) else mask
        }
    }
    fun bindings(shortcuts: List<PenShortcut>): Map<Int, PenBinding> =
        shortcuts.mapIndexed { index, shortcut -> (1 shl index) to shortcut.binding }.toMap()
}

/** A workspace handoff preserves identity, press suppression and only still-effective HOLDs. */
data class PenInputSnapshot(
    val held: Set<PenTrigger>,
    val activeHolds: List<PenTrigger>,
    val motionDeviceId: Int?,
)
