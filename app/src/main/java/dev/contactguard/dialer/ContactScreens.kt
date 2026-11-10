package dev.contactguard.dialer

import android.provider.ContactsContract.CommonDataKinds.Phone as P
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetail(d: Detail, onBack: () -> Unit, onCall: (String) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onStar: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val editable = d.rawIds.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (editable) {
                        IconButton(onClick = { onStar(!d.contact.starred) }) {
                            Icon(if (d.contact.starred) Icons.Filled.Star else Icons.Outlined.StarOutline, if (d.contact.starred) "Remove from favorites" else "Add to favorites")
                        }
                        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit contact") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Delete") }, { menu = false; confirm = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = Dimens.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(d.contact.name, d.contact.photo, Dimens.avatarLarge)
                    Spacer(Modifier.height(Dimens.gutter))
                    Text(d.contact.name.ifBlank { "(No name)" }, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Dimens.gutter))
                    val first = d.phones.firstOrNull()?.number
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Quick(Icons.Outlined.Call, "Call", first != null) { first?.let(onCall) }
                        Quick(Icons.AutoMirrored.Outlined.Message, "Text", first != null) { first?.let { Phone.sms(ctx, it) } }
                    }
                }
            }
            if (d.phones.isNotEmpty()) item {
                Section("Phone") {
                    d.phones.forEach { m ->
                        ListItem(
                            headlineContent = { Text(formatNumber(m.number)) },
                            supportingContent = { Text(m.label) },
                            leadingContent = { Icon(Icons.Outlined.Call, null) },
                            trailingContent = { IconButton(onClick = { Phone.sms(ctx, m.number) }) { Icon(Icons.AutoMirrored.Outlined.Message, "Text ${m.number}") } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onCall(m.number) },
                        )
                    }
                }
            }
            if (d.emails.isNotEmpty()) item {
                Section("Email") {
                    d.emails.forEach {
                        ListItem({ Text(it) }, leadingContent = { Icon(Icons.Outlined.Email, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
            }
            item {
                Section("Saved in") {
                    ListItem({ Text(d.sources.joinToString("\n")) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete ${d.contact.name}?") },
        text = { Text("This contact will be removed from ${d.sources.joinToString(", ")}.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete contact") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
    )
}

@Composable
private fun Quick(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp), shape = CircleShape) { Icon(icon, null) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private val PHONE_TYPES = listOf(P.TYPE_MOBILE to "Mobile", P.TYPE_HOME to "Home", P.TYPE_WORK to "Work", P.TYPE_OTHER to "Other")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditor(
    title: String, initial: Phone.Draft, accounts: List<AccountRef>?, // null = editing, account fixed
    onCancel: () -> Unit, onSave: (AccountRef?, Phone.Draft) -> Unit,
) {
    var first by remember { mutableStateOf(initial.first) }
    var last by remember { mutableStateOf(initial.last) }
    val phones = remember { mutableStateListOf(*initial.phones.ifEmpty { listOf("" to P.TYPE_MOBILE) }.toTypedArray()) }
    val emails = remember { mutableStateListOf(*initial.emails.toTypedArray()) }
    // Default to a Google account: it is backed up off the phone.
    var account by remember { mutableStateOf(accounts?.firstOrNull { it.type == "com.google" } ?: accounts?.firstOrNull()) }
    val canSave = (first + last).isNotBlank() || phones.any { it.first.isNotBlank() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel") } },
                actions = {
                    Button(onClick = { onSave(account, Phone.Draft(first, last, phones.toList(), emails.toList())) }, enabled = canSave,
                        modifier = Modifier.padding(end = 8.dp)) { Text("Save") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.rowGap),
        ) {
            if (accounts != null) Picker("Save to", account?.label.orEmpty(), accounts.map { it.label }) { account = accounts[it] }
            if (account?.type == RawSnap.SIM_TYPE) Text("SIM cards keep one name and one number.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(first, { first = it }, Modifier.fillMaxWidth(), label = { Text("First name") }, singleLine = true)
            OutlinedTextField(last, { last = it }, Modifier.fillMaxWidth(), label = { Text("Last name") }, singleLine = true)
            phones.forEachIndexed { i, (num, type) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(num, { phones[i] = it to type }, Modifier.weight(1f), label = { Text("Phone") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    Spacer(Modifier.width(8.dp))
                    Picker("Type", PHONE_TYPES.firstOrNull { it.first == type }?.second ?: "Other", PHONE_TYPES.map { it.second }, Modifier.width(128.dp)) {
                        phones[i] = num to PHONE_TYPES[it].first
                    }
                    if (phones.size > 1) IconButton(onClick = { phones.removeAt(i) }) { Icon(Icons.Outlined.RemoveCircleOutline, "Remove phone") }
                }
            }
            if (account?.type != RawSnap.SIM_TYPE) {
                TextButton(onClick = { phones += "" to P.TYPE_MOBILE }) { Icon(Icons.Outlined.Add, null); Text("Add phone") }
                emails.forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(e, { emails[i] = it }, Modifier.weight(1f), label = { Text("Email") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        IconButton(onClick = { emails.removeAt(i) }) { Icon(Icons.Outlined.RemoveCircleOutline, "Remove email") }
                    }
                }
                TextButton(onClick = { emails += "" }) { Icon(Icons.Outlined.Add, null); Text("Add email") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, value: String, options: List<String>, modifier: Modifier = Modifier.fillMaxWidth(), onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(
            value, {}, Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
        )
        ExposedDropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, { onPick(i); open = false }, contentPadding = PaddingValues(horizontal = Dimens.gutter)) }
        }
    }
}
