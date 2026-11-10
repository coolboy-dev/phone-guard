package dev.contactguard.dialer

import android.provider.CallLog
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val clear = Color.Transparent

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Dimens.gutter))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(favs: List<Contact>, all: List<Match>, onCall: (String) -> Unit, onOpen: (Long) -> Unit, onBrowse: () -> Unit) {
    if (favs.isEmpty()) return EmptyState(Icons.Outlined.StarOutline, "No favorites yet", "Star a contact to call them in one tap.", "Open contacts", onBrowse)
    LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(Dimens.gutter), verticalArrangement = Arrangement.spacedBy(Dimens.rowGap)) {
        items(favs, key = { it.id }) { c ->
            Column(
                Modifier.animateItem().combinedClickable(
                    onClick = { all.firstOrNull { it.contact.id == c.id }?.let { onCall(it.number) } ?: onOpen(c.id) },
                    onLongClick = { onOpen(c.id) },
                    onLongClickLabel = "Open contact",
                ).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(c.name, c.photo, 72.dp)
                Spacer(Modifier.height(8.dp))
                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun dayLabel(t: Long): String = when {
    DateUtils.isToday(t) -> "Today"
    DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> "Older"
}

fun whenLabel(ctx: android.content.Context, t: Long): String =
    if (DateUtils.isToday(t)) DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    else DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentsScreen(recents: List<Recent>, onCall: (String) -> Unit, onOpen: (Long) -> Unit, onCreate: (String) -> Unit, changed: () -> Unit) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    if (recents.isEmpty()) return EmptyState(Icons.Outlined.Call, "No recent calls", "Calls you make and receive show up here.")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        recents.groupBy { dayLabel(it.date) }.forEach { (day, list) ->
            stickyHeader(key = "h$day") {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Text(day, Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            items(list, key = { it.ids.first() }) { r ->
                val missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE
                val (icon, tint) = when (r.type) {
                    CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.onSurfaceVariant
                    CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.onSurfaceVariant
                    CallLog.Calls.BLOCKED_TYPE -> Icons.Filled.Block to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> Icons.AutoMirrored.Filled.CallMissed to MaterialTheme.colorScheme.error
                }
                val title = r.name ?: formatNumber(r.number).ifBlank { "Private number" }
                val expanded = open == r.ids.first()
                Column(Modifier.animateItem()) {
                    ListItem(
                        headlineContent = {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        },
                        supportingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, null, Modifier.size(16.dp), tint = tint)
                                Spacer(Modifier.width(4.dp))
                                Text((if (r.count > 1) "(${r.count}) " else "") + whenLabel(ctx, r.date))
                            }
                        },
                        leadingContent = { Avatar(title, r.photo) },
                        trailingContent = {
                            if (r.number.isNotBlank()) IconButton(onClick = { onCall(r.number) }) { Icon(Icons.Outlined.Call, "Call $title") }
                        },
                        colors = ListItemDefaults.colors(containerColor = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh else clear),
                        modifier = Modifier.combinedClickable(onClick = { open = if (expanded) null else r.ids.first() }),
                    )
                    AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 72.dp, end = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val known = r.name != null
                            Action(Icons.AutoMirrored.Outlined.Message, "Message") { Phone.sms(ctx, r.number) }
                            if (known) Action(Icons.Outlined.Person, "Contact") { Phone.lookup(ctx, r.number)?.let { onOpen(it.id) } }
                            else Action(Icons.Outlined.PersonAdd, "Add") { onCreate(r.number) }
                            Action(Icons.Filled.Block, "Block") { Phone.block(ctx, r.number); changed() }
                            Action(Icons.Outlined.Delete, "Delete") { Phone.deleteCalls(ctx, r.ids); open = null; changed() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    accounts: List<AccountRef>, selected: AccountRef?, onSelect: (AccountRef?) -> Unit,
    contacts: List<Contact>, onOpen: (Contact) -> Unit, onCreate: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = Dimens.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected == null, { onSelect(null) }, { Text("All") }) }
                items(accounts, key = { "${it.type}|${it.name}" }) { a ->
                    FilterChip(selected == a, { onSelect(a) }, { Text(a.label, maxLines = 1) })
                }
            }
        }
        item {
            ListItem(
                headlineContent = { Text("Create new contact") },
                leadingContent = {
                    Box(Modifier.size(Dimens.avatar), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) }
                },
                colors = ListItemDefaults.colors(containerColor = clear),
                modifier = Modifier.combinedClickable(onClick = onCreate),
            )
        }
        item {
            Text("${contacts.size} contacts", Modifier.padding(horizontal = Dimens.gutter, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        contacts.groupBy { it.name.firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#' }.forEach { (letter, list) ->
            stickyHeader(key = "h$letter") {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Text("$letter", Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter + 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            items(list, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.name.ifBlank { "(No name)" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Avatar(c.name, c.photo) },
                    colors = ListItemDefaults.colors(containerColor = clear),
                    modifier = Modifier.animateItem().padding(horizontal = 8.dp)
                        .then(Modifier.combinedClickable(onClick = { onOpen(c) })),
                )
            }
        }
    }
}

/** Rounded section card used on detail and guard screens. */
@Composable
fun Section(title: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 6.dp)) {
        if (title != null) Text(title, Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Column { content() } }
    }
}
