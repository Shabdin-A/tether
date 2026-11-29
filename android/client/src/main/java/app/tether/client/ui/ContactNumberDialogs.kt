package app.tether.client.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import app.tether.client.R
import app.tether.core.Contact
import app.tether.core.Phone

private val numberAccent = 0xff00c7b7.toInt()
private val numberMuted = 0xffc4c7c9.toInt()
private val numberSelectionTint = ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(numberAccent, numberMuted))

internal fun Context.contactPhoneLabel(phone: Phone): String {
    phone.label?.takeIf { it.isNotBlank() }?.let { return it }
    return getString(when (phone.type) {
        "mobile" -> R.string.contacts_detail_mobile
        "home" -> R.string.contacts_detail_home
        "work" -> R.string.contacts_detail_work
        "main" -> R.string.contacts_detail_main
        "other" -> R.string.contacts_detail_other
        else -> return phone.type
    })
}

internal fun showContactNumberPicker(
    owner: Activity,
    views: LinkViews,
    contact: Contact,
    defaultPhone: Phone?,
    saveOnly: Boolean,
    onConfirm: (Phone, Boolean) -> Unit,
) {
    if (contact.phones.isEmpty()) return
    var selected = contact.phones.indexOf(defaultPhone).coerceAtLeast(0)
    val dialog = Dialog(owner)
    val panel = owner.numberPanel(views, 28)
    panel.addView(View(owner).apply { background = views.shape(0xff646669.toInt(), 4) },
        LinearLayout.LayoutParams(owner.dp(34), owner.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = owner.dp(12)
        })
    panel.addView(owner.label(owner.getString(R.string.contacts_choose_number), size = 25f, bold = true))
    panel.addView(owner.label(owner.getString(
        if (saveOnly) R.string.contacts_choose_default_for else R.string.contacts_choose_call_for, contact.name), size = 16f).apply {
        setTextColor(numberMuted)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = owner.dp(5); bottomMargin = owner.dp(14) })

    val radios = mutableListOf<RadioButton>()
    val rows = mutableListOf<View>()
    for ((index, phone) in contact.phones.withIndex()) {
        val radio = RadioButton(owner).apply {
            buttonTintList = numberSelectionTint
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        radios.add(radio)
        val formatted = owner.app.format(phone.n)
        val type = owner.contactPhoneLabel(phone)
        val row = owner.row().apply {
            minimumHeight = owner.dp(66)
            setPaddingRelative(owner.dp(7), owner.dp(6), owner.dp(10), owner.dp(6))
            addView(radio, LinearLayout.LayoutParams(owner.dp(40), -2))
            addView(owner.numberGlyph(R.drawable.ic_contacts_call), LinearLayout.LayoutParams(owner.dp(22), owner.dp(22)).apply {
                marginEnd = owner.dp(13)
            })
            addView(LinearLayout(owner).apply {
                orientation = LinearLayout.VERTICAL
                addView(owner.label(formatted, size = 17f, bold = true))
                addView(owner.label(type, size = 14f).apply { setTextColor(numberMuted) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            contentDescription = "$formatted, $type"
            isFocusable = true
            setOnClickListener {
                selected = index
                for (i in rows.indices) {
                    radios[i].isChecked = i == selected
                    rows[i].isSelected = i == selected
                    rows[i].background = if (i == selected) views.shape(0xff292c2f.toInt(), 23) else null
                }
            }
        }
        rows.add(row)
        radio.isChecked = index == selected
        row.isSelected = index == selected
        if (row.isSelected) row.background = views.shape(0xff292c2f.toInt(), 23)
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = owner.dp(4) })
    }
    val save = CheckBox(owner).apply {
        setText(R.string.contacts_save_default_checkbox)
        setTextColor(Color.WHITE)
        buttonTintList = numberSelectionTint
        minHeight = owner.dp(48)
    }
    if (!saveOnly) {
        panel.addView(views.separator())
        panel.addView(save)
    }
    val confirm = owner.getString(if (saveOnly) R.string.save else R.string.call)
    panel.addView(owner.row(gravity = Gravity.CENTER).apply {
        background = views.shape(numberAccent, 32)
        minimumHeight = owner.dp(52)
        if (!saveOnly) addView(owner.numberGlyph(R.drawable.ic_contacts_call, views.background),
            LinearLayout.LayoutParams(owner.dp(20), owner.dp(20)).apply { marginEnd = owner.dp(8) })
        addView(owner.label(confirm, size = 17f, bold = true).apply { setTextColor(views.background) })
        contentDescription = confirm
        isFocusable = true
        setOnClickListener {
            dialog.dismiss()
            onConfirm(contact.phones[selected], save.isChecked)
        }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = owner.dp(12) })
    panel.addView(owner.label(owner.getString(android.R.string.cancel), size = 16f).apply {
        minHeight = owner.dp(48)
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        isFocusable = true
        setOnClickListener { dialog.dismiss() }
    })
    dialog.showNumberPanel(owner, panel, Gravity.BOTTOM)
}

internal fun showContactNumberOptions(
    owner: Activity,
    views: LinkViews,
    number: String,
    actions: List<Pair<Int, Int>>,
    onItem: (Int) -> Unit,
) {
    val dialog = Dialog(owner)
    val panel = owner.numberPanel(views, 24)
    panel.addView(owner.label(number, size = 20f, bold = true), LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = owner.dp(12)
    })
    actions.forEachIndexed { index, (glyph, label) ->
        panel.addView(owner.row().apply {
            minimumHeight = owner.dp(52)
            setPaddingRelative(owner.dp(12), owner.dp(5), owner.dp(12), owner.dp(5))
            if (index == 0) background = views.shape(0xff292c2f.toInt(), 16)
            addView(owner.numberGlyph(glyph), LinearLayout.LayoutParams(owner.dp(23), owner.dp(23)).apply {
                marginEnd = owner.dp(14)
            })
            addView(owner.label(owner.getString(label), size = 17f))
            isFocusable = true
            setOnClickListener { dialog.dismiss(); onItem(index) }
        })
    }
    dialog.showNumberPanel(owner, panel, Gravity.CENTER)
}

private fun Activity.numberPanel(views: LinkViews, radius: Int) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = views.shape(0xff191a1c.toInt(), radius)
    setPadding(dp(20), dp(12), dp(20), dp(12))
}

private fun Activity.numberGlyph(resource: Int, tint: Int = Color.WHITE) = ImageView(this).apply {
    setImageResource(resource)
    setColorFilter(tint)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

private fun Dialog.showNumberPanel(owner: Activity, panel: LinearLayout, gravity: Int) {
    setContentView(object : ScrollView(owner) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxHeight = minOf(MeasureSpec.getSize(heightMeasureSpec), (owner.window.decorView.height * 0.9f).toInt())
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }.apply {
        clipToOutline = true
        background = panel.background
        panel.background = null
        addView(panel)
    })
    show()
    window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.65f)
        setGravity(gravity)
        val availableWidth = owner.window.decorView.width
        val width = if (gravity == Gravity.BOTTOM) availableWidth else availableWidth - owner.dp(36)
        setLayout(minOf(width, owner.dp(480)), WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
