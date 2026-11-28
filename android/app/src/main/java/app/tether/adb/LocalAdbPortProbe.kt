package app.tether.adb

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** A TCP reachability check only. No ADB bytes are sent. */
internal object LocalAdbPortProbe {
    private const val TIMEOUT_MS = 1_500
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    enum class Status { OPEN, REFUSED, TIMEOUT, ERROR, NO_ADDRESS }
    data class Result(val address: String?, val status: Status, val detail: String? = null)

    fun parsePort(text: String): Int? {
        val input = text.trim()
        if (input.length !in 1..5) return null
        var port = 0
        for (digit in input) port = port * 10 + (digit.digitToIntOrNull() ?: return null)
        return port.takeIf { it in 1..65535 }
    }

    fun run(port: Int, wifiAddress: InetAddress?, connect: (InetSocketAddress, Int) -> Unit = { address, timeout ->
        Socket().use { it.connect(address, timeout) }
    }): List<Result> = listOf(check(loopback, port, connect),
        wifiAddress?.let { check(it, port, connect) } ?: Result(null, Status.NO_ADDRESS))

    private fun check(address: InetAddress, port: Int, connect: (InetSocketAddress, Int) -> Unit): Result {
        val host = address.hostAddress
        return try {
            connect(InetSocketAddress(address, port), TIMEOUT_MS)
            Result(host, Status.OPEN)
        } catch (_: SocketTimeoutException) {
            Result(host, Status.TIMEOUT)
        } catch (e: IOException) {
            val refused = e is ConnectException &&
                (e.message?.contains("refused", ignoreCase = true) == true ||
                    e.message?.contains("ECONNREFUSED", ignoreCase = true) == true)
            Result(host, if (refused) Status.REFUSED else Status.ERROR, e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            Result(host, Status.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }
}
