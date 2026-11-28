package app.tether.client

import app.tether.client.ui.recipientOptions
import app.tether.client.ui.smsRecipient
import app.tether.core.Contact
import app.tether.core.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewMessageRecipientTest {
    @Test fun iranianNotationsUseTheSameRecipient() {
        for (input in listOf("09123456789", "+98 912 345 6789", "00989123456789", "۰۹۱۲۳۴۵۶۷۸۹", "+٩٨٩١٢٣٤٥٦٧٨٩")) {
            assertEquals(input, "+989123456789", smsRecipient(input))
        }
    }

    @Test fun invalidRecipientsAreRejectedWithoutGuessingDigits() {
        for (input in listOf("", "Ali 09123456789", "09", "++989123456789", "*123#", "+9809123456789")) {
            assertNull(input, smsRecipient(input))
        }
        assertEquals("+12025550101", smsRecipient("+1 (202) 555-0101"))
    }

    @Test fun suggestionsMatchPersianNamesAndEquivalentNumbers() {
        val contact = Contact(42, "علي", listOf(Phone("+989123456789", "mobile"), Phone("+982112345678", "work")), emptyList(), null, false)
        assertEquals(2, recipientOptions("علی", listOf(contact)).size)
        assertEquals(listOf("+989123456789"), recipientOptions("۰۹۱۲", listOf(contact)).map { it.number })
    }
}
