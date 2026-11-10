package dev.contactguard.dialer

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.provider.SimPhonebookContract.ElementaryFiles
import android.provider.SimPhonebookContract.SimRecords
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/** Snapshots every contact source, spots deletions, logs the context around them, and restores. */
object Guard {
    data class Event(
        val id: Long,
        val contacts: List<RawSnap>,
        val mass: Boolean,
        val diag: List<String>,
        val status: String, // pending | restored | dismissed | returned
    )

    /** Bumped on every change so the UI reloads. */
    val version = MutableStateFlow(0)
    private val lock = Any()
    private const val GOOGLE = "com.google"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)
    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // ---------- reading ----------

    /** Account types the user can edit: phone storage, SIM, and accounts whose sync adapter uploads (Google, Exchange…). Skips WhatsApp & co, which rebuild their rows constantly. */
    fun editableTypes(): Set<String?> =
        ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
            .mapTo(HashSet<String?>()) { it.accountType } + setOf(null, RawSnap.SIM_TYPE)

    private fun isEditable(type: String?, editable: Set<String?>) =
        type in editable || type == null || type.contains("local", true) || type.contains("phone", true) || type.contains("sim", true)

    /** Live raw contacts, plus the ids flagged DELETED=1 (deleted on this phone, sync not done yet). */
    fun readLive(ctx: Context): Pair<List<RawSnap>, Set<Long>> {
        val cr = ctx.contentResolver
        val editable = editableTypes()
        val heads = HashMap<Long, RawSnap>()
        val marked = HashSet<Long>()
        cr.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME, RawContacts.DELETED, RawContacts.STARRED, RawContacts.DISPLAY_NAME_PRIMARY),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(1)
                if (!isEditable(type, editable)) continue
                val id = c.getLong(0)
                if (c.getInt(3) == 1) marked += id
                else heads[id] = RawSnap(id, type, c.getString(2), c.getString(5).orEmpty(), c.getInt(4) == 1, emptyList())
            }
        }
        val rows = HashMap<Long, MutableList<Map<String, String>>>()
        val cols = arrayOf(Data.RAW_CONTACT_ID, Data.MIMETYPE) + (1..15).map { "data$it" }
        cr.query(Data.CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val rid = c.getLong(0)
                if (rid !in heads) continue
                val mime = c.getString(1) ?: continue
                // Standard kinds only: photos are blobs, group ids and app-specific rows don't survive a move.
                if (!mime.startsWith("vnd.android.cursor.item/") || mime == Photo.CONTENT_ITEM_TYPE || mime == GroupMembership.CONTENT_ITEM_TYPE) continue
                val m = HashMap<String, String>()
                m["mimetype"] = mime
                for (i in 2 until cols.size) if (c.getType(i) != Cursor.FIELD_TYPE_BLOB) c.getString(i)?.let { m[cols[i]] = it }
                rows.getOrPut(rid) { ArrayList() } += m
            }
        }
        return heads.values.map { it.copy(rows = rows[it.rawId].orEmpty()) } to marked
    }

    fun activeSubs(ctx: Context): List<SubscriptionInfo> =
        if (!has(ctx, Manifest.permission.READ_PHONE_STATE)) emptyList()
        else try { ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty() } catch (_: Exception) { emptyList() }

    /** SIM records of every active SIM (API 31+), and the sub ids that read OK. */
    fun readSim(ctx: Context): Pair<List<RawSnap>, Set<String>> {
        if (Build.VERSION.SDK_INT < 31) return emptyList<RawSnap>() to emptySet()
        val out = ArrayList<RawSnap>()
        val ok = HashSet<String>()
        for (sub in activeSubs(ctx)) try {
            ctx.contentResolver.query(
                SimRecords.getContentUri(sub.subscriptionId, ElementaryFiles.EF_ADN),
                arrayOf(SimRecords.RECORD_NUMBER, SimRecords.NAME, SimRecords.PHONE_NUMBER), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    val num = c.getString(2).orEmpty()
                    out += RawSnap(
                        -(sub.subscriptionId * 100_000L + c.getInt(0)), RawSnap.SIM_TYPE, sub.subscriptionId.toString(), name, false,
                        listOf(mapOf("mimetype" to RawSnap.NAME_MIME, "data1" to name), mapOf("mimetype" to RawSnap.PHONE_MIME, "data1" to num, "data2" to "2")),
                    )
                }
                ok += sub.subscriptionId.toString()
            }
        } catch (_: Exception) { /* SIM busy or unsupported: skip it this round */ }
        return out to ok
    }

    // ---------- the check ----------

    fun check(ctx: Context) = synchronized(lock) {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return@synchronized
        val p = prefs(ctx)
        val (live, marked) = readLive(ctx)
        val (sim, simOk) = readSim(ctx)
        val all = live + sim
        val old = loadSnaps(File(ctx.filesDir, "snapshot.json"))
        val events = loadEvents(ctx).toMutableList()

        // Pending events whose contacts all came back by themselves (sync glitch) close on their own.
        for (i in events.indices) {
            val e = events[i]
            if (e.status == "pending" && e.contacts.all { stillPresent(it, all) || all.any { l -> l.rawId == it.rawId } })
                events[i] = e.copy(status = "returned")
        }

        if (old != null) {
            val considered = old.filter { it.accountType != RawSnap.SIM_TYPE || it.accountName in simOk }
            val own = p.getStringSet("own", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
            val pendingIds = events.filter { it.status == "pending" }.flatMap { e -> e.contacts.map { it.rawId } }.toSet()
            val d = diff(considered, all, own + pendingIds)
            p.edit().remove("own").apply()
            if (d.lost.isNotEmpty()) {
                val ev = Event(System.currentTimeMillis(), d.lost, d.massAccounts.isNotEmpty(), diagnose(ctx, d, marked, old, all, p.getLong("lastCheck", 0)), "pending")
                events.add(0, ev)
                saveEvents(ctx, events)
                if (p.getBoolean("autoRestore", false) && !ev.mass) restore(ctx, ev.id) else notifyLoss(ctx, ev)
            } else saveEvents(ctx, events)
        } else saveEvents(ctx, events)
        // SIMs not in the phone right now keep their old records in the baseline.
        val keep = old.orEmpty().filter { it.accountType == RawSnap.SIM_TYPE && it.accountName !in simOk }
        saveSnaps(File(ctx.filesDir, "snapshot.json"), all + keep)
        p.edit().putLong("lastCheck", System.currentTimeMillis()).putInt("count", all.size).apply()
        if (p.getString("lastExport", "") != LocalDate.now().toString()) export(ctx)
        version.value++
    }

    private fun diagnose(ctx: Context, d: Diff, marked: Set<Long>, old: List<RawSnap>, live: List<RawSnap>, since: Long): List<String> {
        val out = ArrayList<String>()
        for ((_, list) in d.lost.groupBy { it.accountKey }) {
            val s = list[0]
            val sync = syncOn(s.accountType, s.accountName)
            out += "${list.size} from ${accountLabel(ctx, s.accountType, s.accountName)}" +
                (sync?.let { if (it) " (contact sync on)" else " (contact sync OFF)" } ?: "")
        }
        val onPhone = d.lost.count { it.rawId in marked }
        val viaSync = d.lost.count { it.rawId !in marked && it.accountType == GOOGLE }
        val local = d.lost.count { it.rawId !in marked && it.accountType != GOOGLE }
        if (onPhone > 0) out += "$onPhone deleted on this phone by an app"
        if (viaSync > 0) out += "$viaSync removed through Google sync: deleted on another device or contacts.google.com, or removed by Google on this phone"
        if (local > 0) out += "$local deleted directly from phone storage or SIM"
        val liveAccts = live.map { it.accountKey }.toSet()
        old.map { it.accountType to it.accountName }.distinct()
            .filter { (t, n) -> t != RawSnap.SIM_TYPE && "${t.orEmpty()}|${n.orEmpty()}" !in liveAccts }
            .forEach { (t, n) -> out += "Account no longer on the phone: ${accountLabel(ctx, t, n)}" }
        deletedAt(ctx, since)?.let { out += "Deleted at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" }
        val updated = recentlyUpdated(ctx, 6 * 3_600_000L)
        if (updated.isNotEmpty()) out += "Updated in the last 6 h: " + updated.joinToString()
        val open = recentApps(ctx, 15 * 60_000L)
        if (open != null) out += if (open.isEmpty()) "No apps opened in the 15 min before" else "Open in the 15 min before: " + open.joinToString()
        return out
    }

    private fun deletedAt(ctx: Context, since: Long): Long? = try {
        ctx.contentResolver.query(
            ContactsContract.DeletedContacts.CONTENT_URI, arrayOf(ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP),
            "${ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP} > ?", arrayOf(since.toString()),
            "${ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    } catch (_: Exception) { null }

    private fun label(ctx: Context, pkg: String) = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun packages(ctx: Context, flags: Int): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") ctx.packageManager.getInstalledPackages(flags)

    private fun recentlyUpdated(ctx: Context, window: Long): List<String> {
        val now = System.currentTimeMillis()
        return packages(ctx, 0).filter { now - it.lastUpdateTime < window && it.packageName != ctx.packageName }
            .sortedByDescending { it.lastUpdateTime }.take(8).map { label(ctx, it.packageName) }
    }

    @Suppress("DEPRECATION")
    fun usageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Apps brought to the foreground in the window, or null when usage access isn't granted. */
    private fun recentApps(ctx: Context, window: Long): List<String>? {
        if (!usageAccess(ctx)) return null
        val now = System.currentTimeMillis()
        val ev = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(now - window, now)
        val seen = LinkedHashSet<String>()
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName != ctx.packageName) seen += e.packageName
        }
        return seen.toList().takeLast(8).map { label(ctx, it) }
    }

    /** Installed apps allowed to change contacts: the suspects list. */
    fun contactWriters(ctx: Context): List<String> = packages(ctx, PackageManager.GET_PERMISSIONS)
        .filter { pi ->
            pi.packageName != ctx.packageName && pi.packageName != "com.android.providers.contacts" &&
                pi.requestedPermissions?.indices?.any { i ->
                    pi.requestedPermissions!![i] == Manifest.permission.WRITE_CONTACTS &&
                        (pi.requestedPermissionsFlags!![i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                } == true
        }.map { label(ctx, it.packageName) }.sorted()

    // ---------- accounts ----------

    /** null for accounts without sync (phone storage, SIM). */
    fun syncOn(type: String?, name: String?): Boolean? {
        if (type == null || name == null || type == RawSnap.SIM_TYPE) return null
        return ContentResolver.getMasterSyncAutomatically() && ContentResolver.getSyncAutomatically(Account(name, type), ContactsContract.AUTHORITY)
    }

    fun accountLabel(ctx: Context, type: String?, name: String?): String = when {
        type == null -> "Phone storage"
        type == RawSnap.SIM_TYPE -> activeSubs(ctx).firstOrNull { it.subscriptionId.toString() == name }
            ?.let { "SIM ${it.simSlotIndex + 1} (${it.displayName})" } ?: "SIM"
        type.contains("sim", true) -> "SIM"
        else -> name ?: type
    }

    /** Where a restored contact goes: its old account if still here with sync on, else phone storage. */
    private fun target(ctx: Context, s: RawSnap): Pair<String?, String?> {
        val t = s.accountType ?: return null to null
        if (t == RawSnap.SIM_TYPE) return null to null
        val present = try { AccountManager.get(ctx).getAccountsByType(t).any { it.name == s.accountName } } catch (_: Exception) { false } ||
            readLive(ctx).first.any { it.accountKey == s.accountKey }
        if (!present) return null to null
        if (syncOn(t, s.accountName) == false) return null to null // would just be removed again
        return t to s.accountName
    }

    // ---------- restore ----------

    fun restore(ctx: Context, eventId: Long): Int = synchronized(lock) {
        val events = loadEvents(ctx).toMutableList()
        val i = events.indexOfFirst { it.id == eventId }
        if (i < 0) return@synchronized 0
        val all = readLive(ctx).first + readSim(ctx).first
        var n = 0
        for (s in events[i].contacts) if (stillPresent(s, all) || insert(ctx, s)) n++
        events[i] = events[i].copy(status = "restored")
        saveEvents(ctx, events)
        NotificationManagerCompat.from(ctx).cancel(eventId.toInt())
        version.value++
        n
    }

    fun dismiss(ctx: Context, eventId: Long) = synchronized(lock) {
        saveEvents(ctx, loadEvents(ctx).map { if (it.id == eventId) it.copy(status = "dismissed") else it })
        NotificationManagerCompat.from(ctx).cancel(eventId.toInt())
        version.value++
    }

    private fun insert(ctx: Context, s: RawSnap): Boolean {
        val phone = s.rows.firstOrNull { it["mimetype"] == RawSnap.PHONE_MIME }?.get("data1")
        if (s.accountType == RawSnap.SIM_TYPE && Build.VERSION.SDK_INT >= 31 && phone != null) {
            val subs = activeSubs(ctx).map { it.subscriptionId }
            val sub = s.accountName?.toIntOrNull()?.takeIf { it in subs } ?: subs.firstOrNull()
            if (sub != null && simInsert(ctx, sub, s.name, phone)) return true
        }
        val (type, name) = target(ctx, s)
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, type).withValue(RawContacts.ACCOUNT_NAME, name)
                .withValue(RawContacts.STARRED, if (s.starred) 1 else 0).build(),
        )
        for (row in s.rows) {
            val b = ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
            row.forEach { (k, v) -> b.withValue(k, v) }
            ops += b.build()
        }
        return try { ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops); true } catch (_: Exception) { false }
    }

    fun simInsert(ctx: Context, sub: Int, name: String, number: String): Boolean = Build.VERSION.SDK_INT >= 31 && try {
        val v = ContentValues().apply { put(SimRecords.NAME, name); put(SimRecords.PHONE_NUMBER, number) }
        ctx.contentResolver.insert(SimRecords.getContentUri(sub, ElementaryFiles.EF_ADN), v) != null
    } catch (_: Exception) { false }

    /** Our own deletes are not alarms. */
    fun markOwnDelete(ctx: Context, rawIds: Collection<Long>) {
        val p = prefs(ctx)
        p.edit().putStringSet("own", p.getStringSet("own", emptySet())!! + rawIds.map { it.toString() }).apply()
    }

    // ---------- backups ----------

    /** Writes today's .vcf into the folder the user picked; keeps the last 14. Returns the file name. */
    fun export(ctx: Context): String? {
        val tree = prefs(ctx).getString("folder", null) ?: return null
        return try {
            val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: return null
            val name = "contacts-${LocalDate.now()}.vcf"
            dir.findFile(name)?.delete()
            val f = dir.createFile("text/x-vcard", name) ?: return null
            val cr = ctx.contentResolver
            cr.openOutputStream(f.uri)?.use { out ->
                cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use { c ->
                    while (c.moveToNext()) try {
                        cr.openInputStream(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, c.getString(0)))?.use { it.copyTo(out) }
                    } catch (_: Exception) { }
                }
            }
            dir.listFiles().filter { it.name?.startsWith("contacts-") == true }.sortedByDescending { it.name }.drop(14).forEach { it.delete() }
            prefs(ctx).edit().putString("lastExport", LocalDate.now().toString()).apply()
            f.name
        } catch (_: Exception) { null }
    }

    // ---------- storage ----------

    private fun RawSnap.json() = JSONObject().put("id", rawId).put("t", accountType ?: JSONObject.NULL).put("a", accountName ?: JSONObject.NULL)
        .put("n", name).put("s", starred).put("r", JSONArray(rows.map { JSONObject(it) }))

    private fun JSONObject.snap(): RawSnap {
        val r = getJSONArray("r")
        return RawSnap(
            getLong("id"), optString("t").takeUnless { isNull("t") }, optString("a").takeUnless { isNull("a") }, getString("n"), getBoolean("s"),
            (0 until r.length()).map { i -> r.getJSONObject(i).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } },
        )
    }

    private fun saveSnaps(f: File, s: List<RawSnap>) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(JSONArray(s.map { it.json() }).toString())
        tmp.renameTo(f) // atomic: a crash never leaves a half-written baseline
    }

    fun loadSnaps(f: File): List<RawSnap>? = if (!f.exists()) null else try {
        JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it).snap() } }
    } catch (_: Exception) { null }

    fun loadEvents(ctx: Context): List<Event> {
        val f = File(ctx.filesDir, "events.json")
        if (!f.exists()) return emptyList()
        return try {
            JSONArray(f.readText()).let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                    val c = o.getJSONArray("c")
                    val d = o.getJSONArray("d")
                    Event(o.getLong("id"), (0 until c.length()).map { c.getJSONObject(it).snap() }, o.getBoolean("m"),
                        (0 until d.length()).map { d.getString(it) }, o.getString("st"))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveEvents(ctx: Context, e: List<Event>) {
        val f = File(ctx.filesDir, "events.json")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(JSONArray(e.take(100).map { ev ->
            JSONObject().put("id", ev.id).put("c", JSONArray(ev.contacts.map { it.json() })).put("m", ev.mass)
                .put("d", JSONArray(ev.diag)).put("st", ev.status)
        }).toString())
        tmp.renameTo(f)
    }

    // ---------- notification ----------

    private fun notifyLoss(ctx: Context, ev: Event) {
        if (!has(ctx, Manifest.permission.POST_NOTIFICATIONS) && Build.VERSION.SDK_INT >= 33) return
        val names = ev.contacts.joinToString { it.name.ifBlank { it.phones.firstOrNull() ?: "?" } }
        val n = ev.contacts.size
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("tab", "guard"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val restore = PendingIntent.getBroadcast(
            ctx, ev.id.toInt(), Intent(ctx, Actions::class.java).setAction(Actions.RESTORE).putExtra("id", ev.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (ev.mass) "Many contacts disappeared at once" else if (n == 1) "A contact disappeared" else "$n contacts disappeared"
        val body = (if (ev.mass) "If an account was removed or its sync turned off, restoring may create duplicates. Check before restoring.\n" else "") +
            names + "\n" + ev.diag.firstOrNull().orEmpty()
        NotificationManagerCompat.from(ctx).notify(
            ev.id.toInt(),
            NotificationCompat.Builder(ctx, App.CH_GUARD)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(names)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, if (n == 1) "Restore" else "Restore all", restore)
                .build(),
        )
    }
}
