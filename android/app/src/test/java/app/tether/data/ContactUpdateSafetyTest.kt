package app.tether.data

import app.tether.core.Contact
import app.tether.core.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactUpdateSafetyTest {
    private val original = Contact(1, "Synthetic", listOf(Phone("+12025550101", "mobile")),
        listOf("old@example.invalid"), null, false)

    @Test fun hostAdditionsRejectTheOldSnapshotEvenIfTheVersionDidNotChange() {
        assertFalse(sameContactEditSnapshot(original, original.copy(phones = original.phones +
            Phone("+12025550102", "home")), "v1", "v1"))
        assertFalse(sameContactEditSnapshot(original, original.copy(emails = original.emails +
            "new@example.invalid"), "v1", "v1"))
        assertFalse(sameContactEditSnapshot(original, original, "v1", "v2"))
        assertTrue(sameContactEditSnapshot(original, original, "v1", "v1"))
    }

    @Test fun exactNumberAcrossAccountsMustResolveToOneWritableRow() {
        data class Row(val raw: Int, val number: String, val writable: Boolean)
        val exact = Row(1, "+12025550101", true)
        val similar = Row(2, "+442025550101", true)
        val duplicate = exact.copy(raw = 2, writable = false)
        fun select(rows: List<Row>) = uniqueWritableContactRow(rows, { it.number == exact.number }, { it.writable })
        assertEquals(exact, select(listOf(similar, exact)))
        assertNull(select(listOf(duplicate, exact)))
        assertNull(select(listOf(duplicate, similar)))
        assertNull(select(listOf(similar)))
    }
}
