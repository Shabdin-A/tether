package app.tether.client.ui

import app.tether.client.report.ClientReport
import app.tether.client.report.ReportUi
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.tether.client.R
import app.tether.client.link.BleCentral
import app.tether.client.link.LinkService
import app.tether.client.model.Conversation
import app.tether.client.model.Search
import app.tether.client.model.Sim
import app.tether.client.model.UssdCodes
import app.tether.client.model.dialable
import app.tether.core.CallEntry
import app.tether.core.Contact
import app.tether.core.Phone
import org.json.JSONObject

/** Recents, Contacts (Favourites), Keypad, Messages and Link: the iPhone app's five tabs. */
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var linkBar: LinearLayout
    private val linkViews by lazy { LinkViews(this) }
    private var linkSection: LinkSection? = null
    private var revealLinkSection = false
    private var tab = TAB_KEYPAD
    private val listener: () -> Unit = { render() }
    private var query = ""
    private var number = StringBuilder()
    private var listTab = -1
    private lateinit var keypadView: KeypadView
    private lateinit var recentsView: RecentsView
    private lateinit var contactsView: ContactsView
    private var contactDetails: ContactDetailsView? = null
    private var detailedContactId: Long? = null
    private var deleteConfirmation: AlertDialog? = null
    private var shownDeleteToken: String? = null
    private var unregisterDetailsBack: (() -> Unit)? = null
    private val keypadSim = KeypadSimSelection()
    private var disconnectedKeypadStatus: JSONObject? = null
    private val messageRows = MessageRows()
    private val messageAccent = Color.rgb(0, 199, 183)
    private lateinit var messageEmpty: TextView
    private val messagePhotos = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount + 2 * key.length
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: if (app.identity.isPaired) TAB_RECENTS else TAB_LINK
        number.append(savedInstanceState?.getString(EXTRA_NUMBER).orEmpty())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(linkViews.background) }
        banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        root.addView(banner)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        linkBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            visibility = View.GONE
        }
        root.addView(linkBar)
        setContentView(root)
        fitSystemBars(root)
        // A recreated activity already has the user's latest keypad input.
        if (savedInstanceState == null) handleIntent(intent) else render()
        if (tab == TAB_CONTACTS && savedInstanceState?.containsKey("contactDetails") == true) {
            app.state.contacts.firstOrNull { it.id == savedInstanceState.getLong("contactDetails") }
                ?.let(::showContactDetails)
        }
        askPermissions()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        closeContactDetails()
        i?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it >= 0 }?.let { tab = it }
        i?.getStringExtra(EXTRA_NUMBER)?.let { number = StringBuilder(dialable(it)); tab = TAB_KEYPAD }
        listTab = -1
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putString(EXTRA_NUMBER, number.toString())
        detailedContactId?.let { outState.putLong("contactDetails", it) }
    }

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
        closeContactDetails()
        if (tab == TAB_RECENTS && i != TAB_RECENTS) app.state.markRecentsSeen()
        tab = i; query = ""; listTab = -1
        render()
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        val s = app.state
        val conversations = s.conversations
        if (!app.link.isConnected) {
            disconnectedKeypadStatus = s.status
            keypadSim.clear()
        } else if (keypadSim.hasTemporarySelection) {
            keypadSim.resolve(keypadSims().map { it.sub }, s.defaultSub)
        }
        linkBar.visibility = if (contactDetails == null) View.VISIBLE else View.GONE
        if (contactDetails == null) renderLinkBar(conversations.sumOf { it.unread }, s.missedCount)
        renderBanner()
        when (tab) {
            TAB_RECENTS -> recents()
            TAB_CONTACTS -> contacts()
            TAB_KEYPAD -> keypad()
            TAB_MESSAGES -> messages(conversations)
            else -> linkTab()
        }
    }

    private fun renderBanner() {
        banner.removeAllViews()
        app.state.problem?.let { p ->
            banner.addView(label(describe(p), R.color.warn, 14f).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { setColor(col(R.color.surface)); cornerRadius = dp(10).toFloat() }
                setOnClickListener { app.state.problem = null; render() }
            })
        }
        app.micWarning?.takeIf { app.identity.isPaired }?.let { w ->
            banner.addView(label(getString(micText(w)), R.color.warn, 14f).apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { setColor(col(R.color.surface)); cornerRadius = dp(10).toFloat() }
            })
        }
        app.calls.calls.firstOrNull()?.let { c ->
            banner.addView(label("☎ " + (c.name ?: app.format(c.number)) + " · " + stateWord(c.state), R.color.background, 14f).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(col(R.color.ok)); cornerRadius = dp(10).toFloat() }
                setOnClickListener { app.openCallScreen() }
            })
        }
        for (r in app.calls.remote.values) {
            banner.addView(row(
                label(getString(R.string.call_elsewhere, app.displayName(r.num), r.where ?: getString(R.string.another_device)), R.color.text, 14f).weight().apply {
                    isFocusable = true
                    setOnClickListener { app.openCallScreen(r.cid) }
                },
                pill(getString(R.string.move_here)) { app.calls.take(r.cid) },
            ).apply { setPadding(dp(12), dp(4), dp(4), dp(4)); background = GradientDrawable().apply { setColor(col(R.color.surface)); cornerRadius = dp(10).toFloat() } })
        }
    }

    private fun stateWord(s: String) = getString(when (s) {
        "ringing" -> R.string.call_ringing; "dialing", "connecting" -> R.string.call_dialing
        "holding" -> R.string.call_on_hold; else -> R.string.call_active
    })

    // ------------------------------------------------------------------ Recents

    private fun recents() {
        if (listTab != TAB_RECENTS) {
            listTab = TAB_RECENTS
            content.removeAllViews()
            recentsView = RecentsView()
            content.addView(recentsView, FrameLayout.LayoutParams(-1, -1))
        }
        recentsView.refresh()
    }

    private inner class RecentsView : ClientTabViewport(this@MainActivity) {
        private val owner = this@MainActivity
        private val panel = column()
        private val state = label("", R.color.muted, 15f).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(88)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = linkViews.shape(col(R.color.surface), 20)
        }
        private val rows = RecentRows()
        private val clear = pill(getString(R.string.clear_all), secondary = true) {
            AlertDialog.Builder(owner).setMessage(R.string.clear_recents_confirm)
                .setPositiveButton(R.string.clear_all) { _, _ -> app.state.clearRecents() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }

        init {
            linkViews.heading(panel, R.string.tab_recents, R.string.recents_subtitle, overflow = ::showLinkOverflow)
            val searchIcon = getDrawable(R.drawable.ic_messages_search)?.mutate()?.apply {
                setTint(col(R.color.muted))
                setBounds(0, 0, dp(20), dp(20))
            }
            val search = EditText(owner).apply {
                hint = getString(R.string.recents_search)
                setText(query)
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT
                textSize = 15f
                setTextColor(col(R.color.text))
                setHintTextColor(col(R.color.muted))
                background = linkViews.shape(col(R.color.surface), 24)
                minimumHeight = dp(48)
                setPaddingRelative(dp(12), dp(4), dp(12), dp(4))
                setCompoundDrawablesRelative(searchIcon, null, null, null)
                compoundDrawablePadding = dp(8)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString().orEmpty()
                        refresh()
                    }
                })
            }
            panel.addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(search, LinearLayout.LayoutParams(0, -2, 1f))
                clear.minimumHeight = dp(48)
                addView(clear, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            val history = ListView(owner).apply {
                background = linkViews.shape(col(R.color.surface), 20)
                clipToOutline = true
                divider = android.graphics.drawable.ColorDrawable(col(R.color.background))
                dividerHeight = dp(1)
                isVerticalScrollBarEnabled = false
                adapter = rows
                setOnItemClickListener { _, _, position, _ ->
                    rows.getItem(position).let { app.dial(it.num, it.sub ?: app.state.defaultSub) }
                }
                setOnItemLongClickListener { _, _, position, _ -> recentMenu(rows.getItem(position)); true }
            }
            val card = FrameLayout(owner).apply {
                addView(history, FrameLayout.LayoutParams(-1, -2))
                addView(state, FrameLayout.LayoutParams(-1, -2))
            }
            history.emptyView = state
            panel.addView(card, LinearLayout.LayoutParams(-1, 0, 1f))
            canvas.addView(panel, FrameLayout.LayoutParams(-1, -1))
            panel.requestFocus()
        }

        fun refresh() {
            val s = app.state
            clear.visibility = if (s.recents.isEmpty()) View.GONE else View.VISIBLE
            state.text = when {
                s.recents.isNotEmpty() -> getString(R.string.recents_no_matches)
                s.problem != null -> getString(R.string.recents_error)
                s.syncing -> getString(R.string.recents_loading)
                !app.link.isConnected -> getString(R.string.recents_offline)
                else -> getString(R.string.recents_empty)
            }
            rows.items = if (query.isBlank()) s.recents else s.recents.filter {
                Search.matches(query, listOfNotNull(it.name, s.name(it.num)), listOf(it.num))
            }
            rows.notifyDataSetChanged()
        }

        private inner class RecentRows : BaseAdapter() {
            var items: List<CallEntry> = emptyList()
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = getItem(position).id
            override fun hasStableIds() = true

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View =
                ((convertView as? RecentRow) ?: RecentRow()).apply { bind(getItem(position)) }
        }

        private inner class RecentRow : LinearLayout(owner) {
            private val direction = label("", size = 20f).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            private val name = label("", size = 16f, bold = true).apply {
                setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            }
            private val detail = label("", R.color.muted, 13f).apply { maxLines = 2 }
            private val stamp = label("", R.color.muted, 12f).apply { gravity = Gravity.END }
            private val identity = LinearLayout(owner).apply {
                orientation = LinearLayout.VERTICAL
                addView(name); addView(detail)
            }

            init {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
                addView(direction, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
                addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
                addView(stamp, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }

            fun bind(c: CallEntry) {
                val s = app.state
                val missed = c.type == 3
                val tint = col(if (missed) R.color.bad else R.color.accent)
                direction.text = when (c.type) { 1 -> "↙"; 2 -> "↗"; 3 -> "↙"; 5, 6 -> "×"; else -> "•" }
                direction.setTextColor(tint)
                direction.background = linkViews.shape(col(R.color.background), 18)
                name.text = s.name(c.num) ?: c.name ?: app.format(c.num)
                name.setTextColor(col(if (missed) R.color.bad else R.color.text))
                val kind = getString(when (c.type) {
                    1 -> R.string.call_in; 2 -> R.string.call_out; 3 -> R.string.call_missed
                    5 -> R.string.call_declined; 6 -> R.string.call_blocked; else -> R.string.call_other
                })
                detail.text = listOfNotNull(kind, s.simName(c.sub),
                    if (c.dur > 0) "%d:%02d".format(c.dur / 60, c.dur % 60) else null).joinToString(" · ")
                stamp.text = time(c.date)
            }
        }
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
        if (listTab != TAB_CONTACTS) {
            content.removeAllViews()
            contactsView = ContactsView(this, linkViews, ::showLinkOverflow,
                ::showContactDetails, ::contactCallMenu) { editContact(null) }
            content.addView(contactsView, FrameLayout.LayoutParams(-1, -1))
            listTab = TAB_CONTACTS
        }
        contactsView.submit(app.state.contacts)
        val details = contactDetails ?: return
        val contact = app.state.contacts.firstOrNull { it.id == detailedContactId }
        if (contact == null) {
            closeContactDetails()
            render()
            return
        }
        details.bind(contact, contact.phones.firstOrNull()?.let { app.state.isBlocked(it.n) } == true,
            app.state.defaultContactPhone(contact))
        renderPhoneDeleteConfirmation(contact)
    }

    private fun renderPhoneDeleteConfirmation(contact: Contact) {
        val pending = app.state.phoneDeletion
        val token = pending?.takeIf { it.contact == contact.id }?.token
        if (shownDeleteToken == token) return
        deleteConfirmation?.dismiss()
        shownDeleteToken = token
        deleteConfirmation = if (token == null) null else AlertDialog.Builder(this)
            .setTitle(R.string.contacts_delete_number)
            .setMessage(getString(R.string.contacts_delete_confirm, app.format(pending?.number.orEmpty())))
            .setNegativeButton(android.R.string.cancel) { _, _ -> app.state.cancelContactPhoneDeletion() }
            .setPositiveButton(R.string.contacts_delete_number) { _, _ -> app.state.confirmContactPhoneDeletion(token) }
            .setOnCancelListener { app.state.cancelContactPhoneDeletion() }
            .show()
    }

    private fun showContactDetails(contact: Contact) {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(contactsView.windowToken, 0)
        detailedContactId = contact.id
        val details = ContactDetailsView(this, linkViews,
            back = { closeContactDetails(); render() },
            call = ::contactCallMenu,
            message = ::contactMessageMenu,
            edit = { editContact(it) },
            block = { c -> c.phones.firstOrNull()?.let { app.state.setBlocked(it.n, !app.state.isBlocked(it.n)) } },
            favourite = { app.state.setStarred(it.id, !it.starred) },
            more = ::contactMenu,
            phoneActions = ::contactPhoneMenu,
            setDefault = { chooseContactNumber(it, saveOnly = true) })
        contactDetails = details
        contactsView.visibility = View.GONE
        content.addView(details, FrameLayout.LayoutParams(-1, -1))
        if (Build.VERSION.SDK_INT >= 33) {
            val dispatcher = onBackInvokedDispatcher
            val callback = android.window.OnBackInvokedCallback { closeContactDetails(); render() }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            unregisterDetailsBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
        render()
    }

    private fun closeContactDetails() {
        val details = contactDetails ?: return
        app.state.cancelContactPhoneDeletion()
        deleteConfirmation?.dismiss()
        deleteConfirmation = null
        shownDeleteToken = null
        unregisterDetailsBack?.invoke()
        unregisterDetailsBack = null
        content.removeView(details)
        contactDetails = null
        detailedContactId = null
        contactsView.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (contactDetails == null) super.onBackPressed() else {
            closeContactDetails()
            render()
        }
    }

    private fun callContactNumber(number: String) = app.dial(number, app.state.defaultSub)

    private fun contactCallMenu(c: Contact) {
        val contact = app.state.contacts.singleOrNull { it.id == c.id } ?: return
        val direct = contact.phones.singleOrNull() ?: app.state.defaultContactPhone(contact)
        if (direct != null) callContactNumber(direct.n)
        else if (contact.phones.isNotEmpty()) chooseContactNumber(contact, saveOnly = false)
    }

    private fun chooseContactNumber(contact: Contact, saveOnly: Boolean) {
        showContactNumberPicker(this, linkViews, contact, app.state.defaultContactPhone(contact), saveOnly) { phone, save ->
            val current = app.state.contacts.singleOrNull { it.id == contact.id }
            if (current?.phones?.singleOrNull { it.n == phone.n } == null ||
                ((saveOnly || save) && !app.state.setDefaultContactPhone(contact.id, phone.n))) {
                Toast.makeText(this, R.string.contacts_number_changed, Toast.LENGTH_SHORT).show()
            } else if (!saveOnly) {
                callContactNumber(phone.n)
            }
        }
    }

    private fun contactMessageMenu(c: Contact) {
        if (c.phones.size == 1) {
            openThread(c.phones.single().n)
            return
        }
        AlertDialog.Builder(this).setTitle(c.name)
            .setItems(c.phones.map { getString(R.string.message_number, app.format(it.n)) }.toTypedArray()) { _, index ->
                openThread(c.phones[index].n)
            }.show()
    }

    private fun contactPhoneMenu(contact: Contact, phone: Phone) {
        val formatted = app.format(phone.n)
        val isDefault = app.state.defaultContactPhone(contact) == phone
        val actions = mutableListOf(
            R.drawable.ic_contact_details_star to if (isDefault) R.string.contacts_clear_default else R.string.contacts_set_default,
            R.drawable.ic_contact_details_edit to R.string.contacts_edit_number,
            R.drawable.ic_contacts_copy to R.string.contacts_copy_number,
            R.drawable.ic_contacts_call to R.string.call,
            R.drawable.ic_link_messages to R.string.contacts_detail_message,
        )
        if (app.state.canDeleteContactPhone && app.link.isConnected && app.state.phoneDeletion == null) {
            actions.add(R.drawable.ic_contacts_delete to R.string.contacts_delete_number)
        }
        showContactNumberOptions(this, linkViews, formatted, actions) { index ->
            val current = app.state.contacts.singleOrNull { it.id == contact.id }
            val position = current?.phones?.indexOf(phone) ?: -1
            if (current == null || position < 0) {
                Toast.makeText(this, R.string.contacts_number_changed, Toast.LENGTH_SHORT).show()
            } else when (index) {
                0 -> if (!app.state.setDefaultContactPhone(current.id, if (isDefault) null else phone.n)) {
                    Toast.makeText(this, R.string.contacts_number_changed, Toast.LENGTH_SHORT).show()
                }
                1 -> editContact(current, phoneIndex = position)
                2 -> copy(phone.n)
                3 -> callContactNumber(phone.n)
                4 -> openThread(phone.n)
                5 -> app.state.prepareContactPhoneDeletion(current.id, phone.n)
            }
        }
    }

    private fun contactMenu(c: Contact) {
        val s = app.state
        val opts = ArrayList<Pair<String, () -> Unit>>()
        for (p in c.phones) {
            opts += getString(R.string.call_number, app.format(p.n)) to { callContactNumber(p.n) }
            opts += getString(R.string.message_number, app.format(p.n)) to { openThread(p.n) }
        }
        opts += getString(if (c.starred) R.string.unfavourite else R.string.favourite) to { s.setStarred(c.id, !c.starred) }
        opts += getString(R.string.edit) to { editContact(c) }
        c.phones.firstOrNull()?.let { p -> val b = s.isBlocked(p.n); opts += getString(if (b) R.string.unblock else R.string.block) to { s.setBlocked(p.n, !b) } }
        AlertDialog.Builder(this).setTitle(c.name).setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }.show()
    }

    private fun editContact(c: Contact?, prefill: String? = null, phoneIndex: Int? = null) {
        startActivity(Intent(this, EditContactActivity::class.java).apply {
            c?.let { putExtra(EditContactActivity.CONTACT_ID, it.id) }
            putExtra(EditContactActivity.PREFILL, prefill)
            phoneIndex?.let { putExtra(EditContactActivity.PHONE_INDEX, it) }
        })
    }

    // ------------------------------------------------------------------ Keypad

    private fun openUssd(code: String, sub: Int?) {
        startActivity(Intent(this, UssdActivity::class.java).putExtra(UssdActivity.EXTRA_CODE, code).apply { sub?.let { putExtra(UssdActivity.EXTRA_SUB, it) } }
            .putExtra(UssdActivity.EXTRA_SEND, UssdCodes.valid(code)))
    }

    private fun keypadSims(): List<Sim> =
        if (app.link.isConnected && app.state.status !== disconnectedKeypadStatus) app.state.sims else emptyList()

    private fun keypad() {
        if (listTab != TAB_KEYPAD) {
            listTab = TAB_KEYPAD
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
                if (app.state.canUssd && UssdCodes.valid(value)) openUssd(value, selected)
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
            column.addView(ussdButton)
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

    // ------------------------------------------------------------------ Messages

    private fun messages(conversations: List<Conversation>) {
        messageRows.items = app.state.searchConversations(conversations, query)
        if (listTab != TAB_MESSAGES) showMessages()
        messageEmpty.setText(when {
            query.isNotBlank() -> R.string.messages_no_results
            app.state.syncing -> R.string.messages_loading
            !app.link.isConnected -> R.string.messages_disconnected
            else -> R.string.messages_empty
        })
        messageRows.notifyDataSetChanged()
    }

    private fun showMessages() {
        val composeSize = dp(56)
        val composeBottomMargin = dp(10)
        val viewport = ClientTabViewport(this)
        val column = viewport.column()
        linkViews.heading(column, R.string.tab_messages, R.string.messages_subtitle, overflow = ::showLinkOverflow)
        val searchIcon = getDrawable(R.drawable.ic_messages_search)?.mutate()?.apply {
            setTint(Color.rgb(196, 199, 201))
            setBounds(0, 0, dp(20), dp(20))
        }
        val search = EditText(this).apply {
            hint = getString(R.string.messages_search)
            setText(query)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(196, 199, 201))
            background = linkViews.shape(Color.rgb(30, 31, 32), 24)
            minimumHeight = dp(48)
            setPaddingRelative(dp(14), dp(5), dp(14), dp(5))
            setCompoundDrawablesRelative(searchIcon, null, null, null)
            compoundDrawablePadding = dp(10)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    render()
                }
            })
        }
        val starred = iconButton(R.drawable.ic_star_border, getString(R.string.starred_messages), R.color.warn) {
            startActivity(Intent(this, StarredActivity::class.java))
        }
        column.addView(row(search.weight(), starred), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val card = FrameLayout(this).apply {
            background = linkViews.shape(Color.rgb(21, 22, 23), 20)
            clipToOutline = true
        }
        val messagesList = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(44, 45, 46))
            dividerHeight = dp(1)
            isVerticalScrollBarEnabled = false
            setPadding(dp(12), 0, dp(12), 0)
            adapter = messageRows
            setOnItemClickListener { _, _, pos, _ ->
                messageRows.getItem(pos).let { openThread(it.address, it.thread) }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                messageRows.getItem(pos).let { threadMenu(it.thread, it.address) }
                true
            }
        }
        messageEmpty = label("", R.color.muted, 14f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(messagesList, FrameLayout.LayoutParams(-1, -1))
        card.addView(messageEmpty, FrameLayout.LayoutParams(-1, -1))
        messagesList.emptyView = messageEmpty
        column.addView(card, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            bottomMargin = composeSize + composeBottomMargin + dp(6)
        })
        val compose = ImageView(this).apply {
            background = linkViews.shape(messageAccent, 28)
            setImageResource(R.drawable.ic_messages_compose)
            setColorFilter(Color.rgb(9, 10, 11))
            setPadding(dp(15), dp(15), dp(15), dp(15))
            contentDescription = getString(R.string.new_message)
            isFocusable = true
            setOnClickListener { composeMessage() }
        }
        viewport.canvas.apply {
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(compose, FrameLayout.LayoutParams(composeSize, composeSize, Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(20)
                bottomMargin = composeBottomMargin
            })
        }
        content.removeAllViews()
        content.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        listTab = TAB_MESSAGES
        column.requestFocus()
    }

    private fun composeMessage() {
        startActivity(Intent(this, NewMessageActivity::class.java))
    }

    private inner class MessageRows : BaseAdapter() {
        var items: List<Conversation> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = getItem(position).thread
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            (convertView as? MessageRow ?: MessageRow()).apply { bind(getItem(position)) }
    }

    private inner class MessageRow : LinearLayout(this@MainActivity) {
        private val initial = (avatar("", null) as TextView).apply {
            background = round(R.color.surface, 40).apply { setColor(Color.rgb(57, 91, 113)) }
            setTextColor(Color.WHITE)
        }
        private val picture = ImageView(context).apply {
            clipToOutline = true
            background = round(R.color.surface, 40)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        private val face = FrameLayout(context).apply {
            addView(initial, FrameLayout.LayoutParams(-1, -1))
            addView(picture, FrameLayout.LayoutParams(-1, -1))
        }
        private val title = label("", size = 16f, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        private val subtitle = label("", R.color.muted, 13f).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        private val date = label("", R.color.muted, 12f)
        private val badge = label("", size = 11f, bold = true).apply {
            background = linkViews.shape(messageAccent, 12)
            setTextColor(Color.rgb(9, 10, 11))
            gravity = Gravity.CENTER
            minimumWidth = dp(20)
            minimumHeight = dp(20)
            setPaddingRelative(dp(4), 0, dp(4), 0)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(62)
            setPadding(dp(2), dp(6), dp(2), dp(6))
            addView(face, LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title)
                addView(subtitle)
            }, LayoutParams(0, -2, 1f))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.END
                setPaddingRelative(dp(8), 0, 0, 0)
                addView(date, LayoutParams(-2, -2))
                addView(badge, LayoutParams(-2, -2))
            })
        }

        fun bind(conversation: Conversation) {
            val state = app.state
            val name = state.name(conversation.address) ?: app.format(conversation.address)
            val photo = state.photo(conversation.address)
            val bitmap = photo?.let { key ->
                messagePhotos.get(key) ?: runCatching {
                    val bytes = Base64.decode(key, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()?.also { messagePhotos.put(key, it) }
            }
            picture.setImageBitmap(bitmap)
            picture.visibility = if (bitmap != null) VISIBLE else GONE
            initial.visibility = if (bitmap == null) VISIBLE else GONE
            initial.text = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
            val muted = if (state.isMuted(conversation.thread)) "🔕 " else ""
            title.text = muted + name
            badge.text = conversation.unread.toString()
            badge.contentDescription = resources.getQuantityString(R.plurals.messages_unread, conversation.unread, conversation.unread)
            badge.visibility = if (conversation.unread > 0) VISIBLE else GONE
            val prefix = if (conversation.last.box == 1) "" else getString(R.string.you) + ": "
            subtitle.text = (prefix + conversation.last.body.replace('\n', ' ')).take(120)
            date.text = time(conversation.last.date)
        }
    }

    private fun threadMenu(thread: Long, address: String) {
        val s = app.state
        val muted = s.isMuted(thread); val blocked = s.isBlocked(address)
        val opts = listOf(getString(R.string.call), getString(if (muted) R.string.unmute else R.string.mute),
            getString(if (blocked) R.string.unblock else R.string.block), getString(R.string.mark_read), getString(R.string.delete_conversation))
        AlertDialog.Builder(this).setTitle(s.name(address) ?: app.format(address)).setItems(opts.toTypedArray()) { _, w ->
            when (w) {
                0 -> app.dial(address, s.preferredSim(address))
                1 -> s.setMuted(thread, !muted)
                2 -> s.setBlocked(address, !blocked)
                3 -> s.markRead(thread)
                4 -> AlertDialog.Builder(this).setMessage(R.string.delete_conversation_confirm)
                    .setPositiveButton(R.string.delete) { _, _ -> s.deleteConversation(thread) }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
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
                    if (tab != TAB_LINK) select(TAB_LINK)
                    openLinkSection(LinkSection.PAIRING)
                }
                true
            }
            show()
        }
    }

    private fun renderLinkBar(unread: Int, missed: Int) {
        linkBar.removeAllViews()
        val items = listOf(
            Triple(TAB_LINK, R.string.tab_link, R.drawable.ic_link_chain),
            Triple(TAB_MESSAGES, R.string.tab_messages, R.drawable.ic_link_messages),
            Triple(TAB_KEYPAD, R.string.tab_keypad, R.drawable.ic_link_keypad),
            Triple(TAB_CONTACTS, R.string.tab_contacts, R.drawable.ic_link_contacts),
            Triple(TAB_RECENTS, R.string.tab_recents, R.drawable.ic_link_recents),
        )
        for ((destination, title, icon) in items) {
            val badge = when (destination) {
                TAB_MESSAGES -> if (tab == TAB_LINK) unread else 0
                TAB_RECENTS -> missed
                else -> 0
            }
            val name = getString(title) + if (badge > 0) " ($badge)" else ""
            val button = linkViews.tab(icon, name, destination == tab) { select(destination) }
            linkBar.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun linkTab() {
        val keepScroll = (content.getChildAt(0) as? ScrollView)?.takeIf { listTab == TAB_LINK }?.scrollY ?: 0
        listTab = TAB_LINK
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
        val sv = ScrollView(this).apply {
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
        val reveal = revealLinkSection
        revealLinkSection = false
        sv.post { sv.scrollTo(0, if (reveal && details != null) details.top else keepScroll) }
    }

    private fun linkPairing(): View = linkViews.card(14).apply {
        addView(label(getString(R.string.link_pairing), size = 18f, bold = true))
        if (!app.identity.isPaired) addView(label(getString(R.string.pair_guide), R.color.muted, 13f))
        addView(pill(getString(if (app.identity.isPaired) R.string.pair_again else R.string.pair)) {
            startActivity(Intent(this@MainActivity, PairActivity::class.java))
        })
        if (app.identity.isPaired) addView(pill(getString(R.string.unpair), secondary = true, colorId = R.color.bad) {
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
                    pill(sim.name.ifEmpty { getString(R.string.sim_n, sim.slot + 1) }, secondary = sim.sub != dataSub) { if (sim.sub != dataSub) s.setDataSim(sim.sub) }.apply { isEnabled = s.netPending == null }
                }.toTypedArray()))
            }
            for (sim in sims) {
                val mode = net?.optJSONArray("rat")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optInt("slot") == sim.slot }?.optString("mode") }
                sw.addView(row(label(getString(R.string.network_mode, sim.name.ifEmpty { getString(R.string.sim_n, sim.slot + 1) }), size = 16f).weight(),
                    pill(mode ?: "–", secondary = true) {
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
        if (!pm.isIgnoringBatteryOptimizations(packageName)) me.addView(pill(getString(R.string.allow_background), secondary = true) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        })
        if (!app.hasLinkPermissions()) me.addView(pill(getString(R.string.grant_permissions)) { askPermissions() })
        app.link.roundTripMs?.let { me.addView(label(getString(R.string.round_trip, it), R.color.muted, 13f)) }
        me.addView(row(
            pill(getString(R.string.report_title)) { ReportUi.send(this) { ClientReport.build(app) } },
        ))
        me.addView(label(getString(R.string.report_hint), R.color.muted, 13f))
        me.addView(row(
            pill(getString(R.string.link_log), secondary = true) { showLog() },
            *(if (connected) arrayOf(pill(getString(R.string.selftest), secondary = true) { s.runSelftest() }) else emptyArray()),
        ))
        s.selftest?.let { t ->
            val m = t.optJSONArray("methods")
            me.addView(label((0 until (m?.length() ?: 0)).joinToString("\n") { i -> m!!.getJSONObject(i).let { "${if (it.optBoolean("ok")) "✓" else "✗"} ${it.optString("name")} ${it.optString("note")}" } }, R.color.muted, 12f))
        }
        return me
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
        const val EXTRA_TAB = "tab"; const val EXTRA_NUMBER = "number"
        const val TAB_RECENTS = 0; const val TAB_CONTACTS = 1; const val TAB_KEYPAD = 2; const val TAB_MESSAGES = 3; const val TAB_LINK = 4
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
