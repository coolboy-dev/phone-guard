package dev.contactguard.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.ContactsContract.CommonDataKinds.Phone as P

class MainActivity : ComponentActivity() {
    private val intents = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intents.value = intent
        setContent { AppTheme { Root(intents) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intents.value = intent
    }
}

private val CORE = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE)
private val ALL = CORE + listOf(Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_PHONE_STATE, Manifest.permission.GET_ACCOUNTS) +
    (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())

private fun ready(ctx: Context) = CORE.all { Phone.can(ctx, it) }
private fun isDefault(ctx: Context) = ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)

@Composable
private fun Root(intents: MutableStateFlow<Intent?>) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(ready(ctx)) }
    if (!ok) Onboarding { ok = ready(ctx); GuardJob.schedule(ctx); GuardJob.runNow(ctx) } else Main(intents)
}

@Composable
private fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var dflt by remember { mutableStateOf(isDefault(ctx)) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { dflt = isDefault(ctx) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Shield, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Dimens.gutter))
            Text("Phone Guard", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "A phone app that keeps a copy of every contact, from all your Google accounts, phone storage and SIM, and brings back any that disappear.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            if (!dflt) {
                Button(onClick = { role.launch(ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER)) }, Modifier.fillMaxWidth()) {
                    Text("Set as default phone app")
                }
                Spacer(Modifier.height(8.dp))
            }
            val allow = { perms.launch(ALL.toTypedArray()) }
            if (dflt) Button(onClick = allow, Modifier.fillMaxWidth()) { Text("Allow access to contacts and calls") }
            else OutlinedButton(onClick = allow, Modifier.fillMaxWidth()) { Text("Allow access to contacts and calls") }
        }
    }
}

private enum class Tab(val label: String, val on: ImageVector, val off: ImageVector) {
    FAVORITES("Favorites", Icons.Filled.Star, Icons.Outlined.StarOutline),
    RECENTS("Recents", Icons.Filled.AccessTime, Icons.Outlined.AccessTime),
    CONTACTS("Contacts", Icons.Filled.Contacts, Icons.Outlined.Contacts),
    GUARD("Guard", Icons.Filled.Shield, Icons.Outlined.Shield),
}

private data class Lists(
    val favs: List<Contact> = emptyList(), val recents: List<Recent> = emptyList(), val contacts: List<Contact> = emptyList(),
    val numbers: List<Match> = emptyList(), val accounts: List<AccountRef> = emptyList(),
)

private data class EditReq(val rawId: Long?, val draft: Phone.Draft, val title: String)

/** Bumps whenever contacts or the call log change, from any app. */
@Composable
private fun providerVersion(): Int {
    val ctx = LocalContext.current
    var v by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { v++ } }
        ctx.contentResolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, obs)
        ctx.contentResolver.registerContentObserver(CallLog.CONTENT_URI, true, obs)
        onDispose { ctx.contentResolver.unregisterContentObserver(obs) }
    }
    return v
}

@Composable
private fun Main(intents: MutableStateFlow<Intent?>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.FAVORITES) }
    var keypad by rememberSaveable { mutableStateOf<String?>(null) }
    var lastKeypad by remember { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editor by remember { mutableStateOf<EditReq?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var account by remember { mutableStateOf<AccountRef?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    val version = providerVersion()
    var dflt by remember { mutableStateOf(isDefault(ctx)) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { dflt = isDefault(ctx) }

    val data by produceState(Lists(), version, bump, account) {
        if (version > 0) delay(300) // let bursts of provider changes settle
        value = withContext(Dispatchers.IO) {
            Lists(Phone.favorites(ctx), Phone.recents(ctx), Phone.contacts(ctx, account), Phone.numbers(ctx), Phone.accounts(ctx))
        }
    }
    var lastDetail by remember { mutableStateOf<Detail?>(null) }
    val detail by produceState<Detail?>(null, openId, version, bump) {
        val id = openId ?: return@produceState run { value = null }
        value = withContext(Dispatchers.IO) {
            if (id < 0) Phone.simNumber(ctx, id)?.let { n ->
                val c = data.contacts.firstOrNull { it.id == id } ?: Contact(id, "", n, null, false)
                Detail(c, listOf(Match(c, n, "SIM")), emptyList(), listOf("SIM"), emptyList())
            } else Phone.detail(ctx, id)
        }
        if (value == null) openId = null
    }
    if (detail != null) lastDetail = detail
    keypad?.let { lastKeypad = it }

    val intent by intents.collectAsState()
    LaunchedEffect(intent) {
        val i = intent ?: return@LaunchedEffect
        if (i.action == Intent.ACTION_DIAL || i.action == Intent.ACTION_VIEW) keypad = i.data?.schemeSpecificPart.orEmpty()
        if (i.getStringExtra("tab") == "guard") { tab = Tab.GUARD; keypad = null; openId = null }
        intents.value = null
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(keypad != null, openId, editor) { if (keypad != null || openId != null || editor != null) focus.clearFocus() }
    LaunchedEffect(tab) { if (tab == Tab.RECENTS) withContext(Dispatchers.IO) { Phone.markMissedRead(ctx) } }

    fun call(n: String) { Phone.call(ctx, n); keypad = null }
    fun create(n: String = "") { editor = EditReq(null, Phone.Draft("", "", listOf(n to P.TYPE_MOBILE), emptyList()), "Create contact") }

    BackHandler(editor != null || openId != null || keypad != null || query.isNotEmpty()) {
        when {
            editor != null -> editor = null
            openId != null -> openId = null
            keypad != null -> keypad = null
            else -> query = ""
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column(Modifier.statusBarsPadding()) {
                    SearchPill(query) { query = it }
                    if (!dflt) Surface(
                        Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(Modifier.padding(start = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
                            Text("Not your default phone app yet", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { role.launch(ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER)) }) { Text("Set default") }
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t; query = "" },
                            icon = { Icon(if (tab == t) t.on else t.off, null) }, label = { Text(t.label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(keypad == null && tab != Tab.GUARD, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    FloatingActionButton(onClick = { keypad = "" }, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Filled.Dialpad, "Open keypad") }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (query.isNotBlank()) SearchResults(query, data, onOpen = { openId = it }, onCall = ::call)
                else AnimatedContent(
                    tab,
                    transitionSpec = {
                        (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.96f)) togetherWith fadeOut(tween(90))
                    },
                    label = "tab",
                ) { t ->
                    when (t) {
                        Tab.FAVORITES -> FavoritesScreen(data.favs, data.numbers, ::call, { openId = it }) { tab = Tab.CONTACTS }
                        Tab.RECENTS -> RecentsScreen(data.recents, ::call, { openId = it }, ::create) { bump++ }
                        Tab.CONTACTS -> ContactsScreen(data.accounts, account, { account = it }, data.contacts, { openId = it.id }) { create() }
                        Tab.GUARD -> GuardScreen()
                    }
                }
            }
        }

        AnimatedVisibility(keypad != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            KeypadScreen(lastKeypad, data.numbers, ::call, ::create)
        }
        AnimatedVisibility(openId != null, enter = slideInHorizontally { it / 3 } + fadeIn(), exit = slideOutHorizontally { it / 3 } + fadeOut()) {
            lastDetail?.let { d ->
                Surface(Modifier.fillMaxSize()) {
                    ContactDetail(
                        d, onBack = { openId = null }, onCall = ::call,
                        onEdit = {
                            scope.launch {
                                val raw = d.rawIds.first()
                                editor = EditReq(raw, withContext(Dispatchers.IO) { Phone.draftOf(ctx, raw) }, "Edit contact")
                            }
                        },
                        onDelete = { scope.launch { withContext(Dispatchers.IO) { Phone.delete(ctx, d) }; openId = null; bump++ } },
                        onStar = { s -> scope.launch { withContext(Dispatchers.IO) { Phone.setStarred(ctx, d.contact.id, s) }; bump++ } },
                    )
                }
            }
        }
        AnimatedVisibility(editor != null, enter = slideInVertically { it / 4 } + fadeIn(), exit = slideOutVertically { it / 4 } + fadeOut()) {
            var last by remember { mutableStateOf(editor) }
            editor?.let { last = it }
            last?.let { e ->
                Surface(Modifier.fillMaxSize()) {
                    ContactEditor(
                        e.title, e.draft, if (e.rawId == null) data.accounts else null,
                        onCancel = { editor = null },
                        onSave = { acct, draft ->
                            scope.launch {
                                withContext(Dispatchers.IO) { Phone.save(ctx, e.rawId, acct ?: AccountRef(null, null, ""), draft) }
                                editor = null; keypad = null; bump++
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPill(query: String, onChange: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = 8.dp).height(Dimens.searchHeight),
        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.gutter))
            BasicTextField(
                query, onChange, Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search contacts", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                },
            )
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "Clear search") }
        }
    }
}

@Composable
private fun SearchResults(query: String, data: Lists, onOpen: (Long) -> Unit, onCall: (String) -> Unit) {
    val q = query.trim().lowercase()
    val digits = q.filter { it.isDigit() }
    val hits = remember(q, data) {
        val byNumber = data.numbers.filter { m -> m.contact.name.lowercase().contains(q) || (digits.length >= 3 && m.number.filter { it.isDigit() }.contains(digits)) }
        val noNumber = data.contacts.filter { c -> c.name.lowercase().contains(q) && byNumber.none { it.contact.id == c.id } }.map { Match(it, "", "") }
        (byNumber + noNumber).distinctBy { it.contact.id }
    }
    if (hits.isEmpty()) return EmptyState(Icons.Outlined.Search, "No matches", "Nothing found for \"$query\".")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(hits, key = { it.contact.id }) { m ->
            ListItem(
                headlineContent = { Text(m.contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { if (m.number.isNotEmpty()) Text(formatNumber(m.number)) },
                leadingContent = { Avatar(m.contact.name, m.contact.photo) },
                trailingContent = { if (m.number.isNotEmpty()) IconButton(onClick = { onCall(m.number) }) { Icon(Icons.Outlined.Call, "Call ${m.contact.name}") } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(m.contact.id) },
            )
        }
    }
}
