package app.tether.client.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.util.Linkify
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
import android.widget.TextView
import app.tether.client.R
import app.tether.client.model.NumberShape
import app.tether.core.Sms
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One conversation: bubbles, read state, send from either SIM, copy / delete, call, mute, block. */
class ThreadActivity : Activity() {
    private var thread = Long.MIN_VALUE
    private lateinit var address: String
    private lateinit var list: ListView
    private lateinit var input: EditText
    private lateinit var title: android.widget.TextView
    private lateinit var simButton: android.widget.TextView
    private lateinit var portrait: FrameLayout
    private var avatarName: String? = null
    private var avatarPhoto: String? = null
    private val messages = ThreadRows()
    private var sub: Int? = null
    private var lastCount = -1
    private var focusMessage: Long? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(EXTRA_ADDRESS) ?: run { finish(); return }
        thread = intent.getLongExtra(EXTRA_THREAD, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: app.state.threadFor(address) ?: Long.MIN_VALUE
        sub = app.state.preferredSim(address)
        focusMessage = intent.getLongExtra(EXTRA_FOCUS, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        // A text sent into a new conversation gets its real thread id with the provider row: follow it.
        app.state.onThreadMoved = { from, to -> if (from == thread) thread = to }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
        }
        val viewport = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                column.layoutParams.width = minOf(width.coerceAtLeast(0), dp(760))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            setBackgroundColor(BACKGROUND)
            addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        }
        addHeader(column)
        list = ListView(this).apply {
            divider = null
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            adapter = messages
            setOnItemLongClickListener { _, _, pos, _ ->
                val sms = (messages.getItem(pos) as? ThreadRow.Message)?.sms
                if (sms == null) false else { bubbleMenu(sms); true }
            }
        }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(separator())
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(8))
        }
        simButton = pill("", secondary = true) { pickSim() }.apply { minimumHeight = dp(48) }
        composer.addView(simButton, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        val bar = row().apply { setPadding(0, dp(7), 0, 0) }
        input = EditText(this).apply {
            hint = getString(R.string.text_message)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textSize = 16f
            maxLines = 5
            minimumHeight = dp(48)
            background = surface(COMPOSER, 26)
            setPaddingRelative(dp(16), dp(9), dp(16), dp(9))
            setText(app.state.draft(address))
        }
        bar.addView(input.weight())
        bar.addView(action(R.drawable.ic_thread_send, R.string.send, onClick = ::send).apply {
            background = surface(ACCENT, 24)
            setColorFilter(INK)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        composer.addView(bar)
        column.addView(composer)
        setContentView(viewport)
        fitSystemBars(viewport)
        column.requestFocus()
    }

    private fun addHeader(column: LinearLayout) {
        val brand = row().apply { setPaddingRelative(dp(16), dp(8), dp(12), 0) }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_link_chain)
            setColorFilter(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
        brand.addView(label(getString(R.string.link_brand), size = 21f, bold = true))
        column.addView(brand)
        val header = row().apply { setPaddingRelative(dp(4), dp(7), dp(4), dp(7)) }
        header.addView(action(R.drawable.ic_thread_back, R.string.thread_back) { finish() })
        portrait = FrameLayout(this)
        header.addView(portrait, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = label("", size = 17f, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        identity.addView(title)
        identity.addView(label(app.format(address), size = 13f).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_LTR
        })
        header.addView(identity.weight())
        header.addView(action(R.drawable.ic_thread_call, R.string.call) { app.dial(address, sub) })
        header.addView(action(R.drawable.ic_link_overflow, R.string.thread_options, onClick = ::menu))
        column.addView(header)
        column.addView(separator())
    }

    private fun action(icon: Int, description: Int, onClick: () -> Unit) = ImageButton(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = android.util.TypedValue().let {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            getDrawable(it.resourceId)
        }
        contentDescription = getString(description)
        setOnClickListener { onClick() }
    }

    private fun separator() = View(this).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun surface(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    override fun onResume() {
        super.onResume()
        app.addListener(listener)
        app.appVisible = true
        render()
    }

    override fun onPause() {
        app.state.setDraft(input.text.toString(), address)
        app.removeListener(listener)
        app.appVisible = false
        if (app.state.openThread == thread) app.state.openThread = null
        super.onPause()
    }

    override fun onDestroy() { if (app.state.onThreadMoved != null) app.state.onThreadMoved = null; super.onDestroy() }

    private fun render() {
        val s = app.state
        s.openThread = thread
        val name = s.name(address).orEmpty()
        val photo = s.photo(address)
        title.text = name.ifBlank { getString(R.string.thread_contact) }
        if (avatarName != name || avatarPhoto != photo) {
            portrait.removeAllViews()
            portrait.addView(if (name.isNotBlank() || photo != null) avatar(name, photo, 44) else ImageView(this).apply {
                background = surface(COMPOSER, 22)
                setImageResource(R.drawable.ic_link_contacts)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }, FrameLayout.LayoutParams(-1, -1))
            avatarName = name
            avatarPhoto = photo
        }
        val sims = s.sims
        simButton.visibility = if (sims.size > 1) View.VISIBLE else View.GONE
        simButton.text = sims.firstOrNull { it.sub == sub }?.let { it.name.ifEmpty { getString(R.string.sim_n, it.slot + 1) } } ?: getString(R.string.sim)
        val msgs = if (thread == Long.MIN_VALUE) emptyList() else s.messages(thread)
        s.markRead(thread)
        val atEnd = list.lastVisiblePosition >= list.count - 2
        messages.update(msgs)
        val focusPosition = focusMessage?.let(messages::positionOf) ?: -1
        if (focusPosition >= 0) {
            focusMessage = null
            list.setSelectionFromTop(focusPosition, list.height / 4)
        } else if (msgs.isNotEmpty() && (atEnd || msgs.size != lastCount)) list.setSelection(messages.count - 1)
        lastCount = msgs.size
    }

    private sealed interface ThreadRow {
        data class Day(val date: LocalDate, val timestamp: Long) : ThreadRow
        data class Message(val sms: Sms) : ThreadRow
    }

    private inner class ThreadRows : BaseAdapter() {
        private var source: List<Sms> = emptyList()
        private var zone = ZoneId.systemDefault()
        private var today = LocalDate.now(zone)
        private var rows: List<ThreadRow> = emptyList()

        fun update(items: List<Sms>) {
            val currentZone = ZoneId.systemDefault()
            val currentDay = LocalDate.now(currentZone)
            if (items != source || zone != currentZone || today != currentDay) {
                source = items
                zone = currentZone
                today = currentDay
                rows = buildList {
                    var previous: LocalDate? = null
                    for (sms in items) {
                        val day = Instant.ofEpochMilli(sms.date).atZone(zone).toLocalDate()
                        if (day != previous) add(ThreadRow.Day(day, sms.date))
                        add(ThreadRow.Message(sms))
                        previous = day
                    }
                }
            }
            notifyDataSetChanged()
        }

        fun positionOf(id: Long) = rows.indexOfFirst { it is ThreadRow.Message && it.sms.id == id }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = (rows[position] as? ThreadRow.Message)?.sms?.id ?: Long.MIN_VALUE
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows[position] is ThreadRow.Message
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is ThreadRow.Day) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val item = rows[position]) {
            is ThreadRow.Day -> (convertView as? FrameLayout ?: FrameLayout(this@ThreadActivity).apply {
                setPadding(0, dp(9), 0, dp(9))
                addView(label("", size = 12f).apply {
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    background = surface(COMPOSER, 14)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }).apply {
                (getChildAt(0) as TextView).text = if (item.date == today) getString(R.string.thread_today)
                else DateUtils.formatDateTime(context, item.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
            }
            is ThreadRow.Message -> (convertView as? BubbleRow ?: BubbleRow()).apply { bind(item.sms) }
        }
    }

    private inner class BubbleRow : FrameLayout(this@ThreadActivity) {
        private val body = label("", size = 16f).apply {
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        private val metadata = label("", size = 11f)
        private val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            addView(body, LinearLayout.LayoutParams(-2, -2))
            addView(metadata, LinearLayout.LayoutParams(-2, -2))
        }
        private var boundBody: String? = null
        private lateinit var boundSms: Sms

        init {
            setPadding(0, dp(5), 0, dp(5))
            addView(bubble, FrameLayout.LayoutParams(-2, -2))
            body.setOnLongClickListener { bubbleMenu(boundSms); true }
        }

        @Suppress("RtlHardcoded") // The approved thread puts incoming left and outgoing right in both locales.
        fun bind(m: Sms) {
            boundSms = m
            val mine = m.box != 1
            val gravity = if (mine) Gravity.RIGHT else Gravity.LEFT
            (bubble.layoutParams as FrameLayout.LayoutParams).gravity = gravity
            (metadata.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            bubble.background = surface(if (mine) ACCENT else CHARCOAL, 20)
            body.setTextColor(if (mine) INK else Color.WHITE)
            body.setLinkTextColor(if (mine) INK else ACCENT)
            val state = when (m.box) { 4, 6 -> getString(R.string.sending); 5 -> getString(R.string.not_delivered); else -> null }
            val clock = DateUtils.formatDateTime(this@ThreadActivity, m.date, DateUtils.FORMAT_SHOW_TIME)
            metadata.text = listOfNotNull(clock, app.state.simName(m.sub), state).joinToString(" · ")
            metadata.setTextColor(if (m.box == 5) FAILURE else if (mine) INK else MUTED)
            if (boundBody == m.body) return
            boundBody = m.body
            // Links, e-mail addresses and phone numbers, except "numbers" that are dates, versions or amounts.
            body.text = android.text.SpannableString(m.body).also { sp ->
                Linkify.addLinks(sp, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS or Linkify.EMAIL_ADDRESSES)
                for (link in sp.getSpans(0, sp.length, android.text.style.URLSpan::class.java)) {
                    if (link.url.startsWith("tel:") && NumberShape.isNotAPhoneNumber(sp.substring(sp.getSpanStart(link), sp.getSpanEnd(link)))) sp.removeSpan(link)
                }
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec)
            val limit = (minOf((available * 0.78).toInt(), dp(460)) - bubble.paddingLeft - bubble.paddingRight).coerceAtLeast(1)
            if (body.maxWidth != limit) body.maxWidth = limit
            if (metadata.maxWidth != limit) metadata.maxWidth = limit
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun bubbleMenu(m: Sms) {
        val starred = app.state.isStarred(m.id)
        val opts = mutableListOf(getString(R.string.copy), getString(R.string.delete),
            getString(if (starred) R.string.unstar else R.string.star))
        if (m.box == 5) opts += getString(R.string.send_again)
        AlertDialog.Builder(this).setItems(opts.toTypedArray()) { _, w ->
            when (w) {
                0 -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", m.body))
                1 -> app.state.deleteMessages(listOf(m.id))
                2 -> app.state.setStarred(listOf(m.id), !starred)
                3 -> { app.state.deleteMessages(listOf(m.id)); app.state.sendSms(address, m.body, sub) }
            }
        }.show()
    }

    private fun pickSim() {
        val sims = app.state.sims
        AlertDialog.Builder(this).setItems(sims.map { it.name.ifEmpty { getString(R.string.sim_n, it.slot + 1) } }.toTypedArray()) { _, w ->
            sub = sims[w].sub; app.state.setPreferredSim(sub, address); render()
        }.show()
    }

    private fun send() {
        val text = input.text.toString()
        if (text.isBlank()) return
        app.state.sendSms(address, text, sub)
        if (thread == Long.MIN_VALUE) thread = app.state.threadFor(address) ?: thread
        input.setText("")
        render()
    }

    private fun menu() {
        val s = app.state
        val muted = thread != Long.MIN_VALUE && s.isMuted(thread); val blocked = s.isBlocked(address)
        val opts = listOf(getString(if (muted) R.string.unmute else R.string.mute), getString(if (blocked) R.string.unblock else R.string.block),
            getString(R.string.delete_conversation))
        AlertDialog.Builder(this).setItems(opts.toTypedArray()) { _, w ->
            when (w) {
                0 -> if (thread != Long.MIN_VALUE) s.setMuted(thread, !muted)
                1 -> s.setBlocked(address, !blocked)
                2 -> AlertDialog.Builder(this).setMessage(R.string.delete_conversation_confirm)
                    .setPositiveButton(R.string.delete) { _, _ -> if (thread != Long.MIN_VALUE) s.deleteConversation(thread); finish() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_FOCUS = "focus"
        private val BACKGROUND = Color.rgb(11, 13, 15)
        private val CHARCOAL = Color.rgb(37, 39, 41)
        private val COMPOSER = Color.rgb(31, 33, 36)
        private val DIVIDER = Color.rgb(48, 50, 54)
        private val ACCENT = Color.rgb(0, 199, 183)
        private val INK = Color.rgb(8, 23, 24)
        private val MUTED = Color.rgb(190, 196, 201)
        private val FAILURE = Color.rgb(112, 18, 27)
    }
}
