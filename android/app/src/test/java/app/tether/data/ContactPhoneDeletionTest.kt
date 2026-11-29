package app.tether.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactPhoneDeletionTest {
    private var now = 0L
    private val tokens = ContactPhoneTokens<String> { now }
    private val target = ContactPhoneTarget(1, 2, 3, "phone", "+12025550101", 4, 5)

    private fun failure(session: String, token: String): String? =
        (runCatching { tokens.consume(session, token) }.exceptionOrNull() as? ContactPhoneDeleteFailure)?.code

    @Test fun tokenCannotBeConsumedByAnotherSessionOrReused() {
        val token = tokens.issue("first", target)
        assertEquals("invalid_token", failure("other", token))
        assertEquals(target, tokens.consume("first", token))
        assertEquals("invalid_token", failure("first", token))
    }

    @Test fun expiryDisconnectAndReplacementInvalidateTokens() {
        val expired = tokens.issue("first", target)
        now = 30_000
        assertEquals("expired", failure("first", expired))
        val old = tokens.issue("first", target)
        val replacement = tokens.issue("first", target)
        assertEquals("invalid_token", failure("first", old))
        tokens.clear("first")
        assertEquals("invalid_token", failure("first", replacement))
    }
}
