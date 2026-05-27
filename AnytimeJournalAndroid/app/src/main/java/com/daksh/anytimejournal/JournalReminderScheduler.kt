package com.daksh.anytimejournal

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object JournalReminderScheduler {
    private const val CHANNEL_ID = "anytime_journal_hourly"
    private const val ACTION_HOURLY_JOURNAL = "com.daksh.anytimejournal.HOURLY_JOURNAL"
    const val ACTION_SAVE_JOURNAL_REPLY = "com.daksh.anytimejournal.SAVE_HOURLY_JOURNAL_REPLY"
    const val KEY_JOURNAL_REPLY = "journal_reply"
    private const val REQUEST_REMINDER = 4100
    private const val REQUEST_REPLY = 4101
    private const val NOTIFICATION_ID = 4102
    private const val ONE_HOUR_MS = 60 * 60 * 1000L

    fun scheduleNext(context: Context, fromMillis: Long = System.currentTimeMillis()) {
        val triggerAt = fromMillis + ONE_HOUR_MS
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, JournalReminderReceiver::class.java).apply {
            action = ACTION_HOURLY_JOURNAL
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_REMINDER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleAlarm(alarmManager, triggerAt, pendingIntent)
    }

    fun showReminder(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(manager)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_REMINDER,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_journal_notification)
                .setContentTitle("Journal update")
                .setContentText("Quick fill: what changed in the last hour?")
                .setShowWhen(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(contentIntent)
                .addAction(replyAction(context))
                .setGroup(NotificationHelper.GROUP_KEY)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setSortKey("3_journal_hourly")
                .build(),
        )
        NotificationHelper.refreshCompactHub(context)
    }

    private fun replyAction(context: Context): NotificationCompat.Action {
        val replyIntent = Intent(context, JournalReminderReceiver::class.java).apply {
            action = ACTION_SAVE_JOURNAL_REPLY
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_REPLY,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(KEY_JOURNAL_REPLY)
            .setLabel("Write journal update")
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_journal_notification,
            "Write",
            replyPendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAuthenticationRequired(false)
            .build()
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Hourly journal prompts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Hourly reminders to update your journal"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun scheduleAlarm(alarmManager: AlarmManager, triggerAtMillis: Long, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }
    }
}

class JournalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (intent.action == JournalReminderScheduler.ACTION_SAVE_JOURNAL_REPLY) {
            val pendingResult = goAsync()
            val replyText = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(JournalReminderScheduler.KEY_JOURNAL_REPLY)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    EntryRepository.from(appContext).saveReply(
                        replyText,
                        kind = JournalEntryInput.KIND_JOURNAL,
                    )
                    appContext.getSystemService(NotificationManager::class.java).cancel(4102)
                    JournalReminderScheduler.scheduleNext(appContext)
                    NotificationHelper.showPersistentInput(appContext)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        JournalReminderScheduler.showReminder(appContext)
        JournalReminderScheduler.scheduleNext(appContext)
    }
}
