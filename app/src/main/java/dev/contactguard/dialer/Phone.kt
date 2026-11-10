package dev.contactguard.dialer

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.RawContacts
import android.telephony.PhoneNumberUtils
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import java.util.Locale
import android.provider.ContactsContract.CommonDataKinds.Phone as P

data class Contact(val id: Long, val lookupKey: String, val name: String, val photo: String?, val starred: Boolean)
data class Match(val contact: Contact, val number: String, val label: String)
data class AccountRef(val type: String?, val name: String?, val label: String)
data class Detail(val contact: Contact, val phones: List<Match>, val emails: List<String>, val sources: List<String>, val rawIds: List<Long>)

fun formatNumber(n: String): String = PhoneNumberUtils.formatNumber(n, Locale.getDefault().country) ?: n

/** Thin, synchronous reads/writes over the contacts provider. Call from a background dispatcher. */
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

    /** Every (contact, number) pair: powers search and the detail screen. */
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

    fun detail(ctx: Context, id: Long): Detail? {
        val cr = ctx.contentResolver
        val contact = cr.query(
            ContentUris.withAppendedId(Contacts.CONTENT_URI, id),
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

    /** Where contacts live: phone storage, every editable account (all Google accounts), and each SIM. */
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

    /** The one approved way to delete: the guard knows about it, and Google sync removes it from the cloud too. */
    fun delete(ctx: Context, d: Detail) {
        Guard.markOwnDelete(ctx, d.rawIds)
        ctx.contentResolver.delete(Contacts.getLookupUri(d.contact.id, d.contact.lookupKey), null, null)
    }

    /** Opens the phone's own dialer (Google Phone) with the number filled in. */
    fun dial(ctx: Context, number: String) =
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun sms(ctx: Context, number: String) =
        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private val photos = LruCache<String, ImageBitmap>(150)

    fun photo(ctx: Context, uri: String): ImageBitmap? = photos.get(uri) ?: try {
        ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()?.also { photos.put(uri, it) }
    } catch (_: Exception) { null }
}
