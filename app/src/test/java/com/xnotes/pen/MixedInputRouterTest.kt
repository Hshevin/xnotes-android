package com.xnotes.pen

import com.xnotes.ui.Pane
import org.junit.Assert.*
import org.junit.Test

class MixedInputRouterTest {
    private val map = MixedInputRouter.Target(Pane.PRIMARY, MixedInputRouter.Kind.MAP)
    private val native = MixedInputRouter.Target(Pane.SECONDARY, MixedInputRouter.Kind.NATIVE)
    private fun router() = MixedInputRouter().apply {
        place(map, MixedInputRouter.Bounds(20f, 70f, 320f, 670f))
        place(native, MixedInputRouter.Bounds(320f, 70f, 920f, 670f))
    }

    @Test fun windowBoundsExcludeWorkspaceChromeAndUseActualUnequalSeam() {
        val router = router()
        assertNull(router.hit(30f, 40f))
        assertEquals(map, router.hit(319f, 80f))
        assertEquals(native, router.hit(320f, 80f))
        assertNull(router.hit(920f, 80f))
    }

    @Test fun downOwnsMovedAndAdditionalPointerInputUntilCancelOrUp() {
        val router = router()
        assertEquals(map, router.begin(90f, 100f))
        assertEquals(map, router.begin(600f, 100f))
        assertEquals(map, router.hover(600f, 100f))
        assertEquals(map, router.touchOwner)
        router.finish()
        assertEquals(native, router.hover(600f, 100f))
        assertEquals(native, router.begin(600f, 100f))
    }

    @Test fun gestureStartingOnWorkspaceChromeCannotBecomeNativeMidStream() {
        val router = router()
        assertNull(router.begin(10f, 10f))
        assertTrue(router.hasContact)
        assertNull(router.begin(600f, 100f))
        assertNull(router.hover(600f, 100f))
        router.finish()
        assertEquals(native, router.begin(600f, 100f))
    }

    @Test fun exchangingAndResizingPanesAffectsNextDownOnly() {
        val router = router()
        router.begin(50f, 100f)
        router.place(map, MixedInputRouter.Bounds(400f, 70f, 920f, 670f))
        router.place(native, MixedInputRouter.Bounds(20f, 70f, 400f, 670f))
        assertEquals(map, router.touchOwner)
        router.finish()
        assertEquals(native, router.begin(50f, 100f))
        router.clear()
        assertNull(router.touchOwner)
        assertNull(router.hit(50f, 100f))
        assertFalse(router.hasContact)
    }
}
