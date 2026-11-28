package app.tether.client.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.tether.client.R
import app.tether.client.model.Search
import app.tether.core.Contact

class NewMessageActivity : Activity() {
    private lateinit var recipient: EditText
    private lateinit var hint: TextView
    private lateinit var results: ListView
    private lateinit var next: View
    private lateinit var clear: View
    private val listener: () -> Unit = { renderRecipients() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val style = LinkViews(this)
        val viewport = ClientTabViewport(this).apply { setBackgroundColor(style.background) }
        val column = viewport.column()
        viewport.canvas.addView(column)
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_thread_back)
            setColorFilter(col(R.color.text))
            background = null
            contentDescription = getString(R.string.thread_back)
            setOnClickListener { finish() }
        }
        column.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(label(getString(R.string.new_message), size = 26f, bold = true))
        })
        recipient = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setHint(R.string.message_recipient_hint)
            setTextColor(col(R.color.text))
            setHintTextColor(col(R.color.muted))
            background = style.shape(col(R.color.surface), 24)
            minimumHeight = dp(52)
            setPaddingRelative(dp(16), dp(8), dp(16), dp(8))
            setText(savedInstanceState?.getString("recipient").orEmpty())
            setSelection(length())
        }
        clear = ImageButton(this).apply {
            setImageResource(R.drawable.ic_keypad_backspace)
            setColorFilter(col(R.color.muted))
            background = null
            contentDescription = getString(R.string.message_recipient_clear)
            setOnClickListener { recipient.text.clear(); recipient.requestFocus() }
        }
        column.addView(row().apply {
            background = style.shape(col(R.color.surface), 24)
            recipient.background = null
            addView(recipient, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clear, LinearLayout.LayoutParams(dp(48), dp(48)))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        hint = label("", R.color.muted, 14f).apply {
            setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(hint)
        results = ListView(this).apply {
            divider = null
            isVerticalScrollBarEnabled = false
        }
        column.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        next = pill(getString(R.string.next)) { continueToThread() }.apply { minimumHeight = dp(48) }
        column.addView(next, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        recipient.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderRecipients() }
            override fun afterTextChanged(s: Editable?) {}
        })
        recipient.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_NEXT && next.isEnabled) { continueToThread(); true } else false
        }
        setContentView(viewport)
        fitSystemBars(viewport)
        recipient.requestFocus()
        if (savedInstanceState == null) recipient.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(recipient, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onResume() { super.onResume(); app.addListener(listener); renderRecipients() }
    override fun onPause() { app.removeListener(listener); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("recipient", recipient.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun renderRecipients() {
        val input = recipient.text.toString()
        val number = smsRecipient(input)
        val options = recipientOptions(input, app.state.contacts)
        clear.visibility = if (input.isEmpty()) View.INVISIBLE else View.VISIBLE
        next.isEnabled = number != null
        next.alpha = if (next.isEnabled) 1f else 0.45f
        hint.text = when {
            number != null -> app.state.name(number) ?: app.format(number)
            options.isNotEmpty() -> getString(R.string.contacts_search)
            app.state.syncing -> getString(R.string.message_recipient_loading)
            input.isBlank() -> getString(R.string.message_recipient_hint)
            input.any { it.isDigit() } -> getString(R.string.message_recipient_invalid)
            else -> getString(R.string.message_recipient_empty)
        }
        results.adapter = Rows(options) { option ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(56)
                setPaddingRelative(dp(14), dp(8), dp(14), dp(8))
                addView(label(option.name, size = 16f, bold = true))
                addView(label(app.format(option.number), R.color.muted, 13f))
            }
        }
        results.setOnItemClickListener { _, _, pos, _ ->
            recipient.setText(options[pos].number)
            recipient.setSelection(recipient.length())
        }
    }

    private fun continueToThread() {
        val number = smsRecipient(recipient.text.toString()) ?: return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(recipient.windowToken, 0)
        startActivity(Intent(this, ThreadActivity::class.java)
            .putExtra(ThreadActivity.EXTRA_ADDRESS, number)
            .putExtra(ThreadActivity.EXTRA_THREAD, app.state.threadFor(number) ?: Long.MIN_VALUE))
    }
}

internal data class RecipientOption(val name: String, val number: String)

internal fun recipientOptions(query: String, contacts: List<Contact>): List<RecipientOption> =
    if (query.isBlank()) emptyList() else contacts.asSequence().flatMap { contact ->
        contact.phones.asSequence().filter { Search.matches(query, listOf(contact.name), listOf(it.n)) }
            .mapNotNull { phone -> smsRecipient(phone.n)?.let { RecipientOption(contact.name, it) } }
    }.distinct().take(40).toList()

internal fun smsRecipient(input: String): String? {
    val compact = input.filterNot { it.isWhitespace() || it in "-().\u200e\u200f" }
    val digits = Search.digits(compact)
    val international = compact.startsWith("+")
    if (digits.length != compact.length - (if (international) 1 else 0)) return null
    return when {
        international && digits.startsWith("98") -> digits.takeIf { it.length == 12 && it[2] != '0' }?.let { "+$it" }
        !international && digits.startsWith("0098") ->
            digits.drop(2).takeIf { it.length == 12 && it[2] != '0' }?.let { "+$it" }
        !international && digits.startsWith("09") -> digits.takeIf { it.length == 11 }?.let { "+98${it.drop(1)}" }
        international -> digits.takeIf { it.length in 7..15 && it.first() != '0' }?.let { "+$it" }
        digits.startsWith("00") -> digits.drop(2).takeIf { it.length in 7..15 && it.first() != '0' }?.let { "+$it" }
        else -> digits.takeIf { it.length in 7..15 }
    }
}
