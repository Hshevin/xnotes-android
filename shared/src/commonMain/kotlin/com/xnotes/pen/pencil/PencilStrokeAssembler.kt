package com.xnotes.pen.pencil

import com.xnotes.core.stroke.Sample

data class PencilSample(
    val x: Double,
    val y: Double,
    val timeSec: Double,
    val force: Double,
    val maxForce: Double,
    val estimationIndex: Long?,
    val forcePending: Boolean,
    val rollAngle: Double? = null,
)

class PencilStrokeAssembler(private val pressureSupported: Boolean) {
    private var start: Double? = null
    private var points = mutableListOf<Sample>()
    private val pending = mutableMapOf<Long, Int>()
    val active: Boolean get() = start != null
    val committed: List<Sample> get() = points
    var predicted: List<Sample> = emptyList()
        private set

    fun begin(sample: PencilSample) {
        reset()
        start = sample.timeSec
        append(sample, sample.timeSec)
    }

    fun move(coalesced: List<PencilSample>, predicted: List<PencilSample>) {
        val origin = start ?: return
        coalesced.forEach { append(it, origin) }
        this.predicted = predicted.map { convert(it, origin) }
    }

    fun updateEstimated(index: Long, force: Double, maxForce: Double): Int {
        val position = pending.remove(index) ?: return -1
        points[position] = points[position].copy(pressure = pressure(force, maxForce))
        return position
    }

    fun end(last: List<PencilSample>): List<Sample> {
        val origin = start ?: return emptyList()
        last.forEach { append(it, origin) }
        return reset()
    }

    fun cancel(): List<Sample> = reset()

    private fun append(sample: PencilSample, origin: Double) {
        if (pressureSupported && sample.forcePending) sample.estimationIndex?.let { pending[it] = points.size }
        points.add(convert(sample, origin))
    }

    private fun convert(sample: PencilSample, origin: Double) = Sample(
        sample.x, sample.y, pressure(sample.force, sample.maxForce), (sample.timeSec - origin) * 1000.0,
    )

    private fun pressure(force: Double, maxForce: Double) =
        if (pressureSupported && maxForce > 0.0) (force / maxForce).coerceIn(0.0, 1.0) else CONSTANT_PRESSURE

    private fun reset(): List<Sample> {
        val result = points
        points = mutableListOf()
        pending.clear()
        predicted = emptyList()
        start = null
        return result
    }

    companion object {
        const val CONSTANT_PRESSURE = 1.0
    }
}
