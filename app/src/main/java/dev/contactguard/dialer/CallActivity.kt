package dev.contactguard.dialer

import android.content.Intent
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { AppTheme(dark = true) { CallScreen(::finish) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Actions.ANSWER) Calls.primary()?.takeIf { it.st == Call.STATE_RINGING }?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }
}

private fun stateLabel(state: Int) = when (state) {
    Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW -> "Calling…"
    Call.STATE_RINGING -> "Incoming call"
    Call.STATE_HOLDING -> "On hold"
    Call.STATE_DISCONNECTING -> "Hanging up…"
    Call.STATE_DISCONNECTED -> "Call ended"
    Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM"
    else -> ""
}

private fun elapsed(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun CallScreen(finish: () -> Unit) {
    val ctx = LocalContext.current
    val tick by Calls.tick.collectAsState()
    val calls by Calls.calls.collectAsState()
    val audio by Calls.audio.collectAsState()
    val call = remember(tick, calls) { Calls.primary() }
    val shown = call ?: Calls.lastEnded
    val number = Calls.number(shown)
    val contact by produceState<Contact?>(null, number) { value = withContext(Dispatchers.IO) { Phone.lookup(ctx, number) } }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    var keypad by remember { mutableStateOf(false) }
    var dtmf by remember { mutableStateOf("") }

    LaunchedEffect(call == null) { if (call == null) { delay(1200); finish() } }

    val state = call?.st ?: Call.STATE_DISCONNECTED
    val name = contact?.name ?: shown?.details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: formatNumber(number).ifBlank { "Private number" }
    val connected = shown?.details?.connectTimeMillis ?: 0
    val status = if (state == Call.STATE_ACTIVE && connected > 0) elapsed(now - connected) else stateLabel(state)
    val other = calls.firstOrNull { it != call }
    val scheme = MaterialTheme.colorScheme

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.surface, scheme.surface))),
    ) { CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = Dimens.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            if (other != null) Surface(Modifier.padding(top = 8.dp), shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerHigh) {
                Text("${Phone.lookup(ctx, Calls.number(other))?.name ?: formatNumber(Calls.number(other))} · ${stateLabel(other.st).ifEmpty { "Active" }}",
                    Modifier.padding(horizontal = Dimens.gutter, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(40.dp))
            AnimatedContent(status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") {
                Text(it, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            if (!keypad) {
                Avatar(name, contact?.photo, 128.dp)
                Spacer(Modifier.height(Dimens.gutter))
            }
            Text(name, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, maxLines = 2)
            if (contact != null) Text(formatNumber(number), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            if (keypad) Text(dtmf, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Spacer(Modifier.weight(1f))
            when {
                call == null -> Spacer(Modifier.height(Dimens.callButton))
                state == Call.STATE_SELECT_PHONE_ACCOUNT -> SimChooser(call)
                state == Call.STATE_RINGING -> Incoming(call)
                keypad -> {
                    DialPad(onKey = { c ->
                        dtmf += c
                        call.playDtmfTone(c)
                        call.stopDtmfTone()
                    })
                    TextButton(onClick = { keypad = false }) { Text("Hide keypad") }
                    EndButton(call)
                }
                else -> {
                    Controls(call, other, audio, onKeypad = { keypad = true })
                    Spacer(Modifier.height(32.dp))
                    EndButton(call)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    } }
}

@Composable
private fun Controls(call: Call, other: Call?, audio: CallAudioState?, onKeypad: () -> Unit) {
    val ctx = LocalContext.current
    val muted = audio?.isMuted == true
    val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
    val hasBt = (audio?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH != 0
    var routeMenu by remember { mutableStateOf(false) }
    val holding = call.st == Call.STATE_HOLDING
    val canHold = call.details.can(Call.Details.CAPABILITY_HOLD) || call.details.can(Call.Details.CAPABILITY_SUPPORT_HOLD)
    val mergeWith = call.conferenceableCalls.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tile(Icons.Filled.Dialpad, "Keypad", false, onClick = onKeypad)
            Tile(Icons.Filled.MicOff, if (muted) "Unmute" else "Mute", muted) { Calls.setMuted(!muted) }
            Box {
                Tile(
                    if (route == CallAudioState.ROUTE_BLUETOOTH) Icons.Filled.Bluetooth else Icons.AutoMirrored.Filled.VolumeUp,
                    when (route) { CallAudioState.ROUTE_BLUETOOTH -> "Bluetooth"; CallAudioState.ROUTE_SPEAKER -> "Speaker"; else -> if (hasBt) "Audio" else "Speaker" },
                    route == CallAudioState.ROUTE_SPEAKER || route == CallAudioState.ROUTE_BLUETOOTH,
                ) {
                    if (hasBt) routeMenu = true
                    else Calls.setRoute(if (route == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
                }
                DropdownMenu(routeMenu, { routeMenu = false }) {
                    listOf(CallAudioState.ROUTE_WIRED_OR_EARPIECE to "Phone", CallAudioState.ROUTE_SPEAKER to "Speaker", CallAudioState.ROUTE_BLUETOOTH to "Bluetooth")
                        .forEach { (r, l) -> DropdownMenuItem({ Text(l) }, { Calls.setRoute(r); routeMenu = false }) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tile(Icons.Filled.PersonAdd, "Add call", false) {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Tile(Icons.Filled.Pause, if (holding) "Resume" else "Hold", holding, enabled = canHold || holding) {
                if (holding) call.unhold() else call.hold()
            }
            when {
                mergeWith != null -> Tile(Icons.AutoMirrored.Filled.CallMerge, "Merge", false) { call.conference(mergeWith) }
                other != null -> Tile(Icons.Filled.SwapCalls, "Swap", false) { if (other.st == Call.STATE_HOLDING) other.unhold() else call.hold() }
                else -> Tile(Icons.AutoMirrored.Filled.CallMerge, "Merge", false, enabled = false) {}
            }
        }
    }
}

/** Toggle tile: rounded square that morphs to a circle and fills when on, like the stock call screen. */
@Composable
private fun Tile(icon: ImageVector, label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val s = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (on) Dimens.tile / 2 else 24.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "corner")
    val bg by animateColorAsState(if (on) s.primary else s.surfaceContainerHighest, label = "bg")
    val fg by animateColorAsState(if (on) s.onPrimary else s.onSurface, label = "fg")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp).graphicsLayer { alpha = if (enabled) 1f else 0.38f }) {
        Box(
            Modifier.size(Dimens.tile).clip(RoundedCornerShape(corner)).background(bg)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = fg) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun EndButton(call: Call) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "press")
    Box(
        Modifier.scale(scale).size(width = 104.dp, height = Dimens.callButton).clip(CircleShape).background(CallColors.hangup)
            .clickable(interactionSource = src, indication = null) { call.disconnect() }
            .semantics { contentDescription = "End call" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.CallEnd, null, tint = CallColors.onCall, modifier = Modifier.size(32.dp)) }
}

/** Swipe up to answer, down to decline, with a bouncing hint. TalkBack gets plain actions. */
@Composable
private fun Incoming(call: Call) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val limit = with(density) { 140.dp.toPx() }
    val drag = remember { Animatable(0f) }
    val bounce by rememberInfiniteTransition(label = "hint").animateFloat(
        0f, -14f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "bounce",
    )
    val progress = (drag.value / limit).coerceIn(-1f, 1f)
    val color = when {
        progress < 0 -> lerp(Color(0xFF2D2F31), CallColors.answer, -progress * 1.5f)
        else -> lerp(Color(0xFF2D2F31), CallColors.hangup, progress * 1.5f)
    }.let { if (abs(progress) < 0.01f) CallColors.answer else it }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.offset { IntOffset(0, (bounce * 1.5f).roundToInt()) }.graphicsLayer { alpha = 1f - abs(progress) })
        Text("Swipe up to answer", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.graphicsLayer { alpha = 1f - abs(progress) })
        Spacer(Modifier.height(Dimens.gutter))
        Box(
            Modifier
                .offset { IntOffset(0, (drag.value + if (drag.isRunning || drag.value != 0f) 0f else bounce).roundToInt()) }
                .size(Dimens.callButton + 8.dp).clip(CircleShape).background(color)
                .pointerInput(call) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            when {
                                drag.value < -limit -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
                                drag.value > limit -> call.reject(false, null)
                            }
                            scope.launch { drag.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                        },
                    ) { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceIn(-limit * 1.3f, limit * 1.3f)) } }
                }
                .semantics {
                    contentDescription = "Incoming call. Swipe up to answer, down to decline."
                    customActions = listOf(
                        CustomAccessibilityAction("Answer") { call.answer(VideoProfile.STATE_AUDIO_ONLY); true },
                        CustomAccessibilityAction("Decline") { call.reject(false, null); true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) { Icon(if (progress > 0.3f) Icons.Filled.CallEnd else Icons.Filled.Call, null, tint = CallColors.onCall, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(Dimens.gutter))
        Text("Swipe down to decline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { alpha = 1f - abs(progress) })
    }
}

/** Dual SIM with no default: Telecom waits for us to pick the SIM. */
@Composable
private fun SimChooser(call: Call) {
    val ctx = LocalContext.current
    val tm = ctx.getSystemService(TelecomManager::class.java)
    @Suppress("DEPRECATION")
    val handles: List<PhoneAccountHandle> = call.details.intentExtras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS)
        ?: call.details.extras?.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS)
        ?: try { tm.callCapablePhoneAccounts } catch (_: SecurityException) { emptyList() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.rowGap)) {
        Text("Call with", style = MaterialTheme.typography.titleMedium)
        handles.forEach { h ->
            val label = try { tm.getPhoneAccount(h)?.label?.toString() } catch (_: SecurityException) { null } ?: "SIM"
            OutlinedButton(onClick = { call.phoneAccountSelected(h, false) }, Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.SimCard, null); Spacer(Modifier.width(8.dp)); Text(label)
            }
        }
        TextButton(onClick = { call.disconnect() }) { Text("Cancel call") }
    }
}
