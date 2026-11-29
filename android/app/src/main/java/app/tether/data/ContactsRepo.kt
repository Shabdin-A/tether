package app.tether.data

import app.tether.core.numberKey

import android.content.ContentProviderOperation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import android.util.Log
import app.tether.core.Contact
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import app.tether.core.Phone as PhoneEntry

class ContactsRepo(private val ctx: Context) {
    private val cr get() = ctx.contentResolver

    /** Hash of every raw contact's (id, version); changes whenever any contact changes. */
    fun version(): String {
        val md = MessageDigest.getInstance("SHA-1")
        try {
            cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.VERSION),
                "${RawContacts.DELETED} = 0", null, RawContacts._ID)?.use { c ->
                while (c.moveToNext()) md.update("${c.getLong(0)}:${c.getLong(1)};".toByteArray())
            }
        } catch (e: SecurityException) {
            return ""
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    fun all(withPhotos: Boolean = true): List<Contact> {
        val byId = LinkedHashMap<Long, Builder>()
        try {
            cr.query(Data.CONTENT_URI,
                arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DISPLAY_NAME_PRIMARY, Data.DATA1, Data.DATA2, Data.DATA3,
                    Data.STARRED, Data.PHOTO_THUMBNAIL_URI),
                "${Data.MIMETYPE} IN (?, ?)", arrayOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE),
                "${Data.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val b = byId.getOrPut(id) {
                        Builder(id, c.getString(2) ?: "", c.getInt(6) != 0, c.getString(7))
                    }
                    when (c.getString(1)) {
                        Phone.CONTENT_ITEM_TYPE -> c.getString(3)?.let { n ->
                            // One entry per number however it is written (update() matches numbers the same way).
                            if (b.phones.none { it.n == n || numberKey(it.n).let { k -> k.isNotEmpty() && k == numberKey(n) } }) b.phones.add(PhoneEntry(n, phoneType(c.getInt(4)), c.getString(5)))
                        }
                        Email.CONTENT_ITEM_TYPE -> c.getString(3)?.let { if (it !in b.emails) b.emails.add(it) }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no contacts permission")
        }
        return byId.values.map { b ->
            Contact(b.id, b.name, b.phones, b.emails, if (withPhotos) b.photo?.let(::thumbnail) else null, b.starred)
        }
    }

    private class Builder(val id: Long, val name: String, val starred: Boolean, val photo: String?) {
        val phones = ArrayList<PhoneEntry>()
        val emails = ArrayList<String>()
    }

    private fun phoneType(t: Int) = PhoneLabels.typeName(t)

    /** Down-scaled JPEG (≤ 96 px), base64. */
    private fun thumbnail(uri: String): String? = try {
        cr.openInputStream(Uri.parse(uri))?.use { s ->
            val bmp = BitmapFactory.decodeStream(s) ?: return null
            val scale = 96f / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 70, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (t: Throwable) {
        null
    }

    /** Star / unstar a contact (Favourites, as in the Phone app). Returns rows changed or -1. */
    fun setStarred(contactId: Long, on: Boolean, dryRun: Boolean = false): Int = try {
        if (dryRun) 0 else cr.update(ContactsContract.Contacts.CONTENT_URI, android.content.ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (on) 1 else 0) },
            "${ContactsContract.Contacts._ID} = ?", arrayOf(contactId.toString()))
    } catch (t: Throwable) {
        android.util.Log.w("Tether", "star refused: ${t.message}"); -1
    }

    fun isStarred(contactId: Long): Boolean? = try {
        cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.STARRED), "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()), null)?.use { if (it.moveToFirst()) it.getInt(0) == 1 else null }
    } catch (t: Throwable) { null }

    /** Debug (adb) clean-up of a throw-away test contact: deletes it only if every number on it is a test number. */
    fun deleteTestContact(contactId: Long): String {
        val c = all(withPhotos = false).firstOrNull { it.id == contactId } ?: return "not found"
        if (c.phones.isEmpty() || c.phones.any { !Regex("""\+?1000000000\d""").matches(it.n.filter { ch -> !ch.isWhitespace() }) }) return "refused: not a test contact"
        val n = cr.delete(ContactsContract.Contacts.CONTENT_URI.buildUpon().appendPath(contactId.toString())
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(), null, null)
        return "deleted $n"
    }

    /** Inserts a device-local contact. Never modifies existing ones. */
    fun add(name: String, phones: List<PhoneEntry>, emails: List<String>, dryRun: Boolean = false): Long {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null).build())
        ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build())
        for (p in phones) { val k = PhoneLabels.forNew(p.type, p.label)
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, p.n).withValue(Phone.TYPE, k.type)
                .withValue(Phone.LABEL, k.label).build()) }
        for (e in emails) ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, e).withValue(Email.TYPE, Email.TYPE_HOME).build())
        if (dryRun) return -ops.size.toLong()
        val res = cr.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawId = res[0].uri?.lastPathSegment?.toLongOrNull() ?: return -1
        cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID), "${RawContacts._ID} = ?", arrayOf(rawId.toString()), null)?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return -1
    }

    /** Updates explicit fields of one validated snapshot. This path never deletes provider rows. */
    @Synchronized
    fun update(previous: Contact, version: String, name: String, phones: List<PhoneEntry>,
               emails: List<String>, dryRun: Boolean = false): String? {
        val id = previous.id
        if (dryRun) return "contact updates require a real provider"
        if (id < 0 || name.isBlank() || version.isBlank()) return "invalid contact snapshot"
        if (phones.size < previous.phones.size || emails.size < previous.emails.size) return "removing contact fields is unsupported"

        data class Raw(val id: Long, val version: Long)
        val membership = "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0"
        val args = arrayOf(id.toString())
        val raws = cr.query(RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.VERSION),
            membership, args, null)?.use { c ->
            buildList<Raw> { while (c.moveToNext()) add(Raw(c.getLong(0), c.getLong(1))) }
        } ?: return "contacts unavailable"
        val writableSelection = "$membership AND ${RawContacts.RAW_CONTACT_IS_READ_ONLY} = 0"
        val writable = cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            writableSelection, args, null)?.use { c ->
            buildSet<Long> { while (c.moveToNext()) add(c.getLong(0)) }
        } ?: return "contact permissions unavailable"
        // Capture guards before checking the Client snapshot; later writes must still match them.
        val current = all(withPhotos = false).singleOrNull { it.id == id }
        if (raws.isEmpty() || !sameContactEditSnapshot(previous, current, version, version())) {
            return "contact snapshot changed; sync and reopen the editor"
        }

        data class Row(val id: Long, val raw: Long, val mime: String, val value: String,
                       val type: Int, val label: String?)
        val rows = cr.query(Data.CONTENT_URI,
            arrayOf(Data._ID, Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3),
            "${Data.CONTACT_ID} = ?", args, null)?.use { c ->
            buildList<Row> {
                while (c.moveToNext()) add(Row(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3).orEmpty(),
                    c.getInt(4), c.getString(5)))
            }
        } ?: return "contact rows unavailable"
        val writableRows = cr.query(Data.CONTENT_URI, arrayOf(Data._ID),
            "${Data.CONTACT_ID} = ? AND ${Data.IS_READ_ONLY} = 0", args, null)?.use { c ->
            buildSet<Long> { while (c.moveToNext()) add(c.getLong(0)) }
        } ?: return "contact row permissions unavailable"
        fun isWritable(row: Row) = row.raw in writable && row.id in writableRows
        if (writable.isEmpty()) return "contact is read-only"
        val destination = raws.singleOrNull()?.id?.takeIf { it in writable }
        val guards = arrayListOf(ContentProviderOperation.newAssertQuery(RawContacts.CONTENT_URI)
            .withSelection(membership, args).withExpectedCount(raws.size).build())
        raws.forEach { raw ->
            val selection = if (raw.id in writable) writableSelection else membership
            guards.add(ContentProviderOperation.newAssertQuery(RawContacts.CONTENT_URI)
                .withSelection("$selection AND ${RawContacts._ID} = ? AND ${RawContacts.VERSION} = ?",
                    args + arrayOf(raw.id.toString(), raw.version.toString()))
                .withExpectedCount(1).build())
        }
        val writes = ArrayList<ContentProviderOperation>()
        val checks = ArrayList<ContentProviderOperation>()
        fun write(row: Row, values: android.content.ContentValues) {
            val target = "${Data._ID} = ? AND ${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Data.IS_READ_ONLY} = 0"
            val rowArgs = arrayOf(row.id.toString(), row.raw.toString(), row.mime)
            writes.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                .withSelection("$target AND ${Data.DATA1} = ?", rowArgs + row.value)
                .withValues(values).withExpectedCount(1).build())
            checks.add(ContentProviderOperation.newAssertQuery(Data.CONTENT_URI)
                .withSelection(target, rowArgs).withValues(values).withExpectedCount(1).build())
        }
        fun insert(raw: Long, mime: String, values: android.content.ContentValues) {
            writes.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValues(values)
                .withValue(Data.RAW_CONTACT_ID, raw).withValue(Data.MIMETYPE, mime).build())
            checks.add(ContentProviderOperation.newAssertQuery(Data.CONTENT_URI)
                .withSelection("${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Data.DATA1} = ?",
                    arrayOf(raw.toString(), mime, values.getAsString(Data.DATA1)))
                .withValues(values).withExpectedCount(1).build())
        }

        if (name != previous.name) {
            val row = rows.singleOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE }
                ?.takeIf { destination == it.raw && isWritable(it) && it.value == previous.name }
                ?: return "cannot identify one writable name row"
            write(row, android.content.ContentValues().apply { put(StructuredName.DISPLAY_NAME, name) })
        }

        for ((index, phone) in phones.withIndex()) {
            val old = previous.phones.getOrNull(index)
            if (old == phone) continue
            if (phone.n.isBlank()) return "empty phone number"
            if (old?.n != phone.n && (phones.count { it.n == phone.n } != 1 ||
                rows.any { it.mime == Phone.CONTENT_ITEM_TYPE && it.value == phone.n })) return "phone number already exists"
            val values = android.content.ContentValues().apply { put(Phone.NUMBER, phone.n) }
            if (old == null) {
                val raw = destination ?: return "cannot choose an account for the new phone number"
                val kind = PhoneLabels.forNew(phone.type, phone.label)
                values.put(Phone.TYPE, kind.type)
                values.put(Phone.LABEL, kind.label)
                insert(raw, Phone.CONTENT_ITEM_TYPE, values)
            } else {
                val row = uniqueWritableContactRow(rows, { it.mime == Phone.CONTENT_ITEM_TYPE && it.value == old.n },
                    ::isWritable) ?: return "phone row is ambiguous or read-only"
                PhoneLabels.forExisting(row.type, row.label, phone.type, phone.label)?.let {
                    values.put(Phone.TYPE, it.type)
                    values.put(Phone.LABEL, it.label)
                }
                write(row, values)
            }
        }
        for ((index, email) in emails.withIndex()) {
            val old = previous.emails.getOrNull(index)
            if (old == email) continue
            if (email.isBlank() || emails.count { it.equals(email, true) } != 1 ||
                rows.any { it.mime == Email.CONTENT_ITEM_TYPE && it.value != old && it.value.equals(email, true) }) {
                return "empty or duplicate email"
            }
            val values = android.content.ContentValues().apply { put(Email.ADDRESS, email) }
            if (old == null) {
                val raw = destination ?: return "cannot choose an account for the new email"
                values.put(Email.TYPE, Email.TYPE_HOME)
                insert(raw, Email.CONTENT_ITEM_TYPE, values)
            } else {
                val row = rows.singleOrNull { it.mime == Email.CONTENT_ITEM_TYPE && it.value.equals(old, true) }
                    ?.takeIf { isWritable(it) && it.value == old }
                    ?: return "email row is ambiguous or read-only"
                write(row, values)
            }
        }
        // Verify the new values inside the transaction so a failed assertion rolls back the writes.
        guards.addAll(writes)
        guards.addAll(checks)
        cr.applyBatch(ContactsContract.AUTHORITY, guards)
        return null
    }

    internal fun preparePhoneDeletion(contact: Long, number: String): ContactPhoneTarget {
        if (contact < 0 || number.isBlank()) throw ContactPhoneDeleteFailure("stale")
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw ContactPhoneDeleteFailure("permission_denied")
        }
        val mime = Phone.CONTENT_ITEM_TYPE
        val values = arrayOf(contact.toString(), mime, number)
        val selection = "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Phone.NUMBER} = ?"
        val target = cr.query(Data.CONTENT_URI, arrayOf(Data._ID, Data.RAW_CONTACT_ID, Data.DATA_VERSION),
            selection, values, null)?.use { c ->
            if (!c.moveToFirst()) throw ContactPhoneDeleteFailure("stale")
            val row = c.getLong(0)
            val raw = c.getLong(1)
            val version = c.getLong(2)
            if (c.moveToNext()) throw ContactPhoneDeleteFailure("ambiguous")
            ContactPhoneTarget(contact, raw, row, mime, number, version, 0)
        } ?: throw ContactPhoneDeleteFailure("provider_error")
        val rawVersion = cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.VERSION),
            "${RawContacts._ID} = ? AND ${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0 AND ${RawContacts.RAW_CONTACT_IS_READ_ONLY} = 0",
            arrayOf(target.raw.toString(), contact.toString()), null)?.use { c ->
            if (!c.moveToFirst()) throw ContactPhoneDeleteFailure("read_only")
            c.getLong(0)
        } ?: throw ContactPhoneDeleteFailure("provider_error")
        cr.query(Data.CONTENT_URI, arrayOf(Data._ID), "$selection AND ${Data._ID} = ? AND ${Data.IS_READ_ONLY} = 0",
            values + target.row.toString(), null)?.use {
            if (!it.moveToFirst()) throw ContactPhoneDeleteFailure("read_only")
        } ?: throw ContactPhoneDeleteFailure("provider_error")
        return target.copy(rawVersion = rawVersion)
    }

    @Synchronized
    internal fun deletePreparedPhone(target: ContactPhoneTarget) {
        if (preparePhoneDeletion(target.contact, target.number) != target) throw ContactPhoneDeleteFailure("stale")
        val row = "${Data._ID} = ?"
        val rowArgs = arrayOf(target.row.toString())
        val match = "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Phone.NUMBER} = ?"
        val matchArgs = arrayOf(target.contact.toString(), target.mime, target.number)
        val batch = arrayListOf(
            ContentProviderOperation.newAssertQuery(RawContacts.CONTENT_URI)
                .withSelection("${RawContacts._ID} = ? AND ${RawContacts.CONTACT_ID} = ? AND ${RawContacts.VERSION} = ? AND ${RawContacts.DELETED} = 0 AND ${RawContacts.RAW_CONTACT_IS_READ_ONLY} = 0",
                    arrayOf(target.raw.toString(), target.contact.toString(), target.rawVersion.toString()))
                .withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(Data.CONTENT_URI)
                .withSelection(match, matchArgs).withExpectedCount(1).build(),
            ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection("$row AND ${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Phone.NUMBER} = ? AND ${Data.DATA_VERSION} = ? AND ${Data.IS_READ_ONLY} = 0",
                    rowArgs + arrayOf(target.raw.toString(), target.mime, target.number, target.dataVersion.toString()))
                .withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(Data.CONTENT_URI)
                .withSelection(row, rowArgs).withExpectedCount(0).build(),
        )
        val result = try {
            cr.applyBatch(ContactsContract.AUTHORITY, batch)
        } catch (e: android.content.OperationApplicationException) {
            if (preparePhoneDeletion(target.contact, target.number) != target) throw ContactPhoneDeleteFailure("stale")
            throw e
        }
        if (result.getOrNull(2)?.count != 1) throw ContactPhoneDeleteFailure("unverified")
        val absent = runCatching {
            cr.query(Data.CONTENT_URI, arrayOf(Data._ID), row, rowArgs, null)?.use { !it.moveToFirst() }
        }.getOrNull()
        if (absent != true) throw ContactPhoneDeleteFailure("unverified")
    }

    /** Name lookup for a number (used for call.state). */
    fun nameFor(number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        cr.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    companion object { const val TAG = "Tether" }
}

internal fun sameContactEditSnapshot(previous: Contact, current: Contact?, expectedVersion: String, version: String): Boolean =
    expectedVersion.isNotBlank() && expectedVersion == version && current != null && previous.id == current.id &&
        previous.name == current.name && previous.phones == current.phones && previous.emails == current.emails

internal fun <T> uniqueWritableContactRow(rows: List<T>, match: (T) -> Boolean, writable: (T) -> Boolean): T? =
    rows.singleOrNull(match)?.takeIf(writable)
