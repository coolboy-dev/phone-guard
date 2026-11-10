package dev.contactguard.dialer

/** One raw contact (or SIM record) as it looked in the last snapshot. Pure Kotlin so the diff is unit-testable. */
data class RawSnap(
    val rawId: Long,
    val accountType: String?,
    val accountName: String?,
    val name: String,
    val starred: Boolean,
    /** Data rows: "mimetype" plus data1..data15 that were non-null text. */
    val rows: List<Map<String, String>>,
) {
    val phones: Set<String>
        get() = rows.filter { it["mimetype"] == PHONE_MIME }.mapNotNull { it["data1"]?.let(::normPhone) }
            .filter { it.isNotEmpty() }.toSet()
    val accountKey get() = "${accountType.orEmpty()}|${accountName.orEmpty()}"

    companion object {
        const val PHONE_MIME = "vnd.android.cursor.item/phone_v2"
        const val NAME_MIME = "vnd.android.cursor.item/name"
        const val SIM_TYPE = "guard.sim"
    }
}

fun normPhone(s: String) = s.filter { it.isDigit() }.takeLast(10)
fun normName(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

/** A lost contact counts as still present if a live one has the same name and shares a number (merges, account moves, re-syncs). */
fun stillPresent(snap: RawSnap, live: List<RawSnap>): Boolean {
    val n = normName(snap.name)
    val p = snap.phones
    return live.any { l ->
        (n.isNotEmpty() && normName(l.name) == n && (p.isEmpty() || l.phones.any { it in p })) ||
            (n.isEmpty() && p.isNotEmpty() && l.phones.any { it in p })
    }
}

data class Diff(val lost: List<RawSnap>, val massAccounts: Set<String>)

/**
 * Raw contacts in [old] that are gone from [live] and not found elsewhere.
 * An account losing >=5 contacts and >30% of them at once is "mass": sync turned off or account removed,
 * where an auto-restore would create duplicates once sync comes back.
 */
fun diff(old: List<RawSnap>, live: List<RawSnap>, ignore: Set<Long> = emptySet()): Diff {
    val liveIds = live.mapTo(HashSet()) { it.rawId }
    val lost = old.filter { it.rawId !in liveIds && it.rawId !in ignore && !stillPresent(it, live) }
    val perAccount = old.groupingBy { it.accountKey }.eachCount()
    val mass = lost.groupingBy { it.accountKey }.eachCount()
        .filter { (k, n) -> n >= 5 && n > 0.3 * (perAccount[k] ?: 0) }.keys
    return Diff(lost, mass)
}
