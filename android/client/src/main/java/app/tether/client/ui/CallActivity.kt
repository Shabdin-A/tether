package app.tether.client.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.CallAudioState
import app.tether.client.calls.nextAudioRoute
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import app.tether.client.R
import app.tether.client.calls.LocalCall
import app.tether.client.calls.MicPolicy

/**
 * The call screen (self-managed calls show the app's own): who, SIM, state and time; answer / decline while ringing;
 * mute, audio route (earpiece, speaker, Bluetooth, wired), hold, keypad and end. The screen goes off at the ear.
 */
class CallActivity : Activity() {
    private val style by lazy { LinkViews(this) }
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private var local: String? = null
    private var showKeys = false
    private var proximity: PowerManager.WakeLock? = null
    private var seenCall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        }
        scroll = ScrollView(this).apply { addView(root) }
        val viewport = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                scroll.layoutParams.width = minOf(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight, dp(520)).coerceAtLeast(0)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            setBackgroundColor(style.background)
            addView(scroll, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        }
        setContentView(viewport)
        fitSystemBars(viewport)
        handle(intent)
        // A call is starting: ask for the microphone again if it is still refused (Android stops showing the question
        // after two refusals; the warning below then says where to allow it).
        if (MicPolicy.atCall(app.hasMicPermission()) == MicPolicy.CallCheck.ASK) {
            app.journal.add("microphone: permission missing at call start: asking")
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        app.journal.add("microphone: " + if (app.hasMicPermission()) "allowed" else "refused")
        app.audio.micPermissionChanged()
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: android.content.Intent) {
        i.getStringExtra(EXTRA_LOCAL)?.let { local = it }
        if (i.getBooleanExtra(EXTRA_ANSWER, false)) local?.let { l -> app.telecom.connection(l)?.onAnswer() }
        render()
    }

    override fun onResume() {
        super.onResume()
        // In front now: the link service may become a microphone service (Android refuses that from the background).
        if (app.calls.hasCalls) app.service(callMode = true)
        app.addListener(listener); tick(); render()
    }
    override fun onPause() { app.removeListener(listener); main.removeCallbacksAndMessages(null); super.onPause() }
    override fun onDestroy() { proximity?.let { if (it.isHeld) it.release() }; super.onDestroy() }

    private fun tick() { main.postDelayed({ render(); tick() }, 1000) }

    private fun current(): LocalCall? {
        val calls = app.calls.calls
        return calls.firstOrNull { it.local == local } ?: calls.firstOrNull()
    }

    private fun render() {
        val c = current()
        if (c == null) { if (seenCall || app.calls.calls.isEmpty()) { updateProximity(null); finish() }; return }
        seenCall = true
        local = c.local
        val view = Presentation(c, app.calls.calls.filter { it.local != c.local }, app.state.simName(c.sub),
            app.micWarning?.let { micText(it) }, app.telecom.audioState, app.audio.muted, showKeys, app.calls.cidOf(c.local) != null)
        if (view != presentation) {
            val animateKeys = showKeys && presentation?.keypad == false
            presentation = view
            buildCall(view, animateKeys)
        }
        val stateText = when (c.state) {
            "ringing" -> getString(R.string.incoming_call)
            "dialing", "connecting" -> getString(R.string.call_dialing)
            "holding" -> getString(R.string.call_on_hold)
            else -> {
                val elapsed = c.connectedAt?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) }
                if (elapsed == null) getString(R.string.call_active)
                else getString(R.string.call_active) + " · " + "%d:%02d".format(elapsed / 60, elapsed % 60)
            }
        }
        val text = listOfNotNull(stateText, view.sim).joinToString(" · ")
        if (stateLabel.text.toString() != text) stateLabel.text = text
        updateProximity(if (c.state == "ringing" && c.incoming) null else view.audio?.route ?: CallAudioState.ROUTE_EARPIECE)
    }

    private data class Presentation(
        val call: LocalCall, val others: List<LocalCall>, val sim: String?, val warning: Int?,
        val audio: CallAudioState?, val muted: Boolean, val keypad: Boolean, val canHold: Boolean,
    )

    private var presentation: Presentation? = null
    private lateinit var stateLabel: TextView

    private fun buildCall(view: Presentation, animateKeys: Boolean) {
        val c = view.call
        val scrollY = scroll.scrollY
        root.removeAllViews()
        val who = c.name ?: app.format(c.number)
        if (!view.keypad) root.addView(avatar(who, app.state.photo(c.number), 72), LinearLayout.LayoutParams(dp(72), dp(72)))
        root.addView(label(who, size = if (view.keypad) 24f else 32f, bold = true).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (c.name != null) root.addView(label(app.format(c.number), R.color.muted, 16f).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textDirection = View.TEXT_DIRECTION_LTR
        }, LinearLayout.LayoutParams(-1, -2))
        stateLabel = label("", R.color.accent, 16f, bold = true).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }
        root.addView(stateLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        view.warning?.let { warning ->
            root.addView(label(getString(warning), R.color.warn, 14f).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER })
        }
        for (other in view.others) {
            val state = if (other.state == "holding") R.string.call_on_hold else R.string.call_waiting
            root.addView(label("${other.name ?: app.format(other.number)} · ${getString(state)}", R.color.warn, 14f).apply {
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                minimumHeight = dp(48)
                setOnClickListener { local = other.local; render() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        phoneAppRow(c.local)
        if (c.state == "ringing" && c.incoming) {
            root.addView(controlRow(
                control(R.drawable.ic_call_end, R.string.decline, prominent = R.color.bad) {
                    app.telecom.connection(c.local)?.onReject() ?: app.calls.performEnd(c.local)
                },
                control(R.drawable.ic_thread_call, R.string.answer, prominent = R.color.ok) {
                    app.telecom.connection(c.local)?.onAnswer()
                },
            ))
            return
        }

        val route = view.audio?.route ?: CallAudioState.ROUTE_EARPIECE
        val routeIcon = when (route) {
            CallAudioState.ROUTE_BLUETOOTH -> R.drawable.ic_link_bluetooth
            CallAudioState.ROUTE_WIRED_HEADSET -> R.drawable.ic_call_headset
            CallAudioState.ROUTE_SPEAKER -> R.drawable.ic_call_speaker
            else -> R.drawable.ic_thread_call
        }
        val routeName = when (route) {
            CallAudioState.ROUTE_SPEAKER -> R.string.route_speaker
            CallAudioState.ROUTE_BLUETOOTH -> R.string.route_bluetooth
            CallAudioState.ROUTE_WIRED_HEADSET -> R.string.route_wired
            else -> R.string.route_earpiece
        }
        val controls = mutableListOf(
            control(R.drawable.ic_call_mic, if (view.muted) R.string.unmute_mic else R.string.mute_mic, view.muted) {
                app.telecom.setMuted(!app.audio.muted)
            },
            control(routeIcon, routeName, route != CallAudioState.ROUTE_EARPIECE) { nextRoute(app.telecom.audioState) },
            control(R.drawable.ic_link_keypad, R.string.keypad, view.keypad) { showKeys = !showKeys; render() },
        )
        if (view.canHold) controls += control(R.drawable.ic_call_hold, if (c.held) R.string.resume else R.string.hold, c.held) {
            val connection = app.telecom.connection(c.local)
            if (c.held) connection?.onUnhold() else connection?.onHold()
        }
        root.addView(controlRow(*controls.toTypedArray()))
        if (view.keypad) {
            val keypad = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            val letters = listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "", "")
            "123456789*0#".chunked(3).forEachIndexed { row, digits ->
                keypad.addView(controlRow(*digits.mapIndexed { column, digit ->
                    val caption = letters[row * 3 + column]
                    LinearLayout(this).apply {
                        gravity = Gravity.CENTER
                        orientation = LinearLayout.VERTICAL
                        minimumHeight = dp(48)
                        background = style.shape(col(R.color.surface), 26)
                        isFocusable = true
                        contentDescription = digit.toString()
                        addView(label(digit.toString(), size = 23f, bold = true).apply {
                            textAlignment = View.TEXT_ALIGNMENT_CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        })
                        if (caption.isNotEmpty()) addView(label(caption, R.color.muted, 9f).apply {
                            textAlignment = View.TEXT_ALIGNMENT_CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        })
                        setOnClickListener { app.calls.performDtmf(c.local, digit.toString()) }
                    }
                }.toTypedArray()))
            }
            root.addView(keypad, LinearLayout.LayoutParams(-1, -2))
            if (animateKeys) { keypad.alpha = 0f; keypad.animate().alpha(1f).setDuration(160).start() }
        }
        root.addView(control(R.drawable.ic_call_end, R.string.end_call, prominent = R.color.bad) {
            app.telecom.connection(c.local)?.onDisconnect() ?: app.calls.performEnd(c.local)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (!animateKeys) scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun controlRow(vararg controls: View) = row().apply {
        gravity = Gravity.TOP
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        controls.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        }) }
    }

    private fun control(icon: Int, title: Int, active: Boolean = false, prominent: Int? = null, action: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isSelected = active
            contentDescription = getString(title)
            setOnClickListener { action() }
            val size = if (prominent != null) 64 else 48
            addView(android.widget.ImageView(context).apply {
                val fill = prominent ?: if (active) R.color.accent else R.color.surface
                background = style.shape(col(fill), size / 2)
                setImageResource(icon)
                setColorFilter(col(if (active || prominent != null) R.color.background else R.color.text))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(size), dp(size)))
            addView(label(getString(title), size = 12f).apply {
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }

    /**
     * The way to the Phone app from here: its own call screen for a call that is in the Phone app; "Move to the Phone
     * app" for a call in this screen when a Tether calling account is on; else, with the setting on, why the call is
     * here and where to turn an account on.
     */
    private fun phoneAppRow(local: String) {
        val t = app.telecom
        when {
            t.inPhoneApp(local) -> root.addView(button(getString(R.string.open_phone_app_screen), Look.TONAL, iconId = R.drawable.ic_call) {
                if (t.showPhoneAppCallScreen()) finish()
            })
            t.canMoveToPhoneApp(local) -> root.addView(button(getString(R.string.move_to_phone_app), Look.TONAL, iconId = R.drawable.ic_call) {
                // The Phone app rings a ringing call itself; a running one shows in its call screen once moved.
                if (t.moveToPhoneApp(local)) main.postDelayed({ t.showPhoneAppCallScreen(); finish() }, 800)
            })
            t.usePhoneApp -> {
                root.addView(label(getString(R.string.call_not_in_phone_app), R.color.muted, 13f).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
                root.addView(button(getString(R.string.phone_app_open_accounts), Look.TEXT) { t.openCallingAccounts(this) })
            }
        }
    }

    /** Earpiece → speaker → Bluetooth / wired (whatever is there) → earpiece. */
    private fun nextRoute(s: CallAudioState?) {
        nextAudioRoute(s?.supportedRouteMask, s?.route)?.let { app.telecom.setRoute(it) }
    }

    /** Screen off at the ear while the call is on the earpiece (not on speaker or a headset). */
    private fun updateProximity(route: Int?) {
        val want = route == CallAudioState.ROUTE_EARPIECE
        val pm = getSystemService(PowerManager::class.java)
        if (want && proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "tether:proximity").apply { acquire(4 * 3600_000L) }
        else if (!want && proximity != null) { proximity?.let { if (it.isHeld) it.release() }; proximity = null }
    }

    companion object { const val EXTRA_LOCAL = "local"; const val EXTRA_ANSWER = "answer" }
}
