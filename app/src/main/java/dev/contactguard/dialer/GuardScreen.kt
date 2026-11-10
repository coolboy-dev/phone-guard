package dev.contactguard.dialer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class GuardState(
    val events: List<Guard.Event>,
    val accounts: List<Triple<String, Int, Boolean?>>, // label, count, sync
    val lastCheck: Long,
)

@Composable
fun GuardScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Guard.version.collectAsState()
    var local by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val p = Guard.prefs(ctx)

    val state by produceState<GuardState?>(null, version, local) {
        value = withContext(Dispatchers.IO) {
            val snaps = Guard.loadSnaps(File(ctx.filesDir, "snapshot.json")).orEmpty()
            GuardState(
                Guard.loadEvents(ctx),
                snaps.groupBy { it.accountType to it.accountName }.map { (k, v) ->
                    Triple(Guard.accountLabel(ctx, k.first, k.second), v.size, Guard.syncOn(k.first, k.second))
                }.sortedByDescending { it.second },
                p.getLong("lastCheck", 0),
            )
        }
    }
    val writers by produceState<List<String>?>(null) { value = withContext(Dispatchers.IO) { Guard.contactWriters(ctx) } }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            p.edit().putString("folder", uri.toString()).apply()
            scope.launch { busy = true; withContext(Dispatchers.IO) { Guard.export(ctx) }; busy = false; local++ }
        }
    }
    var showWriters by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    val s = state ?: return Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { CircularProgressIndicator() }
    val pending = s.events.filter { it.status == "pending" }
    val past = s.events.filter { it.status != "pending" }
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(Dimens.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (pending.isEmpty()) Icons.Outlined.Shield else Icons.Outlined.WarningAmber, null, Modifier.size(56.dp),
                    tint = if (pending.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (pending.isEmpty()) "Protecting ${s.accounts.sumOf { it.second }} contacts" else pending.sumOf { it.contacts.size }.let { if (it == 1) "1 contact needs attention" else "$it contacts need attention" },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (s.lastCheck == 0L) "Not checked yet" else "Last checked " + DateUtils.getRelativeTimeSpanString(s.lastCheck),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { busy = true; withContext(Dispatchers.IO) { Guard.check(ctx) }; busy = false; local++ } }, enabled = !busy) {
                    Text(if (busy) "Checking…" else "Check now")
                }
            }
        }

        items(pending, key = { it.id }) { e -> EventCard(e, scope, onDone = { local++ }) }

        item {
            Section("Accounts") {
                s.accounts.forEach { (label, n, sync) ->
                    ListItem(
                        { Text(label) },
                        supportingContent = { Text("$n contacts" + when (sync) { true -> " · sync on"; false -> " · sync OFF"; null -> "" }) },
                        colors = clear,
                    )
                }
            }
        }

        item {
            Section("Protection") {
                ListItem(
                    { Text("Restore automatically") },
                    supportingContent = { Text("Put deleted contacts back without asking. Mass deletions always ask.") },
                    trailingContent = {
                        var on by remember { mutableStateOf(p.getBoolean("autoRestore", false)) }
                        Switch(on, { on = it; p.edit().putBoolean("autoRestore", it).apply() })
                    },
                    colors = clear,
                )
                val f = p.getString("folder", null)
                ListItem(
                    { Text("Backup folder") },
                    supportingContent = {
                        Text(if (f == null) "Pick a folder for a daily .vcf copy. It survives even if this app is removed."
                        else Uri.parse(f).lastPathSegment.orEmpty().substringAfter(':') + " · last copy " + p.getString("lastExport", "never"))
                    },
                    trailingContent = { TextButton(onClick = { folder.launch(null) }) { Text(if (f == null) "Choose" else "Change") } },
                    colors = clear,
                )
                if (f != null) ListItem(
                    { Text("Back up now") },
                    trailingContent = {
                        TextButton(onClick = { scope.launch { busy = true; withContext(Dispatchers.IO) { Guard.export(ctx) }; busy = false; local++ } }, enabled = !busy) { Text("Export") }
                    },
                    colors = clear,
                )
            }
        }

        item {
            Section("Find the cause") {
                val usage = remember(local) { Guard.usageAccess(ctx) }
                ListItem(
                    { Text("App activity") },
                    supportingContent = { Text(if (usage) "On: records which apps were open when contacts disappear" else "Allow usage access to record which apps were open when contacts disappear") },
                    trailingContent = {
                        if (usage) Icon(Icons.Outlined.CheckCircle, "Allowed", tint = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow") }
                    },
                    colors = clear,
                )
                ListItem(
                    { Text("Google Contacts trash") },
                    supportingContent = { Text("Shows which device deleted each synced contact") },
                    trailingContent = {
                        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://contacts.google.com/trash"))) }) { Text("Open") }
                    },
                    colors = clear,
                )
                ListItem(
                    { Text("Apps that can change contacts") },
                    supportingContent = { Text(writers?.let { "${it.size} apps" } ?: "Loading…") },
                    trailingContent = { Icon(if (showWriters) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) },
                    colors = clear,
                    modifier = Modifier.clickable { showWriters = !showWriters },
                )
                AnimatedVisibility(showWriters) {
                    Text(writers.orEmpty().joinToString("\n"), Modifier.padding(start = Dimens.gutter, end = Dimens.gutter, bottom = Dimens.gutter),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (past.isNotEmpty()) {
            item {
                ListItem(
                    { Text("History (${past.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(if (showHistory) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) },
                    colors = clear,
                    modifier = Modifier.padding(horizontal = 4.dp).clickable { showHistory = !showHistory },
                )
            }
            if (showHistory) items(past, key = { it.id }) { e -> EventCard(e, scope, onDone = { local++ }) }
        }
    }
}

@Composable
private fun EventCard(e: Guard.Event, scope: kotlinx.coroutines.CoroutineScope, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    val n = e.contacts.size
    Section(DateUtils.formatDateTime(ctx, e.id, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)) {
        Column(Modifier.padding(Dimens.gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (n == 1) "1 contact disappeared" else "$n contacts disappeared") + when (e.status) {
                    "restored" -> " · restored"; "dismissed" -> " · ignored"; "returned" -> " · came back on their own"; else -> ""
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (e.mass && e.status == "pending") Text(
                "Many at once. If an account was removed or its sync turned off, restoring now may create duplicates when it comes back.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            )
            Text(e.contacts.take(12).joinToString { it.name.ifBlank { it.phones.firstOrNull().orEmpty() } } + if (n > 12) " and ${n - 12} more" else "",
                style = MaterialTheme.typography.bodyMedium)
            e.diag.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (e.status == "pending") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    scope.launch { busy = true; withContext(Dispatchers.IO) { Guard.restore(ctx, e.id) }; busy = false; onDone() }
                }, enabled = !busy) { Text(if (busy) "Restoring…" else if (n == 1) "Restore contact" else "Restore $n contacts") }
                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { Guard.dismiss(ctx, e.id) }; onDone() } }) { Text("Ignore") }
            }
            if (e.status == "dismissed") TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { Guard.restore(ctx, e.id) }; onDone() }
            }) { Text("Restore anyway") }
        }
    }
}
