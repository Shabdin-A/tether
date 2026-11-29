package app.tether.adb

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.tether.R
import app.tether.TetherApp
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADB client to the phone's own adbd over Wireless debugging (TLS, loopback/Wi-Fi), via libadb-android.
 * Our RSA key + self-signed certificate are what adbd remembers after the one-time pairing.
 */
class Adb private constructor(private val ctx: Context) : AbsAdbConnectionManager() {
    private val keyFile = File(ctx.filesDir, "adb_key.pk8")
    private val certFile = File(ctx.filesDir, "adb_cert.der")
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(10, TimeUnit.SECONDS)
        if (!keyFile.exists() || !certFile.exists()) generate()
        key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
    }

    private fun generate() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Tether")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000),
            Date(now + 20L * 365 * 86_400_000), name, kp.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val c = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(builder.build(signer))
        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(c.encoded)
    }

    // libadb destroys the KeyPair of a closed transport. Give each connection its own copy.
    override fun getPrivateKey(): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(key.encoded))
    override fun getCertificate() = cert
    override fun getDeviceName() = "tether"

    /** Pairs with the 6-digit code shown in Settings › Wireless debugging › Pair device with pairing code. */
    fun pairWithCode(code: String): Boolean {
        val (host, port) = findPairingService()
            ?: throw IllegalStateException("pairing service not found; keep the pairing dialog open")
        if (!pairAt(host, port, code)) throw IllegalStateException("wrong or expired pairing code")
        return true
    }

    /** The pairing service Settings announces while "Pair device with pairing code" is open (mDNS, up to 15 s). */
    fun findPairingService(): Pair<String, Int>? = discover(AdbMdns.SERVICE_TYPE_TLS_PAIRING, 15)

    /** False when adbd refused the code (wrong, or the dialog was closed and it expired). */
    fun pairAt(host: String, port: Int, code: String): Boolean {
        Log.i(TAG, "pairing with the pairing service on port $port")
        return pair(host, port, code.trim())
    }

    @Synchronized fun ensureConnected(): Boolean = connectForUse().first

    @Synchronized internal fun connectForUse(retryDiscovery: Boolean = false): Pair<Boolean, String> {
        if (isConnected) return true to "ADB session already connected"
        val details = mutableListOf<String>()
        val prefs = (ctx as? TetherApp)?.prefs
        val legacy = prefs?.getBoolean(PREF_LEGACY, false) ?: false
        val port = prefs?.getInt(PREF_LEGACY_PORT, ConnectOrder.DEFAULT_LEGACY_PORT) ?: ConnectOrder.DEFAULT_LEGACY_PORT
        val how = connectInOrder(object : AdbEndpoints {
            override fun connectTls(): Boolean {
                val service = findConnectService(retryDiscovery) { attempt ->
                    details += "TLS connect discovery attempt $attempt"
                    val endpoint = discover(AdbMdns.SERVICE_TYPE_TLS_CONNECT, 10)
                    if (endpoint == null) details += "TLS connect service discovery timed out (10 s)"
                    endpoint
                } ?: return false
                details += "TLS connect service discovered; opening TCP and authenticating"
                val old = isThrowOnUnauthorised
                setThrowOnUnauthorised(true)
                try {
                    val connected = connect(service.first, service.second)
                    details += if (connected) "TLS ADB session established" else "ADB handshake timed out"
                    if (!connected) disconnectFailedAttempt()
                    return connected
                } catch (e: Exception) {
                    val cause = e.cause
                    details += when {
                        e is AdbAuthenticationFailedException -> "ADB authentication rejected the stored identity"
                        e is AdbPairingRequiredException -> "ADB library reported pairing required during the handshake"
                        e is SocketTimeoutException || cause is SocketTimeoutException -> "TCP connection timed out"
                        e is ConnectException || cause is ConnectException -> "TCP connection failed: ${AdbPairing.shortReason(cause ?: e)}"
                        else -> "TCP/ADB handshake failed: ${e.javaClass.simpleName}: ${AdbPairing.shortReason(e)}"
                    }
                    disconnectFailedAttempt()
                    return false
                } finally {
                    setThrowOnUnauthorised(old)
                }
            }
            override fun connectLegacy(port: Int) = connect("127.0.0.1", port)
        }, ConnectOrder.tlsSupported(Build.VERSION.SDK_INT), legacy, port) { details += it }
        if (how == "legacy") details += "ADB connected over opted-in TCP mode"
        if (details.isEmpty()) details += "No ADB connection available"
        val report = details.joinToString("\n")
        Log.i(TAG, report)
        return (how != null) to report
    }

    private fun disconnectFailedAttempt() {
        runCatching { disconnect() }.onFailure { Log.w(TAG, "could not close failed ADB transport", it) }
    }

    /** Runs a shell command and returns its output (stdout + stderr). */
    @Synchronized fun shell(cmd: String): String = readShell(cmd, firstLine = false)

    /** The probe only needs its reply line; it must not wait for a separate stream-close packet. */
    @Synchronized internal fun shellProbe(): String = readShell("echo tether-adb-ready", firstLine = true)

    private fun readShell(cmd: String, firstLine: Boolean): String {
        if (!ensureConnected()) throw IllegalStateException("adb not connected")
        // "shell,raw:" runs without a PTY, so a backgrounded helper is not hung up when the stream closes.
        openStream("shell,raw:$cmd").use { s ->
            val input = s.openInputStream()
            val out = StringBuilder()
            val buf = ByteArray(4096)
            while (true) {
                val n = try { input.read(buf) } catch (e: Exception) { if (firstLine) throw e else -1 }
                if (n < 0) break
                out.append(String(buf, 0, n))
                if (firstLine) {
                    val newline = out.indexOf("\n")
                    if (newline >= 0) return out.substring(0, newline)
                }
            }
            return out.toString()
        }
    }

    private fun discover(type: String, timeoutSec: Long): Pair<String, Int>? {
        var found: Pair<String, Int>? = null
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(ctx, type) { addr: InetAddress?, port: Int ->
            if (addr != null && port > 0) { found = (addr.hostAddress ?: "127.0.0.1") to port; latch.countDown() }
        }
        try {
            mdns.start()
            return if (latch.await(timeoutSec, TimeUnit.SECONDS)) found else null
        } finally {
            mdns.stop()
        }
    }

    companion object {
        const val TAG = "Tether"
        /** Opt-in legacy TCP adb (`adb tcpip`): the setting and its port (default 5555). */
        const val PREF_LEGACY = "adb_legacy"
        const val PREF_LEGACY_PORT = "adb_legacy_port"
        @Volatile private var instance: Adb? = null
        fun get(ctx: Context): Adb = instance ?: synchronized(this) {
            instance ?: Adb(ctx.applicationContext).also { instance = it }
        }

        /** Drops the current connection (it may be hung on a restarted adbd); the next get() starts fresh. */
        fun reset() {
            val old = synchronized(this) { instance.also { instance = null } } ?: return
            Thread({ runCatching { old.close() } }, "adb-close").apply { isDaemon = true }.start()
        }

        fun hasSecureSettings(ctx: Context) =
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        /** Turns Wireless debugging on (needs WRITE_SECURE_SETTINGS and Wi-Fi). */
        fun enableWirelessDebugging(ctx: Context): Boolean = try {
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
        } catch (e: SecurityException) {
            false
        }

        data class BootstrapResult(val output: String, val missingPermissions: List<String>)

        /** After pairing: request the grants, then verify them through Android rather than shell output. */
        /** USB debugging is on: then adbd, and the helper it started, keep running when Wi-Fi goes (HelperLife). */
        fun usbDebugging(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

        /**
         * Turns USB debugging on so the helper outlives Wi-Fi (issues #26, #29). Only on the user's tap: it is a
         * developer setting some banking apps refuse to run with. Needs WRITE_SECURE_SETTINGS.
         */
        fun enableUsbDebugging(ctx: Context): Boolean = try {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 1)
        } catch (e: SecurityException) {
            false
        }

        /** The phone is on a Wi-Fi network (Wireless debugging runs only then); also behind a VPN or mobile-data default. */
        @Suppress("DEPRECATION")
        fun onWifi(ctx: Context): Boolean {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
            return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
        }

        fun bootstrap(ctx: Context): BootstrapResult {
            val adb = get(ctx)
            val pkg = ctx.packageName
            val sb = StringBuilder()
            val permissions = listOf("WRITE_SECURE_SETTINGS", "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG",
                "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS") +
                (if (Build.VERSION.SDK_INT >= 31) listOf("BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE") else emptyList()) +
                (if (Build.VERSION.SDK_INT >= 33) listOf("POST_NOTIFICATIONS") else emptyList())
            for (p in permissions) {
                sb.append(adb.shell("pm grant $pkg android.permission.$p 2>&1"))
            }
            sb.append(adb.shell("cmd deviceidle whitelist +$pkg 2>&1"))
            sb.append(allowSmsWrite(ctx))
            val missing = permissions.filter {
                ctx.checkSelfPermission("android.permission.$it") != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            return BootstrapResult(sb.toString(), missing)
        }

        /**
         * Lets Tether mark messages as read although it is not the default SMS app: the WRITE_SMS app-op,
         * which only the shell may set. Cheap and idempotent, so it is repeated whenever the helper starts.
         */
        fun allowSmsWrite(ctx: Context): String = try {
            get(ctx).shell("appops set ${ctx.packageName} WRITE_SMS allow 2>&1")
        } catch (t: Throwable) {
            "appops: ${t.message}"
        }

        // ------------------------------------------------------------------ notification flow

        const val NOTIF_ADB = 30
        const val KEY_CODE = "code"

        fun showPairingNotification(ctx: Context, text: String = ctx.getString(R.string.adb_notif_text)) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel(ctx.getString(R.string.adb_notif_hint)).build()
            val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AdbPairingReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val action = Notification.Action.Builder(null, ctx.getString(R.string.adb_notif_action), pi)
                .addRemoteInput(input).build()
            val n = Notification.Builder(ctx, TetherApp.CH_SETUP)
                .setSmallIcon(R.drawable.ic_tether)
                .setContentTitle(ctx.getString(R.string.adb_notif_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .addAction(action)
                .setOngoing(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ADB, n)
        }

        fun updateNotification(ctx: Context, text: String, done: Boolean) {
            if (!done) { showPairingNotification(ctx, text); return }
            val n = Notification.Builder(ctx, TetherApp.CH_SETUP).setSmallIcon(R.drawable.ic_tether)
                .setContentTitle(ctx.getString(R.string.adb_notif_title)).setContentText(text).setAutoCancel(true).build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ADB, n)
        }
    }
}

/** The notification's code field. Only checks the code and hands the work to [AdbPairing] (never blocks the broadcast). */
class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Adb.KEY_CODE)?.toString()
        val code = AdbPairing.normalizeCode(typed)
        // Always answer the reply: a notification that is not posted again keeps its spinner forever.
        if (code == null) { AdbPairing.show(context, AdbPairing.Result.BAD_CODE, null); return }
        AdbPairing.start(context, code)
    }
}
