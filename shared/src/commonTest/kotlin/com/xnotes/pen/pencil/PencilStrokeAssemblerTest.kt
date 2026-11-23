package com.xnotes.pen.pencil

import com.xnotes.core.stroke.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract for turning Apple Pencil touches into stroke samples
 * (docs/movinkpad/process/ipados-pencil-input-contract-2026-11-23.md). Pure logic: the iOS host converts
 * each UITouch (coalesced, predicted, estimated-property update) into a [PencilSample] in content
 * coordinates and feeds it here.
 */
class PencilStrokeAssemblerTest {

    private fun s(
        x: Double, y: Double, timeSec: Double,
        force: Double = 1.0, maxForce: Double = 4.0,
        index: Long? = null, forcePending: Boolean = false,
    ) = PencilSample(x, y, timeSec, force, maxForce, index, forcePending)

    private fun List<Sample>.xs() = map { it.x }

    @Test
    fun forceIsNormalisedByMaximumAndClamped() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.begin(s(0.0, 0.0, 10.0, force = 2.0, maxForce = 4.0))
        a.move(listOf(s(1.0, 0.0, 10.01, force = 5.0), s(2.0, 0.0, 10.02, force = -1.0)), predicted = emptyList())
        val out = a.end(emptyList())
        assertEquals(listOf(0.5, 1.0, 0.0), out.map { it.pressure })
    }

    @Test
    fun timeIsMillisecondsSinceTheFirstSample() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.begin(s(0.0, 0.0, 100.0))
        a.move(listOf(s(1.0, 0.0, 100.004), s(2.0, 0.0, 100.0125)), emptyList())
        val out = a.end(emptyList())
        assertEquals(0.0, out[0].t, 1e-9)
        assertEquals(4.0, out[1].t, 1e-6)
        assertEquals(12.5, out[2].t, 1e-6)
    }

    @Test
    fun withoutPressureEverySampleUsesTheConstant() {
        val a = PencilStrokeAssembler(pressureSupported = false)
        a.begin(s(0.0, 0.0, 0.0, force = 0.2))
        a.move(listOf(s(1.0, 0.0, 0.01, force = 3.9)), emptyList())
        val out = a.end(listOf(s(2.0, 0.0, 0.02, force = 0.0)))
        assertEquals(listOf(1.0, 1.0, 1.0), out.map { it.pressure })
        assertEquals(1.0, PencilStrokeAssembler.CONSTANT_PRESSURE)
    }

    @Test
    fun zeroMaximumForceFallsBackToTheConstant() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.begin(s(0.0, 0.0, 0.0, force = 0.0, maxForce = 0.0))
        assertEquals(listOf(PencilStrokeAssembler.CONSTANT_PRESSURE), a.end(emptyList()).map { it.pressure })
    }

    @Test
    fun predictedSamplesAreDrawnButNeverCommitted() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.begin(s(0.0, 0.0, 0.0))
        a.move(listOf(s(1.0, 0.0, 0.01)), predicted = listOf(s(5.0, 0.0, 0.05), s(6.0, 0.0, 0.06)))
        assertEquals(listOf(0.0, 1.0), a.committed.xs())
        assertEquals(listOf(5.0, 6.0), a.predicted.xs())

        // Each move replaces the previous prediction rather than appending to it.
        a.move(listOf(s(2.0, 0.0, 0.02)), predicted = listOf(s(3.0, 0.0, 0.03)))
        assertEquals(listOf(0.0, 1.0, 2.0), a.committed.xs())
        assertEquals(listOf(3.0), a.predicted.xs())

        a.move(listOf(s(2.5, 0.0, 0.025)), predicted = emptyList())
        assertTrue(a.predicted.isEmpty())

        val out = a.end(listOf(s(4.0, 0.0, 0.04)))
        assertEquals(listOf(0.0, 1.0, 2.0, 2.5, 4.0), out.xs())
        assertTrue(a.predicted.isEmpty())
    }

    @Test
    fun estimatedForceIsPatchedWhenTheUpdateArrives() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.begin(s(0.0, 0.0, 0.0, force = 1.0, index = 7, forcePending = true))
        a.move(listOf(s(1.0, 0.0, 0.01, force = 1.0, index = 8, forcePending = true)), emptyList())
        assertEquals(1, a.updateEstimated(index = 8, force = 3.0, maxForce = 4.0))
        assertEquals(0.75, a.committed[1].pressure, 1e-9)
        assertEquals(0.25, a.committed[0].pressure, 1e-9)

        assertEquals(0, a.updateEstimated(index = 7, force = 2.0, maxForce = 4.0))
        assertEquals(0.5, a.committed[0].pressure, 1e-9)
    }

    @Test
    fun updatesForUnknownOrSettledSamplesAreIgnored() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.begin(s(0.0, 0.0, 0.0, force = 2.0, index = 1, forcePending = false))
        assertEquals(-1, a.updateEstimated(index = 1, force = 4.0, maxForce = 4.0), "sample was not awaiting an update")
        assertEquals(-1, a.updateEstimated(index = 99, force = 4.0, maxForce = 4.0))
        assertEquals(0.5, a.committed[0].pressure, 1e-9)
        // A sample is patched once; a second update for the same index is ignored.
        a.move(listOf(s(1.0, 0.0, 0.01, index = 2, forcePending = true)), emptyList())
        assertEquals(1, a.updateEstimated(2, 4.0, 4.0))
        assertEquals(-1, a.updateEstimated(2, 0.0, 4.0))
        assertEquals(1.0, a.committed[1].pressure, 1e-9)
    }

    @Test
    fun updatesAreIgnoredWithoutPressureSupport() {
        val a = PencilStrokeAssembler(pressureSupported = false)
        a.begin(s(0.0, 0.0, 0.0, index = 3, forcePending = true))
        assertEquals(-1, a.updateEstimated(3, 4.0, 4.0))
        assertEquals(1.0, a.committed[0].pressure)
    }

    @Test
    fun lateUpdatesAfterTheStrokeEndedChangeNothing() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.begin(s(0.0, 0.0, 0.0, force = 2.0, index = 5, forcePending = true))
        val out = a.end(emptyList())
        assertEquals(-1, a.updateEstimated(5, 4.0, 4.0))
        assertEquals(0.5, out[0].pressure, 1e-9)
        assertTrue(a.committed.isEmpty())
    }

    @Test
    fun cancelReturnsCommittedSamplesAndResets() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.begin(s(0.0, 0.0, 0.0))
        a.move(listOf(s(1.0, 0.0, 0.01)), predicted = listOf(s(9.0, 0.0, 0.09)))
        val kept = a.cancel()
        assertEquals(listOf(0.0, 1.0), kept.xs())
        assertTrue(a.committed.isEmpty())
        assertTrue(a.predicted.isEmpty())
        assertTrue(!a.active)
    }

    @Test
    fun beginWhileActiveStartsAFreshStroke() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.begin(s(0.0, 0.0, 0.0))
        assertTrue(a.active)
        a.begin(s(50.0, 0.0, 1.0))
        assertEquals(listOf(50.0), a.committed.xs())
        assertEquals(0.0, a.committed[0].t)
    }

    @Test
    fun movesAndEndsWithoutBeginAreIgnored() {
        val a = PencilStrokeAssembler(pressureSupported = true)
        a.move(listOf(s(1.0, 0.0, 0.0)), listOf(s(2.0, 0.0, 0.0)))
        assertTrue(a.committed.isEmpty())
        assertTrue(a.predicted.isEmpty())
        assertTrue(a.end(listOf(s(3.0, 0.0, 0.0))).isEmpty())
    }
}
