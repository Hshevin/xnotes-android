package com.xnotes.pen.pencil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W3-D contract (docs/movinkpad/process/ipados-wave3-contracts-2026-11-24.md, "W3-D"): the queue between Apple
 * Pencil touches and the shared stroke capture. A sample whose force is still an estimate waits a short while
 * for its update, so the saved stroke carries the corrected pressure; the wet ink never waits.
 */
class PencilFeedTest {

    private fun s(x: Double, ms: Double, force: Double = 2.0, index: Long? = null, pending: Boolean = false) =
        PencilSample(x, 0.0, ms / 1000.0, force, 4.0, index, pending)

    private fun List<PencilFeed.Released>.xs() = map { it.x }

    @Test
    fun theFirstSampleIsReleasedAtOnceSoThePageIsChosenAtPenDown() {
        val f = PencilFeed(pressureSupported = true, settleMs = 40)
        val first = f.begin(s(1.0, 1000.0, index = 1, pending = true))
        assertEquals(1.0, first.x)
        assertEquals(0.5, first.pressure)
        assertEquals(1000L, first.timeMs)
    }

    @Test
    fun settledSamplesPassStraightThrough() {
        val f = PencilFeed(true, 40)
        f.begin(s(0.0, 0.0))
        assertEquals(listOf(1.0, 2.0), f.move(listOf(s(1.0, 8.0), s(2.0, 16.0)), emptyList()).xs())
    }

    @Test
    fun aPendingSampleWaitsForItsUpdateAndKeepsOrder() {
        val f = PencilFeed(true, 40)
        f.begin(s(0.0, 0.0))
        assertTrue(f.move(listOf(s(1.0, 8.0, force = 1.0, index = 7, pending = true), s(2.0, 16.0)), emptyList()).isEmpty(),
            "a settled sample behind a pending one waits too")
        val out = f.update(index = 7, force = 3.0, maxForce = 4.0)
        assertEquals(listOf(1.0, 2.0), out.xs())
        assertEquals(0.75, out[0].pressure)
    }

    @Test
    fun aPendingSampleIsReleasedWithItsEstimateOnceItIsTooOld() {
        val f = PencilFeed(true, 40)
        f.begin(s(0.0, 0.0))
        assertTrue(f.move(listOf(s(1.0, 8.0, force = 1.0, index = 7, pending = true)), emptyList()).isEmpty())
        assertTrue(f.move(listOf(s(2.0, 40.0)), emptyList()).isEmpty(), "32 ms old: still waiting")
        val out = f.move(listOf(s(3.0, 48.0)), emptyList())
        assertEquals(listOf(1.0, 2.0, 3.0), out.xs(), "40 ms behind the newest: released with its estimate")
        assertEquals(0.25, out[0].pressure)
        assertTrue(f.update(7, 4.0, 4.0).isEmpty(), "a late update for a released sample changes nothing")
    }

    @Test
    fun penUpReleasesEverythingStillQueued() {
        val f = PencilFeed(true, 40)
        f.begin(s(0.0, 0.0))
        f.move(listOf(s(1.0, 8.0, index = 9, pending = true)), emptyList())
        val out = f.end(listOf(s(2.0, 16.0)))
        assertEquals(listOf(1.0, 2.0), out.xs())
        assertTrue(f.end(emptyList()).isEmpty())
    }

    @Test
    fun predictionsAreForTheWetInkOnlyAndShowEvenWhileSamplesWait() {
        val f = PencilFeed(true, 40)
        f.begin(s(0.0, 0.0))
        f.move(listOf(s(1.0, 8.0, index = 3, pending = true)), predicted = listOf(s(5.0, 30.0)))
        assertEquals(listOf(1.0, 5.0), f.wetTail().xs(), "queued samples and the prediction both show as wet ink")
        f.update(3, 2.0, 4.0)
        assertEquals(listOf(5.0), f.wetTail().xs())
        f.end(emptyList())
        assertTrue(f.wetTail().isEmpty())
    }

    @Test
    fun withoutPressureNothingWaitsAndPressureIsConstant() {
        val f = PencilFeed(pressureSupported = false, settleMs = 40)
        f.begin(s(0.0, 0.0, force = 0.1, index = 1, pending = true))
        val out = f.move(listOf(s(1.0, 8.0, force = 3.9, index = 2, pending = true)), emptyList())
        assertEquals(listOf(1.0), out.xs())
        assertEquals(PencilStrokeAssembler.CONSTANT_PRESSURE, out[0].pressure)
    }

    @Test
    fun cancelDropsTheQueue() {
        val f = PencilFeed(true, 40)
        f.begin(s(0.0, 0.0))
        f.move(listOf(s(1.0, 8.0, index = 4, pending = true)), listOf(s(3.0, 20.0)))
        f.cancel()
        assertTrue(f.wetTail().isEmpty())
        assertTrue(f.update(4, 4.0, 4.0).isEmpty())
        assertTrue(f.move(listOf(s(2.0, 16.0)), emptyList()).isEmpty(), "no stroke is active after cancel")
    }
}
