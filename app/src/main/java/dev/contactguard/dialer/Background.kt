package dev.contactguard.dialer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_GUARD, "Contact protection", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_RESTORED, "Contacts brought back", NotificationManager.IMPORTANCE_LOW),
            ),
        )
        // Channels from the old dialer build.
        listOf("incoming", "ongoing").forEach { nm.deleteNotificationChannel(it) }
        GuardJob.schedule(this)
    }

    companion object {
        const val CH_GUARD = "guard"
        const val CH_RESTORED = "restored"
    }
}

/**
 * Runs the contact check. Job 1 fires on any change in the contacts provider, even when the app is not running;
 * content-trigger jobs are one-shot, so it re-arms itself. Job 2 is a 6-hourly safety net.
 */
class GuardJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try { Guard.check(this) } finally {
                jobFinished(params, false)
                if (params.jobId == CONTENT) schedule(this)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val CONTENT = 1
        private const val PERIODIC = 2

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val cn = ComponentName(ctx, GuardJob::class.java)
            js.schedule(
                JobInfo.Builder(CONTENT, cn)
                    .addTriggerContentUri(JobInfo.TriggerContentUri(ContactsContract.AUTHORITY_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(3_000)
                    .setTriggerContentMaxDelay(30_000)
                    .build(),
            )
            if (js.getPendingJob(PERIODIC) == null) js.schedule(
                JobInfo.Builder(PERIODIC, cn).setPeriodic(6 * 3_600_000L).setPersisted(true).build(),
            )
        }

        fun runNow(ctx: Context) = thread { Guard.check(ctx.applicationContext) }
    }
}

/** Content-trigger jobs don't survive a reboot or an app update. */
class Boot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        GuardJob.schedule(ctx)
        GuardJob.runNow(ctx)
    }
}

/** Notification buttons. */
class Actions : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            RESTORE -> {
                val done = goAsync()
                thread { try { Guard.restore(ctx, intent.getLongExtra("id", 0)) } finally { done.finish() } }
            }
        }
    }

    companion object {
        const val RESTORE = "restore"
    }
}
