package app.tether.adb

import app.tether.R
import app.tether.adb.AdbPairing.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pairing from the notification (issue #1): every code and every failure ends in a clear message, never a crash. */
class AdbPairingTest {
    @Test fun codeIsSixDigitsInAnyScript() {
        assertEquals("123456", AdbPairing.normalizeCode("123456"))
        assertEquals("123456", AdbPairing.normalizeCode(" 123 456 "))
        assertEquals("123456", AdbPairing.normalizeCode("123-456"))
        assertEquals("123456", AdbPairing.normalizeCode("۱۲۳۴۵۶"))       // Persian keyboard
        assertEquals("123456", AdbPairing.normalizeCode("١٢٣٤٥٦"))       // Arabic-Indic digits
    }

    @Test fun anythingElseIsNoCode() {
        for (s in listOf(null, "", "12345", "1234567", "abcdef", "12 34 5")) assertNull(s, AdbPairing.normalizeCode(s))
    }

    @Test fun reasonIsOneShortLine() {
        val long = IllegalStateException("x".repeat(5000) + "\n\tat somewhere\n\tat elsewhere")
        val r = AdbPairing.shortReason(long)
        assertTrue(r.length <= 160)
        assertFalse(r.contains('\n'))
        assertEquals("IllegalStateException", AdbPairing.shortReason(IllegalStateException()))
        assertEquals("closed by peer", AdbPairing.shortReason(java.io.IOException("  closed \n by   peer ")))
    }

    @Test fun eachResultHasItsMessage() {
        assertEquals(R.string.adb_paired to null, AdbPairing.message(Result.OK))
        assertEquals(R.string.adb_code_format to null, AdbPairing.message(Result.BAD_CODE))
        assertEquals(R.string.adb_no_wifi to null, AdbPairing.message(Result.NO_WIFI))
        assertEquals(R.string.adb_no_service to null, AdbPairing.message(Result.NO_SERVICE))
        assertEquals(R.string.adb_wrong_code to null, AdbPairing.message(Result.WRONG_CODE))
        assertEquals(R.string.adb_failed to "boom", AdbPairing.message(Result.FAILED, "boom"))
        assertEquals(R.string.adb_paired_no_connect to "refused", AdbPairing.message(Result.PAIRED_NO_CONNECT, "refused"))
        assertEquals(R.string.adb_paired_permissions to "READ_SMS", AdbPairing.message(Result.PAIRED_PERMISSIONS, "READ_SMS"))
        assertEquals(R.string.adb_paired_helper_not_ready to "starting", AdbPairing.message(Result.PAIRED_HELPER_NOT_READY, "starting"))
    }

    @Test fun failuresKeepTheCodeFieldOpen() {
        for (r in listOf(Result.BAD_CODE, Result.NO_WIFI, Result.NO_SERVICE, Result.WRONG_CODE, Result.FAILED)) assertTrue(r.name, AdbPairing.asksAgain(r))
        // Paired: nothing more to type (the connection is retried by the app itself).
        assertFalse(AdbPairing.asksAgain(Result.OK))
        assertFalse(AdbPairing.asksAgain(Result.PAIRED_NO_CONNECT))
        assertFalse(AdbPairing.asksAgain(Result.PAIRED_PERMISSIONS))
        assertFalse(AdbPairing.asksAgain(Result.PAIRED_HELPER_NOT_READY))
    }

    @Test fun acceptedCodeWithNoConnectionDoesNotAttemptBootstrapOrHelper() {
        assertEquals(Result.PAIRED_NO_CONNECT to null, AdbPairing.verifyPostPairing(
            connect = { false },
            bootstrap = { error("bootstrap must not run") },
            startHelper = { error("helper must not start") },
            helperRunning = { false },
        ))
        assertEquals(Result.PAIRED_NO_CONNECT to "closed by peer", verify(connect = { throw java.io.IOException("closed by peer") }))
    }

    @Test fun actualPermissionDenialIsSeparateFromConnectionAndShellOutput() {
        assertEquals(Result.PAIRED_PERMISSIONS to "READ_SMS, WRITE_SECURE_SETTINGS",
            verify(bootstrap = { Adb.Companion.BootstrapResult("", listOf("READ_SMS", "WRITE_SECURE_SETTINGS")) }))
        assertEquals(Result.PAIRED_PERMISSIONS to "stream closed",
            verify(bootstrap = { throw java.io.IOException("stream closed") }))
        assertEquals(Result.OK to null,
            verify(bootstrap = { Adb.Companion.BootstrapResult("Added to whitelist", emptyList()) }))
    }

    @Test fun helperErrorOrUnconfirmedRunningStateDoesNotUndoPairing() {
        assertEquals(Result.PAIRED_HELPER_NOT_READY to "starting", verify(startHelper = { "starting" }, running = false))
        assertEquals(Result.PAIRED_HELPER_NOT_READY to "launch timed out", verify(startHelper = { "launch timed out" }))
        assertEquals(Result.PAIRED_HELPER_NOT_READY to "socket closed",
            verify(startHelper = { throw java.io.IOException("socket closed") }))
        assertEquals(Result.PAIRED_HELPER_NOT_READY to null, verify(running = false))
        assertEquals(Result.OK to null, verify())
    }

    private fun verify(
        connect: () -> Boolean = { true },
        bootstrap: () -> Adb.Companion.BootstrapResult = { Adb.Companion.BootstrapResult("", emptyList()) },
        startHelper: () -> String? = { null },
        running: Boolean = true,
    ) = AdbPairing.verifyPostPairing(connect, bootstrap, startHelper) { running }

    @Test fun delayedConnectAdvertisementIsRetriedOnlyDuringSetup() {
        val attempts = mutableListOf<Int>()
        fun discover(attempt: Int): Int? {
            attempts += attempt
            return 5556.takeIf { attempt == 2 }
        }
        assertNull(findConnectService(false, ::discover))
        assertEquals(listOf(1), attempts)
        attempts.clear()
        assertEquals(5556, findConnectService(true, ::discover))
        assertEquals(listOf(1, 2), attempts)
        attempts.clear()
        assertNull(findConnectService<Int>(true) { attempts += it; null })
        assertEquals(listOf(1, 2), attempts)
    }

    @Test fun shellFailureIsDistinguishedFromMissingOrUnexpectedOutput() {
        assertEquals(Result.OK to null, verify(connect = {
            AdbPairing.verifyShell { "tether-adb-ready\r\n" }
            true
        }))
        fun checkFailure(read: () -> String, expected: String) {
            assertEquals(Result.PAIRED_NO_CONNECT to expected, verify(connect = {
            AdbPairing.verifyShell(read = read)
                true
            }, bootstrap = { error("bootstrap must not run") }))
        }
        checkFailure({ throw java.io.IOException("stream closed") }, "ADB shell failed: stream closed")
        checkFailure({ "" }, "ADB shell returned no verification output")
        checkFailure({ "incorrect" }, "ADB shell returned unexpected verification output")
        assertEquals(Result.PAIRED_NO_CONNECT to "ADB shell verification timed out", verify(connect = {
            AdbPairing.verifyShell(timeoutMs = 0) { java.util.concurrent.CountDownLatch(1).await(); "" }
            true
        }))
    }
}
