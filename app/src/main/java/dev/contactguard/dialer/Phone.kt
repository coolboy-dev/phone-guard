package dev.contactguard.dialer

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.PhoneLookup
import android.provider.ContactsContract.RawContacts
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import android.provider.ContactsContract.CommonDataKinds.Phone as P

data class Contact(val id: Long, val lookupKey: String, val name: String, val photo: String?, val starred: Boolean)
data class Match(val contact: Contact, val number: String, val label: String)
data class Recent(val ids: List<Long>, val number: String, val name: String?, val photo: String?, val type: Int, val date: Long, val count: Int, val duration: Long)
data class AccountRef(val type: String?, val name: String?, val label: String)
data class Detail(val contact: Contact, val phones: List<Match>, val emails: List<String>, val sources: List<String>, val rawIds: List<Long>)

/** Thin, synchronous reads/writes over the system providers. Call from a background dispatcher. */
object Phone {
    fun can(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun contactOf(c: android.database.Cursor, id: Int, key: Int, name: Int, photo: Int, star: Int) =
        Contact(c.getLong(id), c.getString(key).orEmpty(), c.getString(name).orEmpty(), c.getString(photo), c.getInt(star) == 1)

    fun contacts(ctx: Context, account: AccountRef? = null): List<Contact> {
        if (!can(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        if (account?.type == RawSnap.SIM_TYPE) return sim(ctx, account)
        val only = account?.let { a ->
            val (sel, args) = if (a.type == null) "${RawContacts.ACCOUNT_TYPE} IS NULL AND ${RawContacts.DELETED}=0" to null
            else "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.DELETED}=0" to arrayOf(a.type, a.name)
            ctx.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID), sel, args, null)
                ?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
        }
        return ctx.contentResolver.query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_THUMBNAIL_URI, Contacts.STARRED),
            null, null, Contacts.SORT_KEY_PRIMARY,
        )?.use { c ->
            buildList { while (c.moveToNext()) contactOf(c, 0, 1, 2, 3, 4).takeIf { only == null || it.id in only }?.let(::add) }
        }.orEmpty()
    }

    /** SIM records shown as contacts with negative ids (not in the contacts provider on stock-like ROMs). */
    private fun sim(ctx: Context, a: AccountRef) = Guard.readSim(ctx).first.filter { it.accountName == a.name }
        .map { Contact(it.rawId, "sim:" + it.phones.firstOrNull().orEmpty(), it.name.ifBlank { it.rows.getOrNull(1)?.get("data1").orEmpty() }, null, false) }

    fun simNumber(ctx: Context, id: Long) = Guard.readSim(ctx).first.firstOrNull { it.rawId == id }?.rows?.getOrNull(1)?.get("data1")

    /** Every (contact, number) pair: powers search, keypad matching and favorites. */
    fun numbers(ctx: Context): List<Match> {
        if (!can(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        return ctx.contentResolver.query(
            P.CONTENT_URI,
            arrayOf(P.CONTACT_ID, P.LOOKUP_KEY, P.DISPLAY_NAME_PRIMARY, P.PHOTO_THUMBNAIL_URI, P.STARRED, P.NUMBER, P.TYPE, P.LABEL),
            null, null, P.SORT_KEY_PRIMARY,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) add(Match(contactOf(c, 0, 1, 2, 3, 4), c.getString(5).orEmpty(),
                    P.getTypeLabel(ctx.resources, c.getInt(6), c.getString(7)).toString()))
            }
        }.orEmpty().distinctBy { it.contact.id to normPhone(it.number) }
    }

    fun favorites(ctx: Context) = contacts(ctx).filter { it.starred }

    fun recents(ctx: Context): List<Recent> {
        if (!can(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<Recent>()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "600").build(),
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.CACHED_PHOTO_URI, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val num = c.getString(1).orEmpty()
                val date = c.getLong(5)
                val last = out.lastOrNull()
                // Google-style grouping: consecutive calls with the same number on the same day.
                if (last != null && normPhone(last.number) == normPhone(num) && dayOf(last.date) == dayOf(date))
                    out[out.lastIndex] = last.copy(ids = last.ids + c.getLong(0), count = last.count + 1)
                else out += Recent(listOf(c.getLong(0)), num, c.getString(2)?.takeIf { it.isNotBlank() }, c.getString(3), c.getInt(4), date, 1, c.getLong(6))
            }
        }
        return out
    }

    private fun dayOf(t: Long) = (t + java.util.TimeZone.getDefault().getOffset(t)) / 86_400_000L

    fun markMissedRead(ctx: Context) {
        if (!can(ctx, Manifest.permission.WRITE_CALL_LOG)) return
        try {
            ctx.contentResolver.update(CallLog.Calls.CONTENT_URI, ContentValues().apply { put(CallLog.Calls.NEW, 0); put(CallLog.Calls.IS_READ, 1) },
                "${CallLog.Calls.NEW}=1 AND ${CallLog.Calls.TYPE}=${CallLog.Calls.MISSED_TYPE}", null)
            ctx.getSystemService(TelecomManager::class.java).cancelMissedCallsNotification()
        } catch (_: Exception) { }
    }

    fun deleteCalls(ctx: Context, ids: List<Long>) = try {
        ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${ids.joinToString()})", null)
    } catch (_: Exception) { 0 }

    private val lookupCache = LruCache<String, Contact>(200)

    fun lookup(ctx: Context, number: String): Contact? {
        if (number.isBlank() || !can(ctx, Manifest.permission.READ_CONTACTS)) return null
        lookupCache.get(number)?.let { return it }
        val found = ctx.contentResolver.query(
            Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(PhoneLookup.CONTACT_ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI, PhoneLookup.STARRED),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) contactOf(c, 0, 1, 2, 3, 4) else null }
            // PhoneLookup misses country-code variants (+91… vs local) when the network country differs.
            ?: normPhone(number).takeIf { it.length >= 7 }?.let { n -> numbers(ctx).firstOrNull { normPhone(it.number) == n }?.contact }
        return found?.also { lookupCache.put(number, it) }
    }

    fun detail(ctx: Context, id: Long): Detail? {
        val cr = ctx.contentResolver
        val contact = cr.query(
            ContentUrisCompat(Contacts.CONTENT_URI, id),
            arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_URI, Contacts.STARRED), null, null, null,
        )?.use { c -> if (c.moveToFirst()) contactOf(c, 0, 1, 2, 3, 4) else null } ?: return null
        val phones = numbers(ctx).filter { it.contact.id == id }
        val emails = cr.query(Email.CONTENT_URI, arrayOf(Email.ADDRESS), "${Email.CONTACT_ID}=?", arrayOf(id.toString()), null)
            ?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let(::add) } }.orEmpty().distinct()
        val raws = cr.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(id.toString()), null)
            ?.use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) } }.orEmpty()
        return Detail(contact, phones, emails, raws.map { Guard.accountLabel(ctx, it.second, it.third) }.distinct(), raws.map { it.first })
    }

    @Suppress("FunctionName")
    private fun ContentUrisCompat(base: Uri, id: Long) = android.content.ContentUris.withAppendedId(base, id)

    /** Places a contact can be saved to: phone storage, every editable account (all Google accounts), and each SIM. */
    fun accounts(ctx: Context): List<AccountRef> {
        val editable = Guard.editableTypes()
        val out = LinkedHashMap<String, AccountRef>()
        out["|"] = AccountRef(null, null, "Phone storage")
        if (can(ctx, Manifest.permission.READ_CONTACTS)) {
            try {
                AccountManager.get(ctx).accounts.filter { it.type in editable }
                    .forEach { out["${it.type}|${it.name}"] = AccountRef(it.type, it.name, it.name) }
            } catch (_: Exception) { }
            ctx.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED}=0", null, null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val t = c.getString(0) ?: continue
                        if (t in editable) out.getOrPut("$t|${c.getString(1)}") { AccountRef(t, c.getString(1), Guard.accountLabel(ctx, t, c.getString(1))) }
                    }
                }
        }
        if (Build.VERSION.SDK_INT >= 31) Guard.activeSubs(ctx).forEach {
            val id = it.subscriptionId.toString()
            out["sim|$id"] = AccountRef(RawSnap.SIM_TYPE, id, Guard.accountLabel(ctx, RawSnap.SIM_TYPE, id))
        }
        return out.values.toList()
    }

    fun setStarred(ctx: Context, id: Long, starred: Boolean) {
        ctx.contentResolver.update(ContentUrisCompat(Contacts.CONTENT_URI, id), ContentValues().apply { put(Contacts.STARRED, if (starred) 1 else 0) }, null, null)
    }

    fun delete(ctx: Context, d: Detail) {
        Guard.markOwnDelete(ctx, d.rawIds)
        ctx.contentResolver.delete(Contacts.getLookupUri(d.contact.id, d.contact.lookupKey), null, null)
    }

    data class Draft(val first: String, val last: String, val phones: List<Pair<String, Int>>, val emails: List<String>)

    fun draftOf(ctx: Context, rawId: Long): Draft {
        var first = ""; var last = ""
        val phones = ArrayList<Pair<String, Int>>(); val emails = ArrayList<String>()
        ctx.contentResolver.query(Data.CONTENT_URI, arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3),
            "${Data.RAW_CONTACT_ID}=?", arrayOf(rawId.toString()), null)?.use { c ->
            while (c.moveToNext()) when (c.getString(0)) {
                StructuredName.CONTENT_ITEM_TYPE -> { first = c.getString(2).orEmpty(); last = c.getString(3).orEmpty() }
                P.CONTENT_ITEM_TYPE -> phones += c.getString(1).orEmpty() to c.getInt(2)
                Email.CONTENT_ITEM_TYPE -> emails += c.getString(1).orEmpty()
            }
        }
        return Draft(first, last, phones, emails)
    }

    /** Creates (rawId == null) or rewrites a contact's name, numbers and emails. */
    fun save(ctx: Context, rawId: Long?, account: AccountRef, d: Draft): Boolean {
        val phones = d.phones.filter { it.first.isNotBlank() }
        val emails = d.emails.filter { it.isNotBlank() }
        val full = "${d.first} ${d.last}".trim()
        if (rawId == null && account.type == RawSnap.SIM_TYPE)
            return Guard.simInsert(ctx, account.name!!.toInt(), full, phones.firstOrNull()?.first ?: return false)
        val ops = ArrayList<ContentProviderOperation>()
        fun data() = if (rawId == null) ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
        else ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.RAW_CONTACT_ID, rawId)
        if (rawId == null) ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, account.type).withValue(RawContacts.ACCOUNT_NAME, account.name).build()
        else ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection("${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE} IN (?,?,?)",
                arrayOf(rawId.toString(), StructuredName.CONTENT_ITEM_TYPE, P.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE)).build()
        ops += data().withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.GIVEN_NAME, d.first.trim()).withValue(StructuredName.FAMILY_NAME, d.last.trim()).build()
        phones.forEach { (n, t) -> ops += data().withValue(Data.MIMETYPE, P.CONTENT_ITEM_TYPE).withValue(P.NUMBER, n.trim()).withValue(P.TYPE, t).build() }
        emails.forEach { ops += data().withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, it.trim()).withValue(Email.TYPE, Email.TYPE_HOME).build() }
        return try { ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops); lookupCache.evictAll(); true } catch (_: Exception) { false }
    }

    fun block(ctx: Context, number: String) = try {
        BlockedNumberContract.canCurrentUserBlockNumbers(ctx) &&
            ctx.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }) != null
    } catch (_: Exception) { false }

    fun call(ctx: Context, number: String) {
        if (number.isBlank()) return
        if (can(ctx, Manifest.permission.CALL_PHONE))
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        else ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun voicemail(ctx: Context) {
        if (can(ctx, Manifest.permission.CALL_PHONE))
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("voicemail", "", null), Bundle())
    }

    /** *#*#code#*#* : hands the code to the system like the stock dialer does. */
    fun specialCode(ctx: Context, s: String): Boolean {
        val m = Regex("^\\*#\\*#(.+)#\\*#\\*$").find(s) ?: return false
        return try { ctx.getSystemService(TelephonyManager::class.java).sendDialerSpecialCode(m.groupValues[1]); true } catch (_: Exception) { false }
    }

    fun sms(ctx: Context, number: String) =
        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private val photos = LruCache<String, ImageBitmap>(150)

    fun photo(ctx: Context, uri: String): ImageBitmap? = photos.get(uri) ?: try {
        ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()?.also { photos.put(uri, it) }
    } catch (_: Exception) { null }

    /** Phone keypad letters → digits, for name matching from the keypad. */
    fun t9(s: String) = buildString {
        for (ch in s.lowercase()) append(
            when (ch) {
                in "abc" -> '2'; in "def" -> '3'; in "ghi" -> '4'; in "jkl" -> '5'; in "mno" -> '6'
                in "pqrs" -> '7'; in "tuv" -> '8'; in "wxyz" -> '9'; in '0'..'9' -> ch; ' ' -> ' '; else -> ""
            },
        )
    }

    fun keypadMatches(all: List<Match>, digits: String): List<Match> {
        if (digits.isEmpty()) return emptyList()
        val plain = digits.filter { it.isDigit() }
        return all.filter { m ->
            val words = t9(m.contact.name).split(' ')
            (plain.isNotEmpty() && (words.any { it.startsWith(plain) } || t9(m.contact.name).replace(" ", "").startsWith(plain))) ||
                m.number.filter { it.isDigit() }.contains(plain)
        }.take(30)
    }
}
