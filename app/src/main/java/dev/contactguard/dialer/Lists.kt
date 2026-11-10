package dev.contactguard.dialer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
fun ContactsScreen(accounts: List<AccountRef>, selected: AccountRef?, onSelect: (AccountRef?) -> Unit, contacts: List<Contact>, onOpen: (Contact) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Dimens.gutter)) {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = Dimens.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected == null, { onSelect(null) }, { Text("All") }) }
                items(accounts, key = { "${it.type}|${it.name}" }) { a ->
                    FilterChip(selected == a, { onSelect(a) }, { Text(a.label, maxLines = 1) })
                }
            }
        }
        item {
            Text("${contacts.size} contacts", Modifier.padding(horizontal = Dimens.gutter, vertical = 8.dp),
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
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.animateItem().padding(horizontal = 8.dp).clickable { onOpen(c) },
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
