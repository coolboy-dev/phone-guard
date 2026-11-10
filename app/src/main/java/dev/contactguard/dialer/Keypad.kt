package dev.contactguard.dialer

import android.content.ClipData
import android.content.ClipboardManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.CallLog
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

private val KEYS = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '*' to "", '0' to "+", '#' to "",
)

private val TONES = mapOf(
    '0' to ToneGenerator.TONE_DTMF_0, '1' to ToneGenerator.TONE_DTMF_1, '2' to ToneGenerator.TONE_DTMF_2, '3' to ToneGenerator.TONE_DTMF_3,
    '4' to ToneGenerator.TONE_DTMF_4, '5' to ToneGenerator.TONE_DTMF_5, '6' to ToneGenerator.TONE_DTMF_6, '7' to ToneGenerator.TONE_DTMF_7,
    '8' to ToneGenerator.TONE_DTMF_8, '9' to ToneGenerator.TONE_DTMF_9, '*' to ToneGenerator.TONE_DTMF_S, '#' to ToneGenerator.TONE_DTMF_P,
)

/** The 4×3 pad. [tones] plays local key tones (keypad screen only; in a call the network plays them). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialPad(onKey: (Char) -> Unit, onLong: (Char) -> Boolean = { false }, tones: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface) {
    val view = LocalView.current
    val ctx = LocalContext.current
    val tone = remember {
        if (tones && Settings.System.getInt(ctx.contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1)
            try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (_: Exception) { null } else null
    }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KEYS.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { (d, sub) ->
                    Box(
                        Modifier.size(Dimens.key).clip(CircleShape)
                            .combinedClickable(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    tone?.startTone(TONES.getValue(d), 120)
                                    onKey(d)
                                },
                                onLongClick = { if (onLong(d)) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                            )
                            .semantics { contentDescription = if (sub.isEmpty()) "$d" else "$d $sub" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$d", style = MaterialTheme.typography.headlineMedium, color = color)
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}

/** Keeps only what a dialer can dial: digits, a leading +, and * # , ; for codes and pauses. */
fun dialable(s: String): String {
    val kept = s.filter { it.isDigit() || it in "+*#,;" }
    return kept.take(1) + kept.drop(1).replace("+", "")
}

fun formatNumber(n: String): String = PhoneNumberUtils.formatNumber(n, Locale.getDefault().country) ?: n

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeypadScreen(initial: String, all: List<Match>, onCall: (String) -> Unit, onCreate: (String) -> Unit) {
    val ctx = LocalContext.current
    var number by rememberSaveable(initial) { mutableStateOf(initial) }
    val matches = remember(number, all) { Phone.keypadMatches(all, number) }

    fun type(c: Char) {
        number += c
        if (number.endsWith("#*#*") && Phone.specialCode(ctx, number)) number = ""
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
                if (number.isNotEmpty() && matches.isEmpty()) item {
                    ListItem(
                        headlineContent = { Text("Create new contact") },
                        leadingContent = { Icon(Icons.Outlined.PersonAdd, null, Modifier.size(Dimens.avatar).padding(8.dp)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.combinedClickable(onClick = { onCreate(number) }),
                    )
                }
                items(matches, key = { it.contact.id.toString() + it.number }) { m ->
                    ListItem(
                        headlineContent = { Text(m.contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${m.label} · ${m.number}") },
                        leadingContent = { Avatar(m.contact.name, m.contact.photo) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.animateItem().combinedClickable(onClick = { onCall(m.number) }),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                // Long-press the number area to paste (or copy), like the stock dialer.
                var menu by remember { mutableStateOf(false) }
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Paste or copy number"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        formatNumber(number), style = MaterialTheme.typography.displaySmall,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                    )
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Paste") }, {
                            menu = false
                            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                            number += dialable(text)
                        })
                        if (number.isNotEmpty()) DropdownMenuItem({ Text("Copy") }, {
                            menu = false
                            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Phone number", number))
                        })
                    }
                }
                AnimatedVisibility(number.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .combinedClickable(onClick = { number = number.dropLast(1) }, onLongClick = { number = "" })
                            .semantics { contentDescription = "Backspace" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Outlined.Backspace, null) }
                }
                if (number.isEmpty()) Spacer(Modifier.width(48.dp))
            }
            DialPad(
                onKey = ::type,
                onLong = { d ->
                    when (d) {
                        '0' -> { number += '+'; true }
                        '1' -> if (number.isEmpty()) { Phone.voicemail(ctx); true } else false
                        else -> false
                    }
                },
                tones = true,
            )
            Box(Modifier.fillMaxWidth().padding(vertical = Dimens.gutter), contentAlignment = Alignment.Center) {
                FloatingActionButton(
                    onClick = {
                        if (number.isEmpty()) number = try { CallLog.Calls.getLastOutgoingCall(ctx).orEmpty() } catch (_: Exception) { "" } // stock behaviour: redial
                        else onCall(number)
                    },
                    shape = CircleShape,
                    containerColor = CallColors.answer, contentColor = CallColors.onCall,
                    modifier = Modifier.size(width = 96.dp, height = 64.dp).semantics { contentDescription = "Call" },
                ) { Icon(Icons.Filled.Call, null) }
            }
        }
    }
}
