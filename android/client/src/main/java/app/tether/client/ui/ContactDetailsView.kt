package app.tether.client.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.tether.client.R
import app.tether.core.Contact
import app.tether.core.Phone

internal class ContactDetailsView(
    private val owner: Activity,
    private val views: LinkViews,
    back: () -> Unit,
    call: (Contact) -> Unit,
    message: (Contact) -> Unit,
    edit: (Contact) -> Unit,
    block: (Contact) -> Unit,
    favourite: (Contact) -> Unit,
    more: (Contact) -> Unit,
    private val phoneActions: (Contact, Phone) -> Unit,
) : FrameLayout(owner) {
    private val teal = Color.rgb(0, 199, 183)
    private val muted = Color.rgb(196, 199, 201)
    private var bound: Contact? = null
    private var boundDefault: Phone? = null
    private val canvas = column().apply { setPadding(owner.dp(16), 0, owner.dp(16), owner.dp(16)) }
    private val face = FrameLayout(owner)
    private val name = text(28f, bold = true).apply { gravity = Gravity.CENTER; textAlignment = TEXT_ALIGNMENT_CENTER }
    private val aboutName = text(15f, muted)
    private val star = icon(R.drawable.ic_contact_details_star, R.string.favourite) { bound?.let(favourite) }
    private val favoriteSwitch = Switch(owner).apply {
        text = resources.getString(R.string.contacts_detail_favourite)
        setTextColor(Color.WHITE)
        textSize = 16f
        minHeight = owner.dp(52)
        setPaddingRelative(owner.dp(4), 0, owner.dp(4), 0)
        val checked = intArrayOf(android.R.attr.state_checked)
        thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(teal, muted))
        trackTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(0xff046c67.toInt(), 0xff555759.toInt()))
        setOnClickListener {
            bound?.let { contact ->
                isChecked = contact.starred
                favourite(contact)
            }
        }
    }
    private val callAction = action(R.drawable.ic_contacts_call, R.string.call, primary = true) { bound?.let(call) }
    private val messageAction = action(R.drawable.ic_link_messages, R.string.contacts_detail_message) { bound?.let(message) }
    private val editAction = action(R.drawable.ic_contact_details_edit, R.string.edit) { bound?.let(edit) }
    private val blockAction = action(R.drawable.ic_contact_details_block, R.string.block) { bound?.let(block) }
    private val numbers = column()

    init {
        setBackgroundColor(views.background)
        val top = owner.row().apply {
            addView(icon(R.drawable.ic_thread_back, R.string.contacts_detail_back, back),
                LinearLayout.LayoutParams(owner.dp(48), owner.dp(48)))
            addView(android.view.View(owner), LinearLayout.LayoutParams(0, 1, 1f))
            addView(star, LinearLayout.LayoutParams(owner.dp(48), owner.dp(48)))
            addView(icon(R.drawable.ic_link_overflow, R.string.contacts_detail_more) { bound?.let(more) },
                LinearLayout.LayoutParams(owner.dp(48), owner.dp(48)))
        }
        addView(column().apply {
            addView(top)
            addView(ScrollView(owner).apply {
                isVerticalScrollBarEnabled = false
                val viewport = object : FrameLayout(owner) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        canvas.layoutParams.width = minOf(MeasureSpec.getSize(widthMeasureSpec), owner.dp(464))
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    }
                }
                viewport.addView(canvas, LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
                addView(viewport, FrameLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }, LayoutParams(-1, -1))
        canvas.addView(face, LinearLayout.LayoutParams(owner.dp(84), owner.dp(84)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = owner.dp(3)
        })
        canvas.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = owner.dp(8) })
        canvas.addView(owner.row().apply {
            for (item in listOf(callAction, messageAction, editAction, blockAction)) {
                addView(item.container, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = owner.dp(12); bottomMargin = owner.dp(20) })
        canvas.addView(card(R.string.contacts_detail_numbers).apply { addView(numbers) })
        canvas.addView(card(R.string.contacts_detail_about).apply {
            addView(owner.row().apply {
                addView(ImageView(owner).apply {
                    setImageResource(R.drawable.ic_link_contacts)
                    setColorFilter(muted)
                }, LinearLayout.LayoutParams(owner.dp(24), owner.dp(24)).apply { marginEnd = owner.dp(14) })
                addView(column().apply {
                    addView(text(16f).apply { setText(R.string.contacts_detail_name) })
                    addView(aboutName)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                setPaddingRelative(owner.dp(4), owner.dp(10), owner.dp(4), owner.dp(10))
            })
            addView(views.separator())
            addView(favoriteSwitch)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = owner.dp(12) })
    }

    fun bind(contact: Contact, blocked: Boolean, defaultPhone: Phone?) {
        val previous = bound
        val previousDefault = boundDefault
        bound = contact
        boundDefault = defaultPhone
        if (previous == null || previous.name != contact.name || previous.photo != contact.photo) {
            face.removeAllViews()
            val avatar = owner.avatar(contact.name, contact.photo, 84)
            if (avatar is TextView) {
                avatar.textAlignment = TEXT_ALIGNMENT_CENTER
                avatar.setTextColor(Color.WHITE)
                avatar.background = views.shape(0xff306eb9.toInt(), 48)
            }
            face.addView(avatar, LayoutParams(-1, -1))
            name.text = contact.name
            aboutName.text = contact.name
        }
        if (previous?.phones != contact.phones || previousDefault != defaultPhone) {
            numbers.removeAllViews()
            if (contact.phones.isEmpty()) numbers.addView(text(15f, muted).apply { setText(R.string.contacts_detail_no_numbers) })
            for (phone in contact.phones) numbers.addView(numberRow(phone, phone == defaultPhone))

        }
        val hasPhone = contact.phones.isNotEmpty()
        for (item in listOf(callAction, messageAction, blockAction)) {
            item.button.isEnabled = hasPhone
            item.container.alpha = if (hasPhone) 1f else 0.45f
        }
        blockAction.label.setText(if (blocked) R.string.unblock else R.string.block)
        blockAction.button.contentDescription = blockAction.label.text
        favoriteSwitch.isChecked = contact.starred
        star.setColorFilter(if (contact.starred) teal else Color.WHITE)
        star.isSelected = contact.starred
        star.contentDescription = resources.getString(if (contact.starred) R.string.unfavourite else R.string.favourite)
    }

    private fun numberRow(phone: Phone, isDefault: Boolean) = owner.row().apply {
        minimumHeight = owner.dp(62)
        val formatted = owner.app.format(phone.n)
        addView(ImageView(owner).apply {
            setImageResource(R.drawable.ic_contacts_call)
            setColorFilter(Color.WHITE)
        }, LinearLayout.LayoutParams(owner.dp(22), owner.dp(22)).apply {
            marginStart = owner.dp(8); marginEnd = owner.dp(16)
        })
        addView(column().apply {
            addView(text(17f).apply {
                text = formatted
                textDirection = TEXT_DIRECTION_LTR
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(owner.row().apply {
                addView(text(14f, muted).apply {
                    text = owner.contactPhoneLabel(phone)
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2, 1f))
                if (isDefault) addView(text(12f, teal, bold = true).apply {
                    setText(R.string.contacts_default_badge)
                    background = views.shape(0xff06352f.toInt(), 10)
                    setPaddingRelative(owner.dp(7), owner.dp(2), owner.dp(7), owner.dp(2))
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = owner.dp(6) })
            })
            setOnClickListener { bound?.let { phoneActions(it, phone) } }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(R.drawable.ic_link_overflow, R.string.contacts_detail_number_actions) { bound?.let { phoneActions(it, phone) } },
            LinearLayout.LayoutParams(owner.dp(48), owner.dp(48)))
    }

    private data class Action(val container: LinearLayout, val button: ImageButton, val label: TextView)

    private fun action(glyph: Int, title: Int, primary: Boolean = false, run: () -> Unit): Action {
        val ink = if (primary) teal else Color.WHITE
        val button = icon(glyph, title, run).apply {
            background = views.shape(if (primary) teal else 0xff202123.toInt(), 36)
            setColorFilter(if (primary) views.background else Color.WHITE)
        }
        val label = text(14f, ink).apply { setText(title); gravity = Gravity.CENTER; textAlignment = TEXT_ALIGNMENT_CENTER }
        val container = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(button, LinearLayout.LayoutParams(owner.dp(52), owner.dp(52)))
            addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = owner.dp(4) })
        }
        return Action(container, button, label)
    }

    private fun icon(glyph: Int, title: Int, click: () -> Unit) = ImageButton(owner).apply {
        setImageResource(glyph)
        setColorFilter(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        background = null
        contentDescription = resources.getString(title)
        setOnClickListener { click() }
    }

    private fun card(title: Int) = column().apply {
        background = views.shape(0xff151617.toInt(), 21)
        setPadding(owner.dp(14), owner.dp(10), owner.dp(14), owner.dp(6))
        addView(text(16f, bold = true).apply { setText(title) })
    }

    private fun column() = LinearLayout(owner).apply { orientation = LinearLayout.VERTICAL }
    private fun text(size: Float, tint: Int = Color.WHITE, bold: Boolean = false) =
        owner.label("", size = size, bold = bold).apply { setTextColor(tint) }
}
