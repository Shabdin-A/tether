package app.tether.adb

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAdbPortProbeTest {
    @Test fun validatesNumericConnectPort() {
        assertEquals(33265, LocalAdbPortProbe.parsePort(" 33265 "))
        assertEquals(33265, LocalAdbPortProbe.parsePort("۳۳۲۶۵"))
        assertEquals(1, LocalAdbPortProbe.parsePort("1"))
        assertEquals(65535, LocalAdbPortProbe.parsePort("65535"))
        listOf("", "0", "-1", "65536", "192.168.100.6:33265", "999999999999").forEach {
            assertNull(it, LocalAdbPortProbe.parsePort(it))
        }
    }

    @Test fun attemptsOnlyLoopbackAndWifiWithBoundedTimeouts() {
        val wifi = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7))
        val attempts = mutableListOf<String>()
        val result = LocalAdbPortProbe.run(33265, wifi) { endpoint, timeout ->
            attempts += "${endpoint.address.hostAddress}:${endpoint.port} timeout=$timeout"
            if (endpoint.address == wifi) throw ConnectException("ECONNREFUSED")
        }
        assertEquals(listOf("127.0.0.1:33265 timeout=1500", "10.0.0.7:33265 timeout=1500"), attempts)
        assertEquals(listOf(LocalAdbPortProbe.Status.OPEN, LocalAdbPortProbe.Status.REFUSED), result.map { it.status })
    }

    @Test fun distinguishesTimeoutAndOtherErrorsAndSkipsMissingWifi() {
        val result = LocalAdbPortProbe.run(33265, null) { _, _ -> throw SocketTimeoutException() }
        assertEquals(listOf(LocalAdbPortProbe.Status.TIMEOUT, LocalAdbPortProbe.Status.NO_ADDRESS), result.map { it.status })
        val error = LocalAdbPortProbe.run(33265, null) { _, _ -> throw IOException("network unreachable") }.first()
        assertEquals(LocalAdbPortProbe.Status.ERROR, error.status)
        assertEquals("network unreachable", error.detail)
    }
}
