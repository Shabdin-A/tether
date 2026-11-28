package app.tether.client.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.tether.client.R
import app.tether.client.model.Search
import app.tether.core.Contact

internal class ContactsView(
    private val owner: Activity,
    private val views: LinkViews,
    overflow: (View) -> Unit,
    private val openContact: (Contact) -> Unit,
    private val callContact: (Contact) -> Unit,
    addContact: () -> Unit,
) : ClientTabViewport(owner) {
    private sealed interface Item {
        data class Heading(val title: String) : Item
        data class Person(val contact: Contact) : Item
    }

    private var contacts: List<Contact> = emptyList()
    private var items: List<Item> = emptyList()
    private val photos = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = key.length * 2 + value.allocationByteCount
    }
    private val muted = Color.rgb(196, 199, 201)
    private val accent = Color.rgb(0, 199, 183)
    private val avatarColors = intArrayOf(0xff276ab8.toInt(), 0xff3a639a.toInt(), 0xff795fa2.toInt(), 0xff486477.toInt())
    private val rows = ContactRows()
    private val search = EditText(owner).apply {
        hint = resources.getString(R.string.contacts_search)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        textSize = 15f
        setTextColor(Color.WHITE)
        setHintTextColor(muted)
        background = views.shape(Color.rgb(30, 31, 32), 24)
        minimumHeight = owner.dp(48)
        setPaddingRelative(owner.dp(14), owner.dp(4), owner.dp(14), owner.dp(4))
        compoundDrawablePadding = owner.dp(10)
        val glyph = owner.getDrawable(R.drawable.ic_messages_search)?.mutate()?.apply {
            setTint(Color.WHITE)
            setBounds(0, 0, owner.dp(19), owner.dp(19))
        }
        setCompoundDrawablesRelative(glyph, null, null, null)
    }
    private val list = ListView(owner).apply {
        divider = null
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, 0, 0, owner.dp(76))
        adapter = rows
        setOnItemClickListener { _, _, position, _ ->
            (rows.getItem(position) as? Item.Person)?.let { openContact(it.contact) }
        }
    }
    init {
        val column = column()
        views.heading(column, R.string.tab_contacts, R.string.contacts_subtitle, search = {
            search.requestFocus()
            owner.getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }, overflow = overflow)
        column.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = owner.dp(10) })
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateRows(resetScroll = true) }
        })
        val add = ImageButton(owner).apply {
            setImageResource(R.drawable.ic_contacts_add)
            setColorFilter(Color.rgb(9, 10, 11))
            scaleType = ImageView.ScaleType.CENTER
            background = views.shape(accent, 48)
            contentDescription = resources.getString(R.string.add_contact)
            setOnClickListener { addContact() }
        }
        canvas.addView(column, LayoutParams(-1, -1))
        canvas.addView(add, LayoutParams(owner.dp(60), owner.dp(60), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = owner.dp(20)
            bottomMargin = owner.dp(10)
        })
        column.requestFocus()
    }

    fun submit(snapshot: List<Contact>) {
        if (contacts === snapshot) return
        contacts = snapshot
        updateRows()
    }

    private fun updateRows(resetScroll: Boolean = false) {
        val query = search.text.toString()
        val visible = if (query.isBlank()) contacts else contacts.filter {
            Search.matches(query, listOf(it.name), it.phones.map { phone -> phone.n })
        }
        val next = buildList {
            if (query.isBlank()) {
                val favorites = visible.filter { it.starred }
                if (favorites.isNotEmpty()) {
                    add(Item.Heading(resources.getString(R.string.contacts_favorites)))
                    favorites.forEach { add(Item.Person(it)) }
                }
                // Use the existing order, including when the source temporarily has an edited name.
                var previous: String? = null
                for (contact in visible) {
                    val letter = contact.name.trim().firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: "#"
                    if (letter != previous) add(Item.Heading(letter))
                    add(Item.Person(contact))
                    previous = letter
                }
            } else visible.forEach { add(Item.Person(it)) }
        }
        if (next == items) return
        items = next
        rows.notifyDataSetChanged()
        if (resetScroll) list.setSelection(0)
    }

    private inner class ContactRows : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (getItem(position) is Item.Heading) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = getItem(position) is Item.Person

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            when (val item = getItem(position)) {
                is Item.Heading -> (convertView as? TextView ?: owner.label("", size = 13f).apply {
                    setTextColor(Color.WHITE)
                    setPaddingRelative(owner.dp(8), owner.dp(7), 0, owner.dp(4))
                    isAccessibilityHeading = true
                }).apply { text = item.title }
                is Item.Person -> (convertView as? ContactRow ?: ContactRow()).apply { bind(item.contact) }
            }
    }

    private inner class ContactRow : FrameLayout(owner) {
        private lateinit var contact: Contact
        private val initial = owner.label("", size = 20f).apply {
            gravity = Gravity.CENTER
            textAlignment = TEXT_ALIGNMENT_CENTER
            setTextColor(Color.WHITE)
        }
        private val picture = ImageView(owner).apply {
            background = views.shape(Color.TRANSPARENT, 44)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        private val title = owner.label("", size = 17f, bold = true).apply {
            setTextColor(Color.WHITE)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        private val number = owner.label("", size = 14f).apply {
            setTextColor(muted)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        private val phone = ImageButton(owner).apply {
            background = null
            setImageResource(R.drawable.ic_contacts_call)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { callContact(contact) }
        }

        init {
            setPadding(0, 0, 0, owner.dp(8))
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            val panel = LinearLayout(owner).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = views.shape(Color.rgb(21, 22, 23), 21)
                minimumHeight = owner.dp(68)
                setPaddingRelative(owner.dp(14), owner.dp(6), owner.dp(4), owner.dp(6))
            }
            val avatar = FrameLayout(owner).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(initial, LayoutParams(-1, -1))
                addView(picture, LayoutParams(-1, -1))
            }
            panel.addView(avatar, LinearLayout.LayoutParams(owner.dp(44), owner.dp(44)).apply { marginEnd = owner.dp(10) })
            panel.addView(LinearLayout(owner).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(number)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(phone, LinearLayout.LayoutParams(owner.dp(48), owner.dp(48)))
            addView(panel, LayoutParams(-1, -2))
        }

        fun bind(contact: Contact) {
            this.contact = contact
            title.text = contact.name
            val first = contact.phones.firstOrNull()
            number.text = first?.let { owner.app.format(it.n) }.orEmpty()
            number.visibility = if (first == null) GONE else VISIBLE
            phone.visibility = if (first == null) INVISIBLE else VISIBLE
            phone.contentDescription = resources.getString(R.string.call_number, contact.name)
            initial.text = contact.name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
            initial.background = views.shape(avatarColors[Math.floorMod(contact.id, avatarColors.size.toLong()).toInt()], 44)
            val photo = contact.photo?.let { key ->
                photos.get(key) ?: runCatching {
                    val bytes = Base64.decode(key, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()?.also { photos.put(key, it) }
            }
            picture.setImageBitmap(photo)
            picture.visibility = if (photo == null) GONE else VISIBLE
            initial.visibility = if (photo == null) VISIBLE else GONE
        }
    }
}
