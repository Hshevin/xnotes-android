package com.xnotes.map.source

import com.xnotes.map.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceTripTest {

    private val canvas = "0b9f3c2e-58a4-4a7e-9d43-0c1e2f3a4b5c"
    private fun ret(node: String = "n1", location: String = "content://c") =
        MapReturn(canvas, location, NodeId(node), 10.0, 20.0, 1.5)

    @Test fun nothingToReturnToAtFirst() {
        val trip = SourceTrip()
        assertNull(trip.pending)
        assertNull(trip.back { canvas })
    }

    @Test fun returningToTheSameCanvasGoesBackAndUsesUpTheBookmark() {
        val trip = SourceTrip()
        trip.leave(ret())
        assertEquals(ret(), trip.pending)
        var asked: String? = null
        assertEquals(ReturnCheck.Go(ret()), trip.back { asked = it; canvas })
        assertEquals("content://c", asked)
        assertNull(trip.pending)
        assertNull(trip.back { canvas })
    }

    @Test fun aLaterLeaveReplacesTheBookmark() {
        val trip = SourceTrip()
        trip.leave(ret("n1"))
        trip.leave(ret("n2"))
        assertEquals(ReturnCheck.Go(ret("n2")), trip.back { canvas })
    }

    @Test fun aCanvasThatIsGoneOrReplacedOrUnreadableIsReported() {
        for (read in listOf<(String) -> String?>({ null }, { "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f" }, { throw java.io.IOException() })) {
            val trip = SourceTrip()
            trip.leave(ret())
            assertEquals(ReturnCheck.CanvasGone, trip.back(read))
            assertNull("used up even so", trip.pending)
        }
    }

    @Test fun openingSomethingElseForgetsTheWayBack() {
        val trip = SourceTrip()
        trip.leave(ret())
        trip.forget()
        assertNull(trip.pending)
        assertNull(trip.back { canvas })
    }
}
