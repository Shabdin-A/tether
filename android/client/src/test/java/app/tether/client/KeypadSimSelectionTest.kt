package app.tether.client

import app.tether.client.ui.KeypadSimSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class KeypadSimSelectionTest {
    @Test fun onlyAvailableDefaultCanBeUsed() {
        val selection = KeypadSimSelection()
        assertNull(selection.resolve(emptyList(), 10))
        assertNull(selection.resolve(listOf(10), 20))
        assertEquals(10, selection.resolve(listOf(10), 10))
        assertNull(selection.resolve(listOf(10, 20), 30))
        assertNull(selection.resolve(listOf(10, 20), null))
        assertEquals(20, selection.resolve(listOf(10, 20), 20))
    }

    @Test fun explicitSelectionSurvivesRefreshButNotRemovalOrDisconnect() {
        val selection = KeypadSimSelection()
        selection.select(20)
        assertEquals(20, selection.resolve(listOf(10, 20), 10))
        assertEquals(20, selection.resolve(listOf(20, 10), 10))
        assertNull(selection.resolve(listOf(10, 30), null))
        assertFalse(selection.hasTemporarySelection)
        assertNull(selection.resolve(listOf(10, 20), null))

        selection.select(20)
        selection.clear()
        assertEquals(10, selection.resolve(listOf(10, 20), 10))
    }
}
