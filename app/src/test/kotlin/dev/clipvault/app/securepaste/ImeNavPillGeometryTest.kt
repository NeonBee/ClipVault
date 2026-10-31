package dev.clipvault.app.securepaste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure geometry checks: no local Android system glyphs or input events are mocked. */
class ImeNavPillGeometryTest {
    @Test fun leftAndRightPillsMirrorAroundCenterAndStayInNavigationStrip() {
        val positions = ImeNavPillGeometry.forNavigationBar(
            widthPx = 720f, heightPx = 260f, navHeightPx = 90f,
            pillWidthPx = 90f, pillHeightPx = 50f,
        )
        assertEquals(2, positions.size)
        val left = positions[0]
        val right = positions[1]
        assertEquals(720f - left.right, right.left, 0.001f)
        assertEquals(720f - left.left, right.right, 0.001f)
        assertEquals(left.top, right.top, 0.001f)
        assertTrue(left.top >= 170f)
        assertTrue(left.bottom <= 260f)
        assertTrue(left.right < right.left)
    }

    @Test fun geometryRefusesNarrowDisplaysInsteadOfCoveringCenterGestureHandle() {
        val positions = ImeNavPillGeometry.forNavigationBar(
            widthPx = 180f, heightPx = 200f, navHeightPx = 80f,
            pillWidthPx = 70f, pillHeightPx = 48f,
        )
        assertTrue(positions.isEmpty())
    }

    @Test fun geometryRefusesPillsThatWouldExtendOutsideNavigationArea() {
        assertTrue(ImeNavPillGeometry.forNavigationBar(
            widthPx = 400f, heightPx = 200f, navHeightPx = 25f,
            pillWidthPx = 50f, pillHeightPx = 60f,
        ).isEmpty())
    }
}
