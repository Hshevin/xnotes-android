package com.xnotes.pen.pencil

import kotlin.math.roundToLong

class PencilFeed(private val pressureSupported: Boolean, private val settleMs: Long = 40) {
    data class Released(val x: Double, val y: Double, val pressure: Double, val timeMs: Long)

    private val queue = ArrayDeque<PencilSample>()
    private var predictions = emptyList<PencilSample>()
    private var active = false
    private var newest = 0L

    fun begin(sample: PencilSample): Released {
        cancel()
        active = true
        return release(sample).also { newest = it.timeMs }
    }

    fun move(coalesced: List<PencilSample>, predicted: List<PencilSample>): List<Released> {
        if (!active) return emptyList()
        predictions = predicted
        queue.addAll(coalesced)
        coalesced.lastOrNull()?.let { newest = maxOf(newest, millis(it)) }
        return drain(false)
    }

    fun update(index: Long, force: Double, maxForce: Double): List<Released> {
        val position = queue.indexOfFirst { it.forcePending && it.estimationIndex == index }
        if (position < 0) return emptyList()
        queue[position] = queue[position].copy(force = force, maxForce = maxForce, forcePending = false)
        return drain(false)
    }

    fun end(last: List<PencilSample>): List<Released> {
        if (!active) return emptyList()
        queue.addAll(last)
        return drain(true).also { cancel() }
    }

    fun cancel() { queue.clear(); predictions = emptyList(); active = false }

    fun wetTail(): List<Released> = queue.map(::release) + predictions.map(::release)

    private fun drain(all: Boolean): List<Released> = buildList {
        while (queue.isNotEmpty()) {
            val head = queue.first()
            if (!all && pressureSupported && head.forcePending && head.estimationIndex != null && newest - millis(head) < settleMs) break
            add(release(queue.removeFirst()))
        }
    }

    private fun millis(sample: PencilSample) = (sample.timeSec * 1000).roundToLong()
    private fun release(sample: PencilSample) = Released(sample.x, sample.y,
        if (pressureSupported && sample.maxForce > 0) (sample.force / sample.maxForce).coerceIn(0.0, 1.0)
        else PencilStrokeAssembler.CONSTANT_PRESSURE, millis(sample))
}
