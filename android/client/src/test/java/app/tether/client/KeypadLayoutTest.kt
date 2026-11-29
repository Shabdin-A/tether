package app.tether.client

import app.tether.client.ui.keypadMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadLayoutTest {
    @Test fun compactPortraitFitsDialerAndLeavesTouchTargets() {
        val metrics = keypadMetrics(288, 266)
        assertTrue(metrics.keyWidth >= 48)
        assertTrue(metrics.keyHeight >= 48)
        assertTrue(metrics.callSize >= 48)
        assertTrue(4 * metrics.keyHeight + 3 * metrics.rowGap + metrics.callSize <= 266)
    }

    @Test fun tabletHeightAndWidthDoNotInflateTheGrid() {
        assertEquals(keypadMetrics(360, 690), keypadMetrics(800, 1100))
    }

    @Test fun shortNarrowWindowKeepsTouchHeightForScrolling() {
        val metrics = keypadMetrics(240, 160, compact = true)
        assertEquals(48, metrics.keyHeight)
        assertEquals(48, metrics.callSize)
        assertTrue(metrics.keyWidth >= 48)
    }
}
