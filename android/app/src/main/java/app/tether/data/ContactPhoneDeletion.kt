package app.tether.data

import java.util.UUID

internal data class ContactPhoneTarget(
    val contact: Long, val raw: Long, val row: Long, val mime: String, val number: String,
    val dataVersion: Long, val rawVersion: Long,
)

internal class ContactPhoneDeleteFailure(val code: String) : Exception(code)

/** One outstanding, single-use confirmation per live connection. */
internal class ContactPhoneTokens<S : Any>(private val now: () -> Long) {
    private data class Prepared(val token: String, val expires: Long, val target: ContactPhoneTarget)
    private val entries = mutableMapOf<S, Prepared>()

    @Synchronized fun issue(session: S, target: ContactPhoneTarget): String {
        val token = UUID.randomUUID().toString()
        entries[session] = Prepared(token, now() + 30_000, target)
        return token
    }

    @Synchronized fun consume(session: S, token: String): ContactPhoneTarget {
        val entry = entries[session]?.takeIf { it.token == token }
            ?: throw ContactPhoneDeleteFailure("invalid_token")
        entries.remove(session)
        if (now() >= entry.expires) throw ContactPhoneDeleteFailure("expired")
        return entry.target
    }

    @Synchronized fun clear(session: S) { entries.remove(session) }
}
