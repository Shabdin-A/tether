package app.tether.client.ui

import app.tether.client.model.BatteryAlert
import app.tether.client.report.ClientReport
import app.tether.client.report.ReportUi
import android.Manifest
import app.tether.client.model.Sim
import android.widget.Toast
import android.widget.ImageButton
import android.widget.ImageView
import android.text.TextUtils
import android.graphics.drawable.InsetDrawable
import android.graphics.Color
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.PopupMenu
import app.tether.client.R
import app.tether.client.link.BleCentral
import app.tether.client.link.LinkService
import app.tether.client.model.RecentsLayout
import app.tether.client.model.Search
import app.tether.client.model.UssdCodes
import app.tether.client.model.dialable
import app.tether.core.CallEntry
import app.tether.core.Contact
import app.tether.core.Phone

/** Recents, Contacts (Favourites), Keypad, Messages and Link: the iPhone app's five tabs. */
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var status: TextView
    private lateinit var legacyHeader: LinearLayout
    private lateinit var viewport: ClientTabViewport
    private lateinit var tabBar: LinearLayout
    private var tabAppearance: Triple<Int, Int, Int>? = null
    private var tab = TAB_KEYPAD
    private val listener: () -> Unit = { render() }
    private var query = ""
    private var number = StringBuilder()
    private var list: ListView? = null
    /** The frame around [list], and Recents' floating date bubble in it (null until Recents shows). */
    private var listFrame: FrameLayout? = null
    private var dayBubble: TextView? = null
    private var listTab = -1
    private val linkViews by lazy { LinkViews(this) }
    private var linkSection: LinkSection? = null
    private var revealLinkSection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: if (app.identity.isPaired) TAB_RECENTS else TAB_LINK
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.background)) }
        legacyHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(6)) }
        legacyHeader.addView(label(getString(R.string.app_name), size = 22f, bold = true).weight())
        status = label("", R.color.muted, 13f)
        legacyHeader.addView(status)
        root.addView(legacyHeader)
        banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        root.addView(banner)
        viewport = ClientTabViewport(this)
        content = viewport.canvas
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(linkViews.background)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        root.addView(tabBar)
        setContentView(root)
        fitSystemBars(root)
        number.append(savedInstanceState?.getString(EXTRA_NUMBER).orEmpty())
        if (savedInstanceState == null) handleIntent(intent) else render()
        askPermissions()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        i?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it >= 0 }?.let { tab = it }
        i?.getStringExtra(EXTRA_NUMBER)?.let { number = StringBuilder(dialable(it)); tab = TAB_KEYPAD }
        listTab = -1
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", tab); outState.putString(EXTRA_NUMBER, number.toString()) }

    override fun onResume() {
        super.onResume()
        app.addListener(listener)
        app.appVisible = true
        if (app.calls.hasCalls) app.service(callMode = true)
        render()
    }

    override fun onPause() {
        app.removeListener(listener)
        app.appVisible = false
        super.onPause()
    }

    private fun askPermissions() {
        val want = app.linkPermissions() + listOfNotNull(Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        else if (app.identity.isPaired) LinkService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (app.identity.isPaired) LinkService.start(this)
        render()
    }

    private fun select(i: Int) {
        if (tab == TAB_RECENTS && i != TAB_RECENTS) app.state.markRecentsSeen()
        tab = i; query = ""; listTab = -1
        if (picked != null || pickedChats != null) { picked = null; pickedChats = null; watchBack(false) }
        render()
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        val s = app.state
        if (!app.link.isConnected) keypadSim.clear()
        else if (keypadSim.hasTemporarySelection) keypadSim.resolve(keypadSims().map { it.sub }, s.defaultSub)
        status.text = when (app.linkState) {
            BleCentral.State.CONNECTED -> "● " + (app.identity.peerName ?: getString(R.string.android_phone))
            BleCentral.State.OFF -> getString(R.string.state_bt_off)
            BleCentral.State.UNPAIRED -> getString(R.string.state_unpaired)
            BleCentral.State.SEARCHING -> getString(R.string.state_searching)
            else -> getString(R.string.state_connecting)
        }
        status.setTextColor(col(if (app.linkState == BleCentral.State.CONNECTED) R.color.ok else R.color.muted))
        legacyHeader.visibility = if (tab == TAB_KEYPAD) View.GONE else View.VISIBLE
        viewport.bounded = tab != TAB_LINK
        renderTabBar(s.unreadTotal, s.missedCount)
        renderBanner()
        when (tab) {
            TAB_RECENTS -> recents()
            TAB_CONTACTS -> contacts()
            TAB_KEYPAD -> keypad()
            TAB_MESSAGES -> messages()
            else -> linkTab()
        }
    }

    private fun renderTabBar(unread: Int, missed: Int) {
        val appearance = Triple(tab, unread, missed)
        if (tabAppearance == appearance) return
        tabAppearance = appearance
        tabBar.removeAllViews()
        for (destination in TAB_NAMES.indices.reversed()) {
            val badge = when (destination) {
                TAB_MESSAGES -> unread
                TAB_RECENTS -> missed
                else -> 0
            }
            tabBar.addView(linkViews.tab(TAB_ICONS[destination], getString(TAB_NAMES[destination]),
                destination == tab, badge) { select(destination) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun renderBanner() {
        banner.removeAllViews()
        app.state.problem?.let { p ->
            // Tap to dismiss.
            banner.addView(label(describe(p), R.color.warn, 14f).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = dp(48)
                pressableCard(R.color.surface) { app.state.problem = null; render() }
            }, bannerParams())
        }
        app.micWarning?.takeIf { app.identity.isPaired }?.let { w ->
            banner.addView(label(getString(micText(w)), R.color.warn, 14f).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(col(R.color.surface)); cornerRadius = dp(10).toFloat() }
            }, bannerParams())
        }
        app.calls.calls.firstOrNull()?.let { c ->
            // The call in progress: tap to go back to the call screen.
            banner.addView(label((c.name ?: app.format(c.number)) + " · " + stateWord(c.state), R.color.background, 14f, bold = true).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelative(icon(R.drawable.ic_call, R.color.background, 18), null, null, null); compoundDrawablePadding = dp(10)
                pressableCard(R.color.ok) { app.openCallScreen() }
            }, bannerParams())
        }
        for (r in app.calls.remote.values) {
            banner.addView(row(
                label(getString(R.string.call_elsewhere, app.displayName(r.num), r.where ?: getString(R.string.another_device)), R.color.text, 14f).weight(),
                button(getString(R.string.move_here), iconId = R.drawable.ic_call) { app.calls.take(r.cid) }.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), dp(4), dp(4), dp(4)) }
                },
            ).apply { setPadding(dp(12), dp(2), dp(4), dp(2)); background = GradientDrawable().apply { setColor(col(R.color.surface)); cornerRadius = dp(10).toFloat() } }, bannerParams())
        }
    }

    private fun bannerParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }

    private fun stateWord(s: String) = getString(when (s) {
        "ringing" -> R.string.call_ringing; "dialing", "connecting" -> R.string.call_dialing
        "holding" -> R.string.call_on_hold; else -> R.string.call_active
    })

    /** A search field above a list; the list survives redraws (its scroll position too). */
    private fun listScreen(hint: String?, extra: View? = null, rows: Rows<*>, onLong: ((Int) -> Unit)? = null, onClick: ((Int) -> Unit)? = null) {
        val lv = list.takeIf { listTab == tab } ?: run {
            content.removeAllViews()
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
            if (hint != null || extra != null) {
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
                if (hint != null) top.addView(EditText(this).apply {
                    this.hint = hint; setText(query); setSingleLine(); setTextColor(col(R.color.text)); setHintTextColor(col(R.color.muted))
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(e: Editable?) { query = e?.toString() ?: ""; render() }
                    })
                }.weight())
                extra?.let { top.addView(it) }
                box.addView(top)
            }
            val l = ListView(this).apply { divider = null; isFocusable = false }
            // The list in a frame, so a screen can float something over it (Recents' date bubble).
            val frame = FrameLayout(this).apply { addView(l, FrameLayout.LayoutParams(-1, -1)) }
            box.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
            content.addView(box)
            list = l; listTab = tab; listFrame = frame; dayBubble = null
            l
        }
        val first = lv.firstVisiblePosition; val top = lv.getChildAt(0)?.top ?: 0
        lv.adapter = rows   // rows are built as they scroll into view
        lv.setSelectionFromTop(first, top)
        lv.setOnItemClickListener { _, _, pos, _ -> onClick?.invoke(pos) }
        lv.setOnItemLongClickListener { _, _, pos, _ -> onLong?.invoke(pos); onLong != null }
    }

    private fun rowView(avatar: View?, title: CharSequence, subtitle: CharSequence?, right: CharSequence?, titleColor: Int = R.color.text, bold: Boolean = false) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10))
            avatar?.let { addView(it) }
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(title, titleColor, 16f, bold).apply { setSingleLine() })
            subtitle?.let { texts.addView(label(it, R.color.muted, 13f).apply { maxLines = 2 }) }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            right?.let { addView(label(it, R.color.muted, 12f).apply { setPadding(dp(8), 0, 0, 0) }) }
        }

    // ------------------------------------------------------------------ Recents

    /**
     * Recents grouped by day, as phones' own call logs show them: a day heading ("Today", "Yesterday", the date) over a
     * card of that day's calls; per line the direction (arrow), the name (red when missed, a count for calls in a row
     * from the same number), the SIM and how long the call took, and the time. While the list scrolls, a bubble at the
     * top names the day being scrolled through, and fades a moment after it stops.
     */
    private fun recents() {
        val s = app.state
        val calls = s.recents.filter { Search.matches(query, listOfNotNull(it.name, s.name(it.num)), listOf(it.num)) }
        val items = RecentsLayout.build(calls, ::dayStart)
        val clear = iconButton(R.drawable.ic_delete_sweep, getString(R.string.clear_all), R.color.accent) {
            AlertDialog.Builder(this).setMessage(R.string.clear_recents_confirm)
                .setPositiveButton(R.string.clear_all) { _, _ -> s.clearRecents() }.setNegativeButton(android.R.string.cancel, null).show()
        }
        // Calls deleted meanwhile (here or on the Android phone) are no longer picked.
        if (picked != null) { val have = s.recents.mapTo(HashSet()) { it.id }; picked!!.retainAll(have) }
        listScreen(getString(R.string.search), clear, Rows(items) { item ->
            when (item) {
                is RecentsLayout.Item.Day -> label(dayName(item.day).uppercase(), R.color.muted, 13f, bold = true).apply {
                    letterSpacing = 0.06f; setPadding(dp(8), dp(16), dp(8), dp(8))
                }
                is RecentsLayout.Item.Line -> recentLine(item)
            }
        }, onLong = { pos ->
            // A long press starts picking calls (as phones' own call logs do); the one-call menu is on the bar (⋮).
            val line = items[pos] as? RecentsLayout.Item.Line
            if (picked == null && line != null) { picked = LinkedHashSet(); togglePick(items, pos) } else togglePick(items, pos)
        }) { pos ->
            if (picked != null) togglePick(items, pos)
            else (items[pos] as? RecentsLayout.Item.Line)?.newest?.let { app.dial(it.num, it.sub ?: s.defaultSub) }
        }
        list?.selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)   // the lines ripple themselves
        dateBubble(items)
        selectionBar(items)
    }

    /**
     * The calls picked for deleting together (their ids; a line of calls in a row picks all of them), or null when
     * Recents is not picking. Picking ends on Back, on ✕, on another tab and after deleting; unpicking everything keeps
     * it on (a day's heading tapped twice).
     */
    private var picked: LinkedHashSet<Long>? = null
    private var selectBar: View? = null
    private var backWhilePicking: Any? = null

    private fun idsOf(item: RecentsLayout.Item, items: List<RecentsLayout.Item>): List<Long> = when (item) {
        is RecentsLayout.Item.Line -> item.calls.map { it.id }
        // A day's heading picks (or unpicks) every call of that day.
        is RecentsLayout.Item.Day -> items.filterIsInstance<RecentsLayout.Item.Line>().filter { it.day == item.day }.flatMap { l -> l.calls.map { it.id } }
    }

    private fun togglePick(items: List<RecentsLayout.Item>, pos: Int) {
        val set = picked ?: return
        val ids = idsOf(items.getOrNull(pos) ?: return, items)
        if (ids.isNotEmpty() && set.containsAll(ids)) set.removeAll(ids.toSet()) else set.addAll(ids)
        list?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        render()   // stays selecting with nothing picked (a day picked and unpicked again); ✕ or Back ends it
    }

    private fun stopPicking() { picked = null; pickedChats = null; render() }

    /** The bar floating at the bottom of Recents while picking: ✕, how many, ⋮ (one call's menu), select all, delete. */
    private fun selectionBar(items: List<RecentsLayout.Item>) {
        val frame = listFrame ?: return
        selectBar?.let { if (it.parent === frame) frame.removeView(it) }; selectBar = null
        val set = picked
        watchBack(set != null)
        if (set == null) return
        val all = items.filterIsInstance<RecentsLayout.Item.Line>().flatMap { l -> l.calls.map { it.id } }
        val allPicked = all.isNotEmpty() && set.containsAll(all)
        val one = items.filterIsInstance<RecentsLayout.Item.Line>().singleOrNull { l -> l.calls.any { it.id in set } }
            ?.takeIf { l -> l.calls.map { it.id }.containsAll(set) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(8), dp(4)); elevation = dp(8).toFloat()
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(col(R.color.surface)); setStroke(dp(1), col(R.color.background)) }
            isClickable = true   // taps on the bar never reach the list under it
            addView(iconButton(R.drawable.ic_close, getString(R.string.done_selecting)) { stopPicking() })
            addView(label(getString(R.string.recents_selected, set.size), size = 15f, bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }.weight())
            one?.let { l -> addView(iconButton(R.drawable.ic_more_vert, getString(R.string.more_options)) { recentMenu(l.newest) }) }
            // An icon, so "3 selected" keeps its room (in words it cut the count to "1 selec"); lit when all are picked.
            addView(iconButton(R.drawable.ic_select_all, getString(if (allPicked) R.string.select_none else R.string.select_all),
                if (allPicked) R.color.accent else R.color.text) {
                if (allPicked) set.clear() else set.addAll(all); render()
            })
            addView(button(getString(R.string.delete), Look.FILLED, R.color.bad, iconId = R.drawable.ic_delete_sweep) {
                if (set.isEmpty()) return@button
                val ids = set.toList()
                AlertDialog.Builder(this@MainActivity).setMessage(getString(R.string.delete_calls_confirm, ids.size))
                    .setPositiveButton(R.string.delete) { _, _ -> app.state.deleteRecents(ids); stopPicking() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) }; isEnabled = set.isNotEmpty() })
        }
        frame.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(4), 0, dp(4), dp(12)) })
        selectBar = bar
        // Room under the last call, so the bar never covers it.
        list?.apply { clipToPadding = false; setPadding(0, 0, 0, dp(84)) }
    }

    /** Back ends picking (Android 13+ through the back dispatcher: an app targeting Android 16 gets no onBackPressed). */
    private fun watchBack(on: Boolean) {
        if (!on) list?.setPadding(0, 0, 0, 0)
        if (Build.VERSION.SDK_INT >= 33) {
            val d = onBackInvokedDispatcher
            if (on && backWhilePicking == null) {
                val cb = android.window.OnBackInvokedCallback { stopPicking() }
                d.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb); backWhilePicking = cb
            } else if (!on) (backWhilePicking as? android.window.OnBackInvokedCallback)?.let { d.unregisterOnBackInvokedCallback(it); backWhilePicking = null }
        }
    }

    @Deprecated("Android 12 and older; newer ones use watchBack()")
    override fun onBackPressed() {
        if (picked != null || pickedChats != null) stopPicking() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** [a] with [f] of [b] mixed in (opaque: the list's rows sit on the page). */
    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /** One line of a day's card: direction, name (and count), SIM and duration, time. Rounded where its card is. */
    private fun recentLine(item: RecentsLayout.Item.Line): View {
        val s = app.state
        val c = item.newest
        val who = s.name(c.num) ?: c.name ?: app.format(c.num)
        val missed = c.type == 3
        val (iconId, iconColor) = when (c.type) {
            1 -> R.drawable.ic_call_received to R.color.muted
            2 -> R.drawable.ic_call_made to R.color.ok
            3 -> R.drawable.ic_call_missed to R.color.bad
            5 -> R.drawable.ic_call_declined to R.color.bad
            6 -> R.drawable.ic_block to R.color.muted
            else -> R.drawable.ic_call_received to R.color.muted
        }
        val kind = getString(when (c.type) { 1 -> R.string.call_in; 2 -> R.string.call_out; 3 -> R.string.call_missed; 5 -> R.string.call_declined; 6 -> R.string.call_blocked; else -> R.string.call_other })
        val duration = if (c.dur > 0) "%d:%02d".format(c.dur / 60, c.dur % 60) else null
        val time = android.text.format.DateUtils.formatDateTime(this, c.date, android.text.format.DateUtils.FORMAT_SHOW_TIME)
        val sim = s.simName(c.sub)
        val r = dp(18).toFloat()
        val radii = when (item.pos) {
            RecentsLayout.Pos.ONLY -> floatArrayOf(r, r, r, r, r, r, r, r)
            RecentsLayout.Pos.FIRST -> floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            RecentsLayout.Pos.LAST -> floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            RecentsLayout.Pos.MIDDLE -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        val picking = picked != null
        val isPicked = picking && item.calls.all { it.id in picked!! }
        val body = GradientDrawable().apply {
            cornerRadii = radii
            setColor(if (isPicked) mix(col(R.color.surface), col(R.color.accent), 0.2f) else col(R.color.surface))
        }
        val mask = GradientDrawable().apply { cornerRadii = radii; setColor(col(R.color.text)) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.RippleDrawable(rippleColor(R.color.text), body, mask)
            // A thin line between the lines of a card (not above its first).
            if (item.pos == RecentsLayout.Pos.MIDDLE || item.pos == RecentsLayout.Pos.LAST)
                addView(View(context).apply { setBackgroundColor(col(R.color.background)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(48) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(16), dp(10)); minimumHeight = dp(62)
                if (picking) addView(android.widget.ImageView(context).apply {
                    // A check mark in a filled circle when picked, an empty ring when not.
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (isPicked) setColor(col(R.color.accent)) else setStroke(dp(2), col(R.color.muted))
                    }
                    if (isPicked) setImageDrawable(icon(R.drawable.ic_check, R.color.background, 16))
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
                else addView(android.widget.ImageView(context).apply { setImageDrawable(icon(iconId, iconColor, 20)) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
                val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(label(android.text.SpannableStringBuilder(who).apply {
                    if (item.calls.size > 1) {
                        val at = length; append(" (${item.calls.size})")
                        setSpan(android.text.style.ForegroundColorSpan(col(R.color.muted)), at, length, 0)
                    }
                }, if (missed) R.color.bad else R.color.text, 16f).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, 0) })
                texts.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, 0)
                    sim?.let { addView(label(it, R.color.muted, 11f).apply {
                        setPadding(dp(5), 0, dp(5), 0); background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(1), col(R.color.muted)); alpha = 120 }
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
                    addView(label(duration ?: kind, R.color.muted, 13f).apply { setPadding(0, 0, 0, 0); if (duration != null) textDirection = View.TEXT_DIRECTION_LTR })
                })
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(time, if (missed) R.color.bad else R.color.muted, 14f).apply { setPadding(dp(8), 0, 0, 0); textDirection = View.TEXT_DIRECTION_LTR })
            })
            // TalkBack: one sentence for the line.
            isSelected = isPicked
            contentDescription = listOfNotNull(if (isPicked) getString(R.string.recents_selected, item.calls.size) else null, who, kind, item.calls.size.takeIf { it > 1 }?.toString(), sim, duration, time).joinToString(", ")
        }
    }

    /** Whether the user is scrolling Recents (a finger on it, or a fling): kept here, as each render adds a new listener. */
    private var recentsScrolling = false
    private var bubbleShown = false
    /** One fade for every render's listener, so a later render can still cancel it. */
    private val bubbleFade = Runnable { bubbleShown = false; dayBubble?.animate()?.alpha(0f)?.setDuration(250)?.start() }

    /**
     * The bubble that names the day being scrolled through (as the Samsung Phone app does), "Today" at the top too.
     * Shown when a scroll starts (not when the list only moves because it was rebuilt), faded a moment after it stops.
     * It is shown once per scroll: starting the fade-in again on every scrolled frame kept it invisible until the
     * list stopped.
     */
    private fun dateBubble(items: List<RecentsLayout.Item>) {
        val frame = listFrame ?: return
        val bubble = dayBubble ?: label("", size = 15f).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; maxLines = 1
            setPadding(dp(18), dp(8), dp(18), dp(8)); alpha = 0f; elevation = dp(6).toFloat()
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(col(R.color.surface)); setStroke(dp(1), col(R.color.background)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            frame.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
            dayBubble = this; recentsScrolling = false; bubbleShown = false
        }
        fun show() {
            bubble.removeCallbacks(bubbleFade)
            if (bubbleShown) return
            bubbleShown = true
            bubble.animate().cancel(); bubble.animate().alpha(1f).setDuration(120).start()
        }
        list?.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: android.widget.AbsListView, state: Int) {
                recentsScrolling = state != android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (recentsScrolling) show() else { bubble.removeCallbacks(bubbleFade); bubble.postDelayed(bubbleFade, 900) }
            }
            override fun onScroll(v: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                RecentsLayout.dayAt(items, first)?.let { bubble.text = dayName(it) }
                if (recentsScrolling) show()
            }
        })
    }

    private fun recentMenu(c: CallEntry) {
        val s = app.state
        val blocked = s.isBlocked(c.num)
        val opts = listOf(getString(R.string.call), getString(R.string.message), getString(R.string.copy_number), getString(R.string.delete),
            getString(if (blocked) R.string.unblock else R.string.block))
        AlertDialog.Builder(this).setTitle(s.name(c.num) ?: app.format(c.num)).setItems(opts.toTypedArray()) { _, w ->
            when (w) {
                0 -> app.dial(c.num, c.sub ?: s.defaultSub)
                1 -> openThread(c.num)
                2 -> copy(c.num)
                3 -> s.deleteRecents(listOf(c.id))
                4 -> s.setBlocked(c.num, !blocked)
            }
        }.show()
    }

    // ------------------------------------------------------------------ Contacts

    private fun contacts() {
        val s = app.state
        val all = s.contacts.filter { Search.matches(query, listOf(it.name), it.phones.map { p -> p.n }) }
        val favs = if (query.isBlank()) all.filter { it.starred } else emptyList()
        val items = ArrayList<Any>()
        if (favs.isNotEmpty()) { items += getString(R.string.favourites); items.addAll(favs); items += getString(R.string.all_contacts) }
        items.addAll(all)
        val add = iconButton(R.drawable.ic_person_add, getString(R.string.new_contact), R.color.accent) { editContact(null) }
        listScreen(getString(R.string.search), add, Rows(items) { it ->
            if (it is String) label(it.uppercase(), R.color.accent, 12f, bold = true).apply { setPadding(dp(4), dp(12), 0, dp(4)) }
            else (it as Contact).let { c -> rowView(avatar(c.name, c.photo), (if (c.starred) "★ " else "") + c.name, c.phones.firstOrNull()?.let { p -> app.format(p.n) }, null) }
        }) { pos -> (items[pos] as? Contact)?.let { contactMenu(it) } }
    }

    private fun contactMenu(c: Contact) {
        val s = app.state
        val opts = ArrayList<Pair<String, () -> Unit>>()
        for (p in c.phones) {
            opts += getString(R.string.call_number, app.format(p.n)) to { app.dial(p.n, s.defaultSub) }
            opts += getString(R.string.message_number, app.format(p.n)) to { openThread(p.n) }
        }
        opts += getString(if (c.starred) R.string.unfavourite else R.string.favourite) to { s.setStarred(c.id, !c.starred) }
        opts += getString(R.string.edit) to { editContact(c) }
        c.phones.firstOrNull()?.let { p -> val b = s.isBlocked(p.n); opts += getString(if (b) R.string.unblock else R.string.block) to { s.setBlocked(p.n, !b) } }
        AlertDialog.Builder(this).setTitle(c.name).setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }.show()
    }

    private fun editContact(c: Contact?, prefill: String? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = EditText(this).apply { hint = getString(R.string.name); setText(c?.name ?: "") }
        val phone = EditText(this).apply { hint = getString(R.string.phone); inputType = InputType.TYPE_CLASS_PHONE; setText(c?.phones?.firstOrNull()?.n ?: prefill ?: "") }
        box.addView(name); box.addView(phone)
        AlertDialog.Builder(this).setTitle(if (c == null) R.string.new_contact else R.string.edit).setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = name.text.toString().trim(); val p = phone.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                val phones = listOfNotNull(p.takeIf { it.isNotEmpty() }?.let { Phone(it, c?.phones?.firstOrNull()?.type ?: "mobile") }) + (c?.phones?.drop(1) ?: emptyList())
                if (c == null) app.state.addContact(n, phones, emptyList()) else app.state.updateContact(c.id, n, phones, c.emails)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    // ------------------------------------------------------------------ Keypad


    /** The keypad is built once while it is shown; a key press or a state change only updates its texts and buttons. */
    private lateinit var keypadView: KeypadView
    private val keypadSim = KeypadSimSelection()

    private fun keypadSims(): List<Sim> =
        if (app.link.isConnected && app.state.hasLiveStatus) app.state.sims else emptyList()

    private fun keypad() {
        if (listTab != TAB_KEYPAD) {
            listTab = TAB_KEYPAD
            list = null
            content.removeAllViews()
            keypadView = KeypadView()
            content.addView(keypadView, FrameLayout.LayoutParams(-1, -1))
        }
        keypadView.refresh()
    }

    private inner class KeypadView : ClientTabViewport(this@MainActivity) {
        private val owner = this@MainActivity
        private val entry = label("", size = 26f, bold = true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setAutoSizeTextTypeUniformWithConfiguration(16, 26, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            textDirection = View.TEXT_DIRECTION_LTR
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val contact = label("", R.color.muted, 14f).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        private val simBar = row().apply {
            background = InsetDrawable(linkViews.shape(KEY_SURFACE, 22), 0, dp(3), 0, dp(3))
            setPadding(0, 0, 0, 0)
        }
        private var renderedSims: List<Sim> = emptyList()
        private var renderedSelection: Int? = null
        private val add = iconAction(R.drawable.ic_keypad_add_contact, R.string.add_contact, 48, KEY_SURFACE, Color.WHITE) {
            if (number.isNotEmpty() && app.state.name(number.toString()) == null) editContact(null, number.toString())
        }
        private val erase = iconAction(R.drawable.ic_keypad_backspace, R.string.keypad_backspace, 48, KEY_SURFACE, Color.WHITE) {
            if (number.isNotEmpty()) number.deleteCharAt(number.length - 1)
            refreshNumber()
        }.apply {
            setOnLongClickListener { number.clear(); refreshNumber(); true }
        }

        private val ussdButton = button(getString(R.string.ussd_button), Look.TONAL, iconId = R.drawable.ic_dialpad) {
                openUssd(number.toString(), keypadSim.resolve(keypadSims().map { it.sub }, app.state.defaultSub))
            }

        init {
            val column = column()
            linkViews.heading(column, R.string.tab_keypad, R.string.keypad_subtitle, overflow = ::showLinkOverflow)
            val panel = LinearLayout(owner).apply {
                orientation = LinearLayout.VERTICAL
            }
            val entryHeight = entry.reservedLineHeight()
            val contactHeight = contact.reservedLineHeight()
            val numberRegion = LinearLayout(owner).apply {
                orientation = LinearLayout.VERTICAL
                addView(entry, LinearLayout.LayoutParams(-1, entryHeight))
                addView(contact, LinearLayout.LayoutParams(-1, contactHeight))
            }
            val upper = LinearLayout(owner).apply {
                orientation = LinearLayout.VERTICAL
                addView(simBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
                addView(numberRegion, LinearLayout.LayoutParams(-1, -2))
                addView(ussdButton, LinearLayout.LayoutParams(-2, -2))
            }
            panel.addView(upper, LinearLayout.LayoutParams(-1, -2))
            panel.addView(View(owner), LinearLayout.LayoutParams(-1, 0, 1f))
            val dialer = LinearLayout(owner).apply {
                orientation = LinearLayout.VERTICAL
            }
            panel.addView(dialer, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            })
            val gridRows = ArrayList<LinearLayout>(4)
            val keyViews = ArrayList<View>(12)
            val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
                "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
            for (line in keys.chunked(3)) {
                dialer.addView(row().apply {
                    gridRows += this
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    gravity = Gravity.CENTER
                    for ((digit, letters) in line) addView(FrameLayout(owner).apply {
                        val key = numberKey(digit, letters)
                        keyViews += key
                        addView(key, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginStart = dp(4); marginEnd = dp(4)
                    })
                }, LinearLayout.LayoutParams(-1, -2).apply {
                    marginStart = dp(8); marginEnd = dp(8)
                })
            }
            val actions = row().apply {
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            actions.addView(add, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(20) })
            val call = iconAction(R.drawable.ic_thread_call, R.string.call, 56, KEYPAD_ACCENT, Color.WHITE) {
                if (number.isEmpty()) return@iconAction
                val sims = keypadSims()
                val selected = keypadSim.resolve(sims.map { it.sub }, app.state.defaultSub)
                if (sims.size > 1 && selected == null) {
                    Toast.makeText(owner, R.string.keypad_select_sim, Toast.LENGTH_SHORT).show()
                    refresh()
                    return@iconAction
                }
                val value = number.toString()
                if (UssdCodes.valid(value) && app.state.canUssd) openUssd(value, selected)
                else app.dial(value, selected)
                number.clear()
                render()
            }
            actions.addView(call)
            actions.addView(erase, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(20) })
            dialer.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            val scroll = object : ScrollView(owner) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val width = MeasureSpec.getSize(widthMeasureSpec)
                    val height = MeasureSpec.getSize(heightMeasureSpec)
                    val density = resources.displayMetrics.density
                    val simHeight = if (simBar.visibility == View.GONE) 0 else {
                        simBar.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                        simBar.measuredHeight + dp(7)
                    }
                    val compact = height - simHeight - entryHeight - contactHeight < dp(48 * 4 + 5 * 3 + 52 + 32)
                    val orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    if (numberRegion.orientation != orientation) {
                        numberRegion.orientation = orientation
                        numberRegion.gravity = Gravity.CENTER_VERTICAL
                        entry.layoutParams = if (compact) LinearLayout.LayoutParams(0, entryHeight, 2f)
                            else LinearLayout.LayoutParams(-1, entryHeight)
                        contact.layoutParams = if (compact) LinearLayout.LayoutParams(0, contactHeight, 1f)
                            else LinearLayout.LayoutParams(-1, contactHeight)
                    }
                    val topGap = dp(if (compact) 4 else 8)
                    val actionGap = dp(if (compact) 8 else 12)
                    (dialer.layoutParams as LinearLayout.LayoutParams).topMargin = topGap
                    (actions.layoutParams as LinearLayout.LayoutParams).topMargin = actionGap
                    upper.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    val occupied = upper.measuredHeight + topGap + actionGap + dp(12)
                    val metrics = keypadMetrics((width / density).toInt(), ((height - occupied) / density).toInt(), compact)
                    for (key in keyViews) resize(key, dp(metrics.keyWidth), dp(metrics.keyHeight))
                    gridRows.forEachIndexed { index, row ->
                        val margin = if (index == gridRows.lastIndex) 0 else dp(metrics.rowGap)
                        val params = row.layoutParams as LinearLayout.LayoutParams
                        if (params.bottomMargin != margin) row.layoutParams = params.apply { bottomMargin = margin }
                    }
                    val callSize = dp(metrics.callSize)
                    resize(call, callSize, callSize)
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }

                override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                    super.onLayout(changed, left, top, right, bottom)
                    if (changed) scrollTo(0, (panel.height - height).coerceAtLeast(0))
                }
            }.apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(panel, FrameLayout.LayoutParams(-1, -2))
            }
            column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            canvas.addView(column, FrameLayout.LayoutParams(-1, -1))
        }

        private fun TextView.reservedLineHeight(): Int = paint.fontMetricsInt.let {
            it.bottom - it.top + paddingTop + paddingBottom
        }

        private fun resize(view: View, width: Int, height: Int) {
            val params = view.layoutParams
            if (params.width != width || params.height != height) {
                view.layoutParams = params.apply { this.width = width; this.height = height }
            }
        }

        fun refresh() {
            ussdButton.visibility = if (app.state.canUssd) View.VISIBLE else View.GONE
            refreshNumber()
            val sims = keypadSims()
            val selected = keypadSim.resolve(sims.map { it.sub }, app.state.defaultSub)
            val choices = sims.takeIf { it.size > 1 }.orEmpty()
            simBar.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
            if (renderedSims == choices && renderedSelection == selected) return
            renderedSims = choices
            renderedSelection = selected
            simBar.removeAllViews()
            for (sim in choices) {
                val active = sim.sub == selected
                val slot = getString(R.string.sim_n, sim.slot + 1)
                val detail = sim.carrier.ifEmpty { sim.name }.takeUnless { it == slot }.orEmpty()
                val choice = row().apply {
                    minimumHeight = dp(48)
                    gravity = Gravity.CENTER
                    if (active) background = InsetDrawable(linkViews.shape(KEYPAD_SELECTED, 22).apply {
                        setStroke(dp(1), KEYPAD_ACCENT)
                    }, 0, dp(3), 0, dp(3))
                    setPaddingRelative(dp(8), dp(3), dp(8), dp(3))
                    isSelected = active
                    isFocusable = true
                    contentDescription = listOf(slot, detail).filter { it.isNotBlank() }.joinToString(", ")
                    setOnClickListener {
                        if (keypadSims().any { it.sub == sim.sub }) keypadSim.select(sim.sub)
                        refresh()
                    }
                    addView(ImageView(owner).apply {
                        setImageResource(R.drawable.ic_keypad_sim)
                        setColorFilter(Color.WHITE)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
                    addView(LinearLayout(owner).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(slot, size = 12f, bold = true).apply { setPadding(0, 0, 0, 0) })
                        if (detail.isNotBlank()) addView(label(detail, size = 10f).apply {
                            setPadding(0, 0, 0, 0)
                            setSingleLine()
                            ellipsize = TextUtils.TruncateAt.END
                        })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                simBar.addView(choice, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }

        private fun refreshNumber() {
            val value = number.toString()
            val name = app.state.name(value).orEmpty()
            entry.text = if (value.isEmpty()) "" else app.format(value)
            entry.visibility = if (value.isEmpty()) View.INVISIBLE else View.VISIBLE
            contact.text = name
            contact.visibility = if (name.isEmpty()) View.INVISIBLE else View.VISIBLE
            add.isEnabled = value.isNotEmpty() && name.isEmpty() && !UssdCodes.valid(value)
            add.alpha = if (add.isEnabled) 1f else 0.45f
            erase.isEnabled = value.isNotEmpty()
            erase.alpha = if (erase.isEnabled) 1f else 0.45f
        }

        private fun numberKey(digit: String, letters: String) = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = circle(KEY_SURFACE)
            contentDescription = digit
            isFocusable = true
            addView(label(digit, size = 23f, bold = true).apply {
                includeFontPadding = false
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            if (letters.isNotEmpty()) addView(label(letters, size = 9f).apply {
                includeFontPadding = false
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setOnClickListener { number.append(digit); refreshNumber() }
            if (digit == "0") setOnLongClickListener { number.append("+"); refreshNumber(); true }
        }

        private fun iconAction(res: Int, title: Int, size: Int, color: Int, tint: Int, action: () -> Unit) =
            ImageButton(owner).apply {
                setImageResource(res)
                setColorFilter(tint)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = circle(color)
                contentDescription = getString(title)
                layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
                setOnClickListener { action() }
            }

        private fun circle(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    /** The carrier-code screen, with [code] typed in (sent at once when it is a code) on SIM [sub]. */
    private fun openUssd(code: String, sub: Int?) {
        startActivity(Intent(this, UssdActivity::class.java).putExtra(UssdActivity.EXTRA_CODE, code).apply { sub?.let { putExtra(UssdActivity.EXTRA_SUB, it) } }
            .putExtra(UssdActivity.EXTRA_SEND, UssdCodes.valid(code)))
    }

    // ------------------------------------------------------------------ Messages

    private fun messages() {
        val s = app.state
        val convs = s.conversations.filter { c -> Search.matches(query, listOfNotNull(s.name(c.address)), listOf(c.address)) ||
            (query.length > 1 && s.messages(c.thread).any { Search.fold(it.body).contains(Search.fold(query)) }) }
        val compose = iconButton(R.drawable.ic_edit, getString(R.string.new_message), R.color.accent) {
            val f = EditText(this).apply { hint = getString(R.string.phone); inputType = InputType.TYPE_CLASS_PHONE }
            AlertDialog.Builder(this).setTitle(R.string.new_message).setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) })
                .setPositiveButton(R.string.next) { _, _ -> dialable(f.text.toString()).takeIf { it.isNotEmpty() }?.let { openThread(it) } }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        // Beside the search field: the starred texts (gold), then New message (the pen).
        val star = iconButton(R.drawable.ic_star_border, getString(R.string.starred_messages), R.color.warn) {
            startActivity(Intent(this, StarredActivity::class.java))
        }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(star); addView(compose) }
        // Conversations deleted meanwhile are no longer picked.
        pickedChats?.retainAll(s.conversations.mapTo(HashSet()) { it.thread })
        listScreen(getString(R.string.search), tools, Rows(convs) { c -> conversationRow(c, last = c === convs.lastOrNull()) }, onLong = { pos ->
            // A long press starts picking conversations; the actions for them come in a bar above the list.
            if (pickedChats == null) pickedChats = LinkedHashSet()
            toggleChat(convs[pos].thread)
        }) { pos ->
            if (pickedChats != null) toggleChat(convs[pos].thread) else openThread(convs[pos].address, convs[pos].thread)
        }
        chatSelectionBar(convs)
    }

    /** The conversations (threads) picked for acting on together, or null when the Messages list is not picking. */
    private var pickedChats: LinkedHashSet<Long>? = null

    private fun toggleChat(thread: Long) {
        val set = pickedChats ?: return
        if (!set.remove(thread)) set += thread
        list?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        render()
    }

    /**
     * While picking: the search row gives way to a bar (✕, how many, select all) and a row of actions for the picked
     * conversations: Read, Mute / Unmute, Block / Unblock, Delete, and Call when there is one. Both stay above the
     * list, so they stay in place while it scrolls.
     */
    private fun chatSelectionBar(convs: List<app.tether.client.model.Conversation>) {
        val box = listFrame?.parent as? LinearLayout ?: return
        (0 until box.childCount).map(box::getChildAt).filter { it.tag == SELECTION_TAG }.forEach(box::removeView)
        val search = box.getChildAt(0).takeIf { it !== listFrame }
        val set = pickedChats
        watchBack(set != null)
        search?.visibility = if (set == null) View.VISIBLE else View.GONE
        if (set == null) return
        val s = app.state
        val picked = s.conversations.filter { it.thread in set }
        val all = convs.map { it.thread }
        val allPicked = all.isNotEmpty() && set.containsAll(all)
        fun bar() = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(col(R.color.surface)) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0); background = bar()
            addView(iconButton(R.drawable.ic_close, getString(R.string.done_selecting)) { stopPicking() })
            addView(label(getString(R.string.recents_selected, set.size), size = 16f, bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(4), 0, 0, 0) }.weight())
            addView(iconButton(R.drawable.ic_select_all, getString(if (allPicked) R.string.select_none else R.string.select_all),
                if (allPicked) R.color.accent else R.color.text) { if (allPicked) set.clear() else set.addAll(all); render() })
        }
        val muted = picked.isNotEmpty() && picked.all { s.isMuted(it.thread) }
        val blocked = picked.isNotEmpty() && picked.all { s.isBlocked(it.address) }
        val one = picked.singleOrNull()
        fun done() { stopPicking() }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4)); background = bar()
            listOfNotNull(
                one?.let { c -> chatAction(R.drawable.ic_call, getString(R.string.call)) { app.dial(c.address, s.preferredSim(c.address)); done() } },
                chatAction(R.drawable.ic_mark_read, getString(R.string.action_read)) { picked.forEach { s.markRead(it.thread) }; done() },
                chatAction(if (muted) R.drawable.ic_notifications else R.drawable.ic_notifications_off, getString(if (muted) R.string.unmute else R.string.mute)) {
                    picked.forEach { s.setMuted(it.thread, !muted) }; done() },
                chatAction(R.drawable.ic_block, getString(if (blocked) R.string.unblock else R.string.block)) {
                    picked.map { it.address }.distinct().forEach { s.setBlocked(it, !blocked) }; done() },
                chatAction(R.drawable.ic_delete, getString(R.string.delete), R.color.bad) {
                    AlertDialog.Builder(this@MainActivity).setMessage(getString(R.string.delete_chats_confirm, picked.size))
                        .setPositiveButton(R.string.delete) { _, _ -> picked.forEach { s.deleteConversation(it.thread) }; done() }
                        .setNegativeButton(android.R.string.cancel, null).show()
                },
            ).forEach { a -> a.isEnabled = picked.isNotEmpty(); a.alpha = if (a.isEnabled) 1f else 0.38f; addView(a, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = SELECTION_TAG; setPadding(0, dp(4), 0, dp(6))
            addView(head, LinearLayout.LayoutParams(-1, dp(52)))
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        box.addView(wrap, 0)
    }

    /** An action of the conversations bar: its icon over its name, with a ripple; faded when nothing is picked. */
    private fun chatAction(iconId: Int, name: String, colorId: Int = R.color.text, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(6)); minimumHeight = dp(56)
        background = android.graphics.drawable.RippleDrawable(rippleColor(colorId), null, GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(col(R.color.text)) })
        addView(android.widget.ImageView(context).apply { setImageDrawable(icon(iconId, colorId, 22)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(label(name, colorId, 12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(dp(2), dp(4), dp(2), 0) }, LinearLayout.LayoutParams(-1, -2))
        isClickable = true; isFocusable = true; contentDescription = name
        setOnClickListener { if (isEnabled) onClick() }
    }

    /**
     * One conversation, as phones' messaging apps list them: the picture (a person icon for a number that is not a
     * contact), the name and two lines of the last text, the date and, under it, a round count of unread texts; unread
     * ones in bold and brighter. A thin line under each, from the text's start.
     */
    private fun conversationRow(c: app.tether.client.model.Conversation, last: Boolean): View {
        val s = app.state
        val picking = pickedChats != null
        val isPicked = pickedChats?.contains(c.thread) == true
        val name = s.name(c.address)
        val who = name ?: app.format(c.address)
        val unread = c.unread > 0
        val photo = s.photo(c.address)
        val pic = if (picking) android.widget.ImageView(this).apply {
            // Picking: a ring, filled with a check mark when picked.
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL
                if (isPicked) setColor(col(R.color.accent)) else setStroke(dp(2), col(R.color.muted)) }
            if (isPicked) setImageDrawable(icon(R.drawable.ic_check, R.color.background, 26))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) }
        } else if (name == null && photo == null) android.widget.ImageView(this).apply {
            setImageDrawable(icon(R.drawable.ic_person, R.color.muted, 26)); scaleType = android.widget.ImageView.ScaleType.CENTER
            background = round(R.color.surface, 48)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) }
        } else avatar(who, photo, 48).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14) }
        val preview = (if (c.last.box != 1) getString(R.string.you) + ": " else "") + c.last.body.replace('\n', ' ').take(160)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12)); minimumHeight = dp(76)
            if (isPicked) background = GradientDrawable().apply { cornerRadius = dp(14).toFloat()
                setColor(android.content.res.ColorStateList.valueOf(col(R.color.accent)).withAlpha(0x26).defaultColor) }
            addView(pic)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(if (s.isMuted(c.thread)) "🔕 $who" else who, R.color.text, 16f, bold = unread).apply {
                    setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, dp(2)) })
                // The text's own direction (Persian on the right, English on the left), as in the conversation.
                addView(label(preview, if (unread) R.color.text else R.color.muted, 14f).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, 0)
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                setPaddingRelative(dp(10), 0, 0, 0)
                addView(label(listDate(c.last.date), if (unread) R.color.accent else R.color.muted, 12f, bold = unread).apply {
                    maxLines = 1; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(-2, -2))   // its own width: the column fits the date
                if (unread) addView(label(if (c.unread > 99) "99+" else c.unread.toString(), R.color.background, 12f, bold = true).apply {
                    gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(dp(6), 0, dp(6), 0)
                    minWidth = dp(22); minimumWidth = dp(22); minHeight = dp(22); minimumHeight = dp(22)
                    background = GradientDrawable().apply { cornerRadius = dp(11).toFloat(); setColor(col(R.color.accent)) }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(-2, -2))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(line)
            // A grey line, clearly there but quieter than the text.
            if (!last) addView(View(context).apply { setBackgroundColor(android.content.res.ColorStateList.valueOf(col(R.color.muted)).withAlpha(0x55).defaultColor) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(4 + 48 + 14) })
            isSelected = isPicked
            contentDescription = listOfNotNull(if (isPicked) getString(R.string.recents_selected, 1) else null, who, if (unread) getString(R.string.unread_n, c.unread) else null,
                preview, listDate(c.last.date)).joinToString(", ")
        }
    }



    private fun openThread(address: String, thread: Long? = null) {
        startActivity(Intent(this, ThreadActivity::class.java).putExtra(ThreadActivity.EXTRA_ADDRESS, address)
            .putExtra(ThreadActivity.EXTRA_THREAD, thread ?: app.state.threadFor(address) ?: Long.MIN_VALUE))
    }

    private fun copy(t: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", t))
    }

    // ------------------------------------------------------------------ Link

    private enum class LinkSection(val title: Int) {
        NETWORK(R.string.link_network), PAIRING(R.string.link_pairing), DIAGNOSTICS(R.string.link_diagnostics)
    }

    private fun openLinkSection(section: LinkSection) {
        if (tab != TAB_LINK) select(TAB_LINK)
        linkSection = section
        revealLinkSection = true
        linkTab()
    }

    private fun showLinkOverflow(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, R.string.link_log, 0, R.string.link_log)
            menu.add(0, R.string.link_pairing, 1, R.string.link_pairing)
            setOnMenuItemClickListener {
                if (it.itemId == R.string.link_log) showLog() else {
                    openLinkSection(LinkSection.PAIRING)
                }
                true
            }
            show()
        }
    }

    private fun linkTab() {
        val reveal = revealLinkSection
        revealLinkSection = false
        val keepScroll = (content.getChildAt(0) as? ScrollView)?.takeIf { listTab == TAB_LINK }?.scrollY ?: 0
        list = null; listTab = TAB_LINK
        content.removeAllViews()
        val s = app.state
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20)) }
        val connected = app.link.isConnected
        val transport = app.transport.takeIf { connected && it != "none" }?.uppercase()
        val status = when (app.linkState) {
            BleCentral.State.CONNECTED -> getString(if (connected) R.string.link_connected else R.string.state_connecting)
            BleCentral.State.OFF -> getString(R.string.state_bt_off)
            BleCentral.State.UNPAIRED -> getString(R.string.state_unpaired)
            BleCentral.State.SEARCHING -> getString(R.string.state_searching)
            BleCentral.State.HANDSHAKING, BleCentral.State.CONNECTING -> getString(R.string.state_connecting)
        }
        val hostStatus = s.status
        val battery = hostStatus?.optInt("battery", -1)?.takeIf { connected && it in 0..100 }
            ?.let { getString(R.string.battery, it, if (hostStatus.optBoolean("charging")) getString(R.string.link_plugged) else "") }
        linkViews.heading(box, overflow = ::showLinkOverflow)
        box.addView(linkViews.host(app.identity.peerName ?: getString(R.string.android_phone), status, connected, battery, transport))
        box.addView(linkViews.metrics(
            app.link.roundTripMs?.takeIf { connected }?.let { getString(R.string.link_latency_ms, it) } ?: "—",
            transport ?: "—",
        ))
        box.addView(linkViews.card().apply {
            addView(linkViews.setting(R.drawable.ic_link_network, R.string.link_network,
                transport?.let { getString(R.string.link_network_transport, it) } ?: status) { openLinkSection(LinkSection.NETWORK) })
            addView(linkViews.separator())
            addView(linkViews.setting(R.drawable.ic_link_bluetooth, R.string.link_pairing,
                getString(R.string.link_pairing_subtitle)) { openLinkSection(LinkSection.PAIRING) })
            addView(linkViews.separator())
            addView(linkViews.setting(R.drawable.ic_link_diagnostics, R.string.link_diagnostics,
                getString(R.string.link_diagnostics_subtitle)) { openLinkSection(LinkSection.DIAGNOSTICS) })
        })
        if (connected && s.net == null && s.netPending == null) s.refreshNet()
        val details = when (linkSection) {
            LinkSection.NETWORK -> linkNetwork(connected, status)
            LinkSection.PAIRING -> linkPairing()
            LinkSection.DIAGNOSTICS -> linkDiagnostics(connected)
            null -> null
        }
        details?.let { box.addView(it) }
        if (app.identity.isPaired) {
            val card = linkViews.card(14)
            // The low-battery alert for the Android phone, chosen here: off or a level; it also alerts at every 5 % below.
            run {
                val n = app.notifier
                fun word(v: Int) = if (v == 0) getString(R.string.battery_alert_off) else "$v %"
                card.addView(row(
                    label(getString(R.string.battery_alert), size = 15f).weight(),
                    button(word(n.batteryAlert), Look.TONAL, iconId = R.drawable.ic_battery_alert) {
                        val choices = BatteryAlert.CHOICES
                        AlertDialog.Builder(this).setTitle(R.string.battery_alert)
                            .setSingleChoiceItems(choices.map(::word).toTypedArray(), choices.indexOf(n.batteryAlert)) { d, w -> n.batteryAlert = choices[w]; d.dismiss(); render() }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    }.apply { contentDescription = getString(R.string.battery_alert) + ", " + word(n.batteryAlert) },
                ))
                card.addView(label(getString(R.string.battery_alert_help), R.color.muted, 12f))
            }
            box.addView(card)
        }
        box.addView(callsCard())
        val sv = ScrollView(this).apply {
            setBackgroundColor(linkViews.background)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(this@MainActivity).apply {
                addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                    val width = minOf(right - left, dp(520))
                    if (width > 0 && box.layoutParams.width != width)
                        box.layoutParams = box.layoutParams.apply { this.width = width }
                }
            })
        }
        content.addView(sv)
        sv.post { sv.scrollTo(0, if (reveal && details != null) details.top else keepScroll) }
    }

    private fun linkPairing(): View = linkViews.card(14).apply {
        addView(label(getString(R.string.link_pairing), size = 18f, bold = true))
        if (!app.identity.isPaired) addView(label(getString(R.string.pair_guide), R.color.muted, 13f))
        addView(button(getString(if (app.identity.isPaired) R.string.pair_again else R.string.pair), if (app.identity.isPaired) Look.TONAL else Look.FILLED) {
            startActivity(Intent(this@MainActivity, PairActivity::class.java))
        })
        if (app.identity.isPaired) addView(button(getString(R.string.unpair), Look.TEXT, R.color.bad) {
            AlertDialog.Builder(this@MainActivity).setMessage(R.string.unpair_confirm)
                .setPositiveButton(R.string.unpair) { _, _ -> app.unpair() }
                .setNegativeButton(android.R.string.cancel, null).show()
        })
    }

    private fun linkNetwork(connected: Boolean, status: String): View {
        val s = app.state
        val phone = linkViews.card(14)
        val st = s.status.takeIf { connected && s.hasLiveStatus }
        val liveSims = if (st != null) s.sims else emptyList()
        phone.addView(label(getString(R.string.link_network), size = 18f, bold = true))
        app.transport.takeIf { connected && it != "none" }?.let {
            phone.addView(label(getString(R.string.link_network_transport, it.uppercase()), R.color.muted, 13f))
        }
        // The Android phone cannot bridge call audio (Android 8-10, or its software gives none): calls stay on it.
        when (st?.optJSONObject("helper")?.optString("audio")) {
            "unavailable" -> phone.addView(label(getString(R.string.audio_unavailable), R.color.warn, 13f))
            "blocked" -> phone.addView(label(getString(R.string.audio_blocked), R.color.warn, 13f))
        }
        // The Android phone's helper needs Wi-Fi to start, and without USB debugging stops with it (issues #26, #29).
        st?.optJSONObject("helper")?.let { h ->
            if (h.optString("why") == "wifi") phone.addView(label(getString(R.string.helper_why_wifi), R.color.warn, 13f))
            else if (h.optBoolean("needsWifi")) phone.addView(label(getString(R.string.helper_needs_wifi), R.color.muted, 13f))
        }
        for (sim in liveSims) {
            val line = listOfNotNull(sim.name.ifEmpty { getString(R.string.sim_n, sim.slot + 1) }, sim.carrier.takeIf { it.isNotEmpty() && it != sim.name },
                if (sim.hasService) getString(R.string.link_sim_signal, sim.signal.coerceIn(0, 4), sim.net.orEmpty()).trim() else getString(R.string.no_service), if (sim.roaming) "R" else null).joinToString(" · ")
            phone.addView(label(line, size = 14f))
        }
        st?.optJSONArray("clients")?.let { a ->
            val names = (0 until a.length()).map { a.getJSONObject(it) }.map { (if (it.optBoolean("self")) "● " else "○ ") + it.optString("name") + if (it.optBoolean("audio")) " ♪" else "" }
            if (names.size > 1) phone.addView(label(getString(R.string.clients, names.joinToString("  ")), R.color.muted, 13f))
        }
        // Switches
        if (connected) {
            val net = s.net
            val sw = phone
            sw.addView(label(getString(R.string.switches).uppercase(), R.color.accent, 12f, bold = true))
            val can = net?.optJSONObject("can")
            fun toggle(title: Int, what: String, on: Boolean?, shown: Boolean = true) {
                if (!shown) return
                sw.addView(Switch(this).apply {
                    text = getString(title); textSize = 16f; setTextColor(col(R.color.text)); isChecked = on == true
                    isEnabled = on != null && s.netPending == null
                    setPadding(0, dp(8), 0, dp(8))
                    setOnCheckedChangeListener { _, v -> if (v != on) s.setNet(what, v) }
                })
            }
            fun current(key: String) = net?.opt(key) as? Boolean
            toggle(R.string.mobile_data, "data", current("data"))
            toggle(R.string.hotspot, "hotspot", current("hotspot"), can?.optBoolean("hotspot", true) ?: true)
            toggle(R.string.bt_tethering, "bt", current("bt"), can?.optBoolean("bt", true) ?: true)
            toggle(R.string.airplane, "airplane", current("airplane"))
            toggle(R.string.share_vpn, "share", current("share"))
            if (net?.optBoolean("vpn") == true) sw.addView(label(getString(R.string.vpn_up), R.color.ok, 13f))
            net?.optJSONObject("proxy")?.optJSONArray("addrs")?.let { a ->
                if (net.optBoolean("share")) for (i in 0 until a.length()) a.getJSONObject(i).let { ad ->
                    sw.addView(label("${ad.optString("kind")}: ${ad.optString("ip")}:${net.optJSONObject("proxy")?.optInt("port")}  ·  ${ad.optString("pac")}", R.color.muted, 12f).apply { setTextIsSelectable(true) })
                }
            }
            val sims = liveSims
            if (sims.size > 1 && net?.has("dataSub") == true) {
                val dataSub = net?.optInt("dataSub", -1) ?: -1
                sw.addView(row(label(getString(R.string.data_sim), size = 16f).weight(), *sims.map { sim ->
                    button(sim.name.ifEmpty { getString(R.string.sim_n, sim.slot + 1) }, if (sim.sub == dataSub) Look.FILLED else Look.TONAL) { if (sim.sub != dataSub) s.setDataSim(sim.sub) }.apply { isEnabled = s.netPending == null; isSelected = sim.sub == dataSub }
                }.toTypedArray()))
            }
            for (sim in sims) {
                val mode = net?.optJSONArray("rat")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optInt("slot") == sim.slot }?.optString("mode") }
                sw.addView(row(label(getString(R.string.network_mode, sim.name.ifEmpty { getString(R.string.sim_n, sim.slot + 1) }), size = 16f).weight(),
                    button(mode ?: "–", Look.TONAL) {
                        val modes = arrayOf("5G", "4G", "3G", "2G")
                        AlertDialog.Builder(this).setItems(modes) { _, w -> s.setNetworkMode(sim.slot, modes[w]) }.show()
                    }.apply { isEnabled = s.netPending == null && !mode.isNullOrBlank() }))
            }
            s.netPending?.let { sw.addView(label(getString(R.string.waiting_for_phone), R.color.muted, 13f)) }
            s.netError?.let { sw.addView(label(if (it == "no_answer") getString(R.string.err_no_answer) else it, R.color.warn, 13f)) }

        }


        if (!connected) phone.addView(label(status, R.color.muted, 14f))
        return phone
    }

    private fun linkDiagnostics(connected: Boolean): View {
        val s = app.state

        val me = linkViews.card(14)
        me.addView(label(getString(R.string.this_phone).uppercase(), R.color.accent, 12f, bold = true))
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) me.addView(button(getString(R.string.allow_background), Look.OUTLINED) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        })
        if (!app.hasLinkPermissions()) me.addView(button(getString(R.string.grant_permissions)) { askPermissions() })
        app.link.roundTripMs?.let { me.addView(label(getString(R.string.round_trip, it), R.color.muted, 13f)) }
        me.addView(row(
            button(getString(R.string.report_title), Look.TONAL) { ReportUi.send(this) { ClientReport.build(app) } },
        ))
        me.addView(label(getString(R.string.report_hint), R.color.muted, 13f))
        me.addView(row(
            button(getString(R.string.link_log), Look.TONAL) { showLog() },
            *(if (connected) arrayOf(button(getString(R.string.selftest), Look.TONAL) { s.runSelftest() }) else emptyArray()),
        ))
        s.selftest?.let { t ->
            val m = t.optJSONArray("methods")
            me.addView(label((0 until (m?.length() ?: 0)).joinToString("\n") { i -> m!!.getJSONObject(i).let { "${if (it.optBoolean("ok")) "✓" else "✗"} ${it.optString("name")} ${it.optString("note")}" } }, R.color.muted, 12f))
        }
        return me
    }

    /**
     * "Use the Phone app for calls" and what it still needs: the calling accounts turned on in the Phone app, and the
     * Phone permission (to see which accounts are on, to dial from this app's keypad through the Phone app and to open
     * its call screen). Until an account is on, calls use this app's own call screen, which the card says. Also, for
     * that own screen: whether Android lets it wake the screen for an incoming call (full-screen notifications).
     */
    private fun callsCard(): View {
        val c = card()
        c.addView(label(getString(R.string.calls_title).uppercase(), R.color.accent, 12f, bold = true))
        val t = app.telecom
        c.addView(Switch(this).apply {
            text = getString(R.string.use_phone_app); textSize = 16f; setTextColor(col(R.color.text)); isChecked = t.usePhoneApp
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v ->
                t.usePhoneApp = v
                if (v && !t.hasPhonePermissions()) requestPermissions(t.phonePermissions, 2)
            }
        })
        if (!t.usePhoneApp) c.addView(label(getString(R.string.phone_app_off), R.color.muted, 13f))
        else {
            val accounts = t.phoneAccounts()
            val off = accounts.filter { !it.second }
            when {
                accounts.isEmpty() -> c.addView(label(getString(R.string.phone_app_turn_on), R.color.warn, 13f))
                off.isEmpty() -> c.addView(label(getString(R.string.phone_app_ready), R.color.ok, 13f))
                off.size == accounts.size -> c.addView(label(getString(R.string.phone_app_turn_on), R.color.warn, 13f))
                else -> c.addView(label(getString(R.string.phone_app_turn_on_some, off.joinToString { it.first }), R.color.warn, 13f))
            }
            if (off.isNotEmpty() || accounts.isEmpty())
                c.addView(button(getString(R.string.phone_app_open_accounts), iconId = R.drawable.ic_call) { t.openCallingAccounts(this) })
            if (!t.hasPhonePermissions()) {
                c.addView(label(getString(R.string.phone_app_permission), R.color.muted, 13f))
                c.addView(button(getString(R.string.allow_phone_calls), Look.OUTLINED) { requestPermissions(t.phonePermissions, 2) })
            }
        }
        // Calls in this app's own screen ring through a full-screen notification: from Android 14 that wakes the screen
        // only with "Full screen notifications" allowed for the app.
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            c.addView(label(getString(R.string.full_screen_off), R.color.warn, 13f).apply { setPadding(0, dp(10), 0, dp(2)) })
            c.addView(button(getString(R.string.full_screen_allow), Look.OUTLINED) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
                    .recoverCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            })
        }
        return c
    }

    private fun showLog() {
        val t = TextView(this).apply { text = app.journal.text; textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        AlertDialog.Builder(this).setTitle(R.string.link_log).setView(ScrollView(this).apply { addView(t) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", app.journal.text)) }
            .show()
    }

    companion object {
        private val KEY_SURFACE = Color.rgb(27, 28, 29)
        private val KEYPAD_ACCENT = Color.rgb(0, 199, 183)
        private val KEYPAD_SELECTED = Color.rgb(3, 99, 94)
        private const val SELECTION_TAG = "selection"
        const val EXTRA_TAB = "tab"; const val EXTRA_NUMBER = "number"
        const val TAB_RECENTS = 0; const val TAB_CONTACTS = 1; const val TAB_KEYPAD = 2; const val TAB_MESSAGES = 3; const val TAB_LINK = 4
        private val TAB_NAMES = listOf(R.string.tab_recents, R.string.tab_contacts, R.string.tab_keypad, R.string.tab_messages, R.string.tab_link)
        private val TAB_ICONS = listOf(R.drawable.ic_link_recents, R.drawable.ic_link_contacts, R.drawable.ic_link_keypad, R.drawable.ic_link_messages, R.drawable.ic_link_chain)
    }
}

internal data class KeypadMetrics(val keyWidth: Int, val keyHeight: Int, val rowGap: Int, val callSize: Int)

/** Dimensions in dp after reserving measured space above and around the dialer. */
internal fun keypadMetrics(availableWidth: Int, dialerHeight: Int, compact: Boolean = false): KeypadMetrics {
    val slot = ((availableWidth - 16) / 3 - 8).coerceAtLeast(1)
    val rowGap = if (compact) 3 else if (dialerHeight < 273) 5 else 8
    val callSize = if (compact) 48 else if (dialerHeight < 273) 52 else 60
    val heightBudget = (dialerHeight - callSize - 3 * rowGap) / 4
    val height = minOf(heightBudget, availableWidth / 5).coerceIn(48, 64)
    return KeypadMetrics(minOf(slot, height * 5 / 4), height, rowGap, callSize)
}

/** A Keypad-only choice. Resolving against fresh status never dials or persists a SIM. */
internal class KeypadSimSelection {
    private var temporary: Int? = null
    val hasTemporarySelection get() = temporary != null

    fun select(sub: Int) { temporary = sub }

    fun clear() { temporary = null }

    fun resolve(available: List<Int>, defaultSub: Int?): Int? {
        if (available.size < 2 || temporary !in available) clear()
        return temporary ?: defaultSub?.takeIf { it in available }
    }
}
