package app.tether.client.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.tether.client.R
import app.tether.client.model.Problem
import app.tether.client.model.contactOf
import app.tether.client.model.dialable
import app.tether.core.Contact
import app.tether.core.Phone
import app.tether.core.numberKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class EditContactActivity : Activity() {
    private data class NumberField(val number: EditText, val label: TextView, var phone: Phone)

    private val views by lazy { LinkViews(this) }
    private val accent = 0xff00c7b7.toInt()
    private val numbers = mutableListOf<NumberField>()
    private val emails = mutableListOf<EditText>()
    private lateinit var token: String
    private var original: Contact? = null
    private lateinit var originalVersion: String
    private var submitted = false
    private lateinit var name: EditText
    private lateinit var phoneGroup: LinearLayout
    private lateinit var emailGroup: LinearLayout
    private lateinit var addNumber: TextView
    private lateinit var addEmail: TextView
    private lateinit var save: ImageButton
    private lateinit var back: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private val listener: () -> Unit = ::refreshSave
    private val pending get() = app.state.contactSave?.let { it.token == token && it.pending } == true
    private val types = listOf("mobile", "home", "work", "main", "other")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = savedInstanceState?.getString("token") ?: UUID.randomUUID().toString()
        submitted = savedInstanceState?.getBoolean("submitted") == true
        originalVersion = savedInstanceState?.getString("contactVersion") ?: app.state.contactsVersion
        val id = intent.getLongExtra(CONTACT_ID, Long.MIN_VALUE)
        val current = app.state.contacts.singleOrNull { it.id == id }
        original = savedInstanceState?.getString("original")?.let { contactOf(JSONObject(it)) } ?: current
        if (id != Long.MIN_VALUE && original == null) { finish(); return }
        val initial = original
        val draft = savedInstanceState?.getString("draft")?.let { contactOf(JSONObject(it)) }
        val root = column().apply {
            setBackgroundColor(views.background)
            isFocusableInTouchMode = true
        }
        val header = row().apply { setPaddingRelative(dp(6), dp(2), dp(6), dp(4)) }
        back = headerButton(R.drawable.ic_thread_back, R.string.contacts_detail_back, Color.WHITE) { cancel() }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label(getString(if (initial == null) R.string.new_contact else R.string.contacts_editor_title),
            size = 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        save = headerButton(R.drawable.ic_contact_editor_save, R.string.save, accent, ::submit)
        header.addView(save, LinearLayout.LayoutParams(dp(48), dp(48)))
        progress = ProgressBar(this).apply { contentDescription = getString(R.string.contacts_editor_saving) }
        header.addView(progress, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) })
        root.addView(header)
        status = label("", R.color.warn, 14f).apply {
            setPaddingRelative(dp(16), dp(4), dp(16), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)

        val form = column().apply { setPadding(dp(20), dp(12), dp(20), dp(28)) }
        val picture = avatar(draft?.name ?: initial?.name.orEmpty(), current?.photo, 84)
        if (picture is TextView) {
            picture.textAlignment = View.TEXT_ALIGNMENT_CENTER
            picture.setTextColor(Color.WHITE)
            picture.background = views.shape(0xff306eb9.toInt(), 48)
        }
        form.addView(picture, LinearLayout.LayoutParams(dp(84), dp(84)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(24)
        })
        name = field(R.string.name, draft?.name ?: initial?.name.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        form.addView(fieldRow(R.drawable.ic_link_contacts, R.string.name, name))
        phoneGroup = column()
        form.addView(phoneGroup)
        val initialNumbers = draft?.phones ?: initial?.phones.orEmpty()
        initialNumbers.forEach(::appendNumber)
        if (initialNumbers.isEmpty() && initial == null) appendNumber(Phone(intent.getStringExtra(PREFILL).orEmpty(), "mobile"))
        addNumber = addAction(R.string.contacts_editor_add_number) {
            appendNumber(Phone("", "mobile"))
            numbers.last().number.requestFocus()
        }
        form.addView(addNumber)
        emailGroup = column()
        form.addView(emailGroup)
        (draft?.emails ?: initial?.emails.orEmpty()).forEach(::appendEmail)
        addEmail = addAction(R.string.contacts_editor_add_email) {
            appendEmail("")
            emails.last().requestFocus()
        }
        form.addView(addEmail)
        val viewport = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                form.layoutParams.width = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(480))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { addView(form, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL)) }
        val scroll = ScrollView(this).apply { addView(viewport) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom))
            insets
        }
        setContentView(root)
        root.requestFocus()
        val phoneIndex = intent.getIntExtra(PHONE_INDEX, -1)
        scroll.post {
            if (savedInstanceState != null) scroll.scrollTo(0, savedInstanceState.getInt("scroll"))
            else numbers.getOrNull(phoneIndex)?.number?.requestFocus()
        }
        formScroll = scroll
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::cancel)
        refreshSave()
    }

    private var formScroll: ScrollView? = null

    override fun onResume() {
        super.onResume()
        if (!::name.isInitialized) return
        app.addListener(listener)
        refreshSave()
    }

    override fun onPause() {
        app.removeListener(listener)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::name.isInitialized) return
        outState.putString("token", token)
        outState.putBoolean("submitted", submitted)
        outState.putString("contactVersion", originalVersion)
        outState.putInt("scroll", formScroll?.scrollY ?: 0)
        original?.let { outState.putString("original", it.copy(photo = null).json().toString()) }
        outState.putString("draft", Contact(-1, name.text.toString(), numbers.map {
            it.phone.copy(n = it.number.text.toString())
        }, emails.map { it.text.toString() }, null, false).json().toString())
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = cancel()

    private fun cancel() { if (!pending) finish() }

    private fun refreshSave() {
        val result = app.state.contactSave?.takeIf { it.token == token }
        if (submitted && result != null && !result.pending && result.error == null) { finish(); return }
        val saving = pending
        val error = result?.error ?: if (submitted && result == null) Problem(Problem.CONTACT_SAVE_UNKNOWN) else null
        val uncertainAdd = original == null && error?.kind == Problem.CONTACT_SAVE_UNKNOWN
        status.text = when (error?.kind) {
            Problem.CONTACT_CHANGED -> getString(R.string.contacts_editor_changed)
            Problem.CONTACT_SAVE_UNKNOWN -> getString(if (uncertainAdd) R.string.contacts_editor_add_unknown else R.string.contacts_editor_unknown)
            else -> error?.let(::describe).orEmpty()
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        progress.visibility = if (saving) View.VISIBLE else View.GONE
        save.visibility = if (saving) View.GONE else View.VISIBLE
        save.isEnabled = !saving && !uncertainAdd
        back.isEnabled = !saving
        name.isEnabled = !saving
        addNumber.isEnabled = !saving
        addEmail.isEnabled = !saving
        numbers.forEach { it.number.isEnabled = !saving; it.label.isEnabled = !saving }
        emails.forEach { it.isEnabled = !saving }
    }

    private fun submit() {
        if (pending) return
        val rawName = name.text.toString()
        val n = if (rawName == original?.name) rawName else rawName.trim()
        if (n.isBlank()) { invalid(name, R.string.contacts_editor_name_required); return }
        val phones = mutableListOf<Phone>()
        val allKeys = numbers.map { numberKey(it.number.text.toString()) }
        for ((index, entry) in numbers.withIndex()) {
            val old = original?.phones?.getOrNull(index)
            val raw = entry.number.text.toString()
            val value = if (raw == old?.n) raw else raw.trim()
            if (old == null && value.isEmpty()) continue
            val key = allKeys[index]
            if (value != old?.n && (dialable(value).none { it.isDigit() } || allKeys.count { it == key } > 1)) {
                invalid(entry.number, R.string.contacts_editor_number_invalid)
                return
            }
            phones.add(entry.phone.copy(n = value))
        }
        val addresses = mutableListOf<String>()
        for ((index, entry) in emails.withIndex()) {
            val old = original?.emails?.getOrNull(index)
            val raw = entry.text.toString()
            val value = if (raw == old) raw else raw.trim()
            if (old == null && value.isEmpty()) continue
            if (value != old && !Patterns.EMAIL_ADDRESS.matcher(value).matches()) {
                invalid(entry, R.string.contacts_editor_email_invalid)
                return
            }
            addresses.add(value)
        }
        submitted = true
        app.state.saveContact(token, original, n, phones, addresses, originalVersion)
        refreshSave()
    }

    private fun invalid(field: EditText, message: Int) {
        field.error = getString(message)
        field.requestFocus()
    }

    private fun appendNumber(phone: Phone) {
        val input = field(R.string.phone, phone.n, InputType.TYPE_CLASS_PHONE)
        val entry = NumberField(input, label(contactPhoneLabel(phone), R.color.accent, 13f), phone)
        numbers.add(entry)
        phoneGroup.addView(fieldRow(R.drawable.ic_contacts_call, R.string.phone, input, entry.label.apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setOnClickListener { chooseType(entry) }
        }))
    }

    private fun chooseType(entry: NumberField) {
        val options = types.map { contactPhoneLabel(Phone("", it)) } + getString(R.string.contacts_editor_custom_label)
        AlertDialog.Builder(this).setTitle(R.string.contacts_editor_number_type).setItems(options.toTypedArray()) { _, index ->
            if (index == types.size) {
                val input = field(R.string.contacts_editor_custom_label, entry.phone.label.orEmpty(), InputType.TYPE_CLASS_TEXT)
                AlertDialog.Builder(this).setTitle(R.string.contacts_editor_custom_label).setView(input)
                    .setPositiveButton(R.string.save) { _, _ ->
                        input.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
                            entry.phone = entry.phone.copy(label = it)
                            entry.label.text = contactPhoneLabel(entry.phone)
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            } else {
                entry.phone = entry.phone.copy(type = types[index], label = if (entry.phone.label == null) null else "")
                entry.label.text = contactPhoneLabel(entry.phone)
            }
        }.show()
    }

    private fun appendEmail(value: String) {
        val input = field(R.string.contacts_editor_email, value, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        emails.add(input)
        emailGroup.addView(fieldRow(null, R.string.contacts_editor_email, input))
    }

    private fun field(hintId: Int, value: String, input: Int) = EditText(this).apply {
        hint = getString(hintId)
        setText(value)
        setSingleLine()
        inputType = input
        if (input == InputType.TYPE_CLASS_PHONE) textDirection = View.TEXT_DIRECTION_LTR
        setTextColor(Color.WHITE)
        setHintTextColor(0xffc4c7c9.toInt())
        textSize = 16f
        minimumHeight = dp(52)
        setPaddingRelative(dp(13), dp(7), dp(13), dp(7))
        background = views.shape(0xff191a1c.toInt(), 16).apply { setStroke(dp(1), 0xff343638.toInt()) }
    }

    private fun fieldRow(glyph: Int?, title: Int, field: EditText, extra: View? = null) = row().apply {
        val icon = ImageView(this@EditContactActivity).apply {
            glyph?.let(::setImageResource)
            setColorFilter(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        addView(column().apply {
            addView(label(getString(title), R.color.muted, 13f))
            addView(field, LinearLayout.LayoutParams(-1, -2))
            extra?.let { addView(it) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = dp(10) })
    }

    private fun addAction(title: Int, run: () -> Unit) = label(getString(title), size = 16f, bold = true).apply {
        setTextColor(accent)
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(36), 0, dp(8), 0)
        isFocusable = true
        setOnClickListener { run() }
    }

    private fun headerButton(glyph: Int, title: Int, tint: Int, run: () -> Unit) = ImageButton(this).apply {
        setImageResource(glyph)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.CENTER
        background = null
        contentDescription = getString(title)
        setOnClickListener { run() }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    companion object {
        const val CONTACT_ID = "contactId"
        const val PREFILL = "prefill"
        const val PHONE_INDEX = "phoneIndex"
    }
}
