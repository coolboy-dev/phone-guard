package dev.contactguard.dialer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/** Call.state is deprecated from API 31 in favour of details.state. */
@Suppress("DEPRECATION")
val Call.st: Int get() = if (android.os.Build.VERSION.SDK_INT >= 31) details.state else state

/** Live call state shared by the service, the call screen and notification buttons. */
object Calls {
    val calls = MutableStateFlow<List<Call>>(emptyList())
    /** Bumped on every call callback: Call objects mutate in place. */
    val tick = MutableStateFlow(0)
    val audio = MutableStateFlow<CallAudioState?>(null)
    var service: CallService? = null
    var lastEnded: Call? = null

    fun primary(): Call? {
        val l = calls.value
        return l.firstOrNull { it.st == Call.STATE_RINGING }
            ?: l.firstOrNull { it.st in listOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT) }
            ?: l.firstOrNull { it.st == Call.STATE_ACTIVE }
            ?: l.firstOrNull()
    }

    fun number(call: Call?): String = call?.details?.handle?.schemeSpecificPart.orEmpty()

    @Suppress("DEPRECATION")
    fun setRoute(route: Int) = service?.setAudioRoute(route)

    @Suppress("DEPRECATION")
    fun setMuted(m: Boolean) = service?.setMuted(m)
}

class CallService : InCallService() {
    private var proximity: PowerManager.WakeLock? = null

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = changed()
        override fun onDetailsChanged(call: Call, details: Call.Details) = changed()
        override fun onConferenceableCallsChanged(call: Call, calls: MutableList<Call>) = changed()
    }

    override fun onCallAdded(call: Call) {
        Calls.service = this
        call.registerCallback(cb)
        Calls.calls.value = Calls.calls.value + call
        changed()
        // Incoming calls go through the full-screen notification; everything else opens the call screen now.
        if (call.st != Call.STATE_RINGING) startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        Calls.lastEnded = call
        Calls.calls.value = Calls.calls.value - call
        changed()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        Calls.audio.value = audioState
        changed()
    }

    override fun onDestroy() {
        proximity?.takeIf { it.isHeld }?.release()
        if (Calls.service == this) Calls.service = null
        super.onDestroy()
    }

    private fun changed() {
        Calls.tick.value++
        notification()
        val c = Calls.primary()
        val earpiece = (Calls.audio.value?.route ?: CallAudioState.ROUTE_EARPIECE) == CallAudioState.ROUTE_EARPIECE
        val wantDark = c != null && earpiece && c.st in listOf(Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING)
        if (proximity == null) proximity = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "guard:proximity")
        val p = proximity!!
        if (wantDark && !p.isHeld) p.acquire(3 * 3_600_000L)
        if (!wantDark && p.isHeld) p.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    private fun notification() {
        val nm = NotificationManagerCompat.from(this)
        val c = Calls.primary()
        if (c == null) { nm.cancel(NOTIF); return }
        val number = Calls.number(c)
        val name = Phone.lookup(this, number)?.name ?: number.ifBlank { "Unknown" }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val screen = PendingIntent.getActivity(this, 0, Intent(this, CallActivity::class.java), flags)
        fun bc(action: String, rc: Int) = PendingIntent.getBroadcast(this, rc, Intent(this, Actions::class.java).setAction(action), flags)
        val b = if (c.st == Call.STATE_RINGING) {
            val answer = PendingIntent.getActivity(this, 1, Intent(this, CallActivity::class.java).setAction(Actions.ANSWER), flags)
            NotificationCompat.Builder(this, App.CH_INCOMING)
                .setContentTitle(name).setContentText("Incoming call")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(screen, true)
                .addAction(0, "Decline", bc(Actions.DECLINE, 2))
                .addAction(0, "Answer", answer)
        } else {
            NotificationCompat.Builder(this, App.CH_ONGOING)
                .setContentTitle(name)
                .setContentText(if (c.st == Call.STATE_HOLDING) "On hold" else "Ongoing call")
                .setUsesChronometer(c.details.connectTimeMillis > 0)
                .setWhen(c.details.connectTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis())
                .addAction(0, "Hang up", bc(Actions.HANGUP, 3))
        }
        val n = b.setSmallIcon(R.drawable.ic_notify).setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(screen).build()
        try { nm.notify(NOTIF, n) } catch (_: SecurityException) { /* notifications not allowed */ }
    }

    companion object { private const val NOTIF = 42 }
}
