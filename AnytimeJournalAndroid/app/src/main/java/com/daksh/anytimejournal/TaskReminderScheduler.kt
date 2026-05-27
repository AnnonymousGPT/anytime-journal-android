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
import kotlin.math.max

object TaskReminderScheduler {
    private const val CHANNEL_ID = "anytime_journal_reminders"
    private const val ACTION_TASK_REMINDER = "com.daksh.anytimejournal.TASK_REMINDER"
    private const val EXTRA_ENTRY_ID = "entry_id"
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_DUE_AT = "due_at"
    private const val NOTIFICATION_BASE_ID = 3000

    fun schedule(context: Context, entryId: Long, text: String, dueAtMillis: Long) {
        val now = System.currentTimeMillis()
        if (dueAtMillis <= now) {
            cancel(context, entryId)
            return
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = reminderIntent(context, entryId, text, dueAtMillis)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(entryId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        scheduleAlarm(alarmManager, dueAtMillis, pendingIntent)
        showScheduledNotification(context, entryId, text, dueAtMillis)
    }

    fun cancel(context: Context, entryId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(entryId),
            reminderIntent(context, entryId, "", 0L),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(entryId))
    }

    fun showDueNotification(context: Context, entryId: Long, text: String, dueAtMillis: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(manager)
        manager.notify(
            notificationId(entryId),
            buildNotification(
                context = context,
                title = "Task due now",
                text = text,
                subText = "Scheduled for ${EntryUiFormatter.kindLabel(JournalEntryInput.KIND_TASK)}",
                dueAtMillis = dueAtMillis,
                ongoing = false,
            ),
        )
        NotificationHelper.refreshCompactHub(context)
    }

    private fun showScheduledNotification(context: Context, entryId: Long, text: String, dueAtMillis: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(manager)
        val minutes = minutesRemaining(dueAtMillis)
        manager.notify(
            notificationId(entryId),
            buildNotification(
                context = context,
                title = "Task reminder set",
                text = text,
                subText = "$minutes min remaining",
                dueAtMillis = dueAtMillis,
                ongoing = true,
            ),
        )
        NotificationHelper.refreshCompactHub(context)
    }

    private fun buildNotification(
        context: Context,
        title: String,
        text: String,
        subText: String,
        dueAtMillis: Long,
        ongoing: Boolean,
    ): Notification {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            20,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_journal_notification)
            .setContentTitle(title)
            .setContentText(EntryUiFormatter.compactPreview(text, maxLength = 80))
            .setSubText(subText)
            .setWhen(dueAtMillis)
            .setShowWhen(true)
            .setOngoing(ongoing)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setGroup(NotificationHelper.GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSortKey(if (ongoing) "4_task_set_$dueAtMillis" else "1_task_due_$dueAtMillis")
            .build()
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Task reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Scheduled task reminders from Anytime Journal"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun reminderIntent(context: Context, entryId: Long, text: String, dueAtMillis: Long): Intent {
        return Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_TASK_REMINDER
            putExtra(EXTRA_ENTRY_ID, entryId)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_DUE_AT, dueAtMillis)
        }
    }

    private fun minutesRemaining(dueAtMillis: Long): Long {
        val millis = dueAtMillis - System.currentTimeMillis()
        return max(1L, (millis + 59_999L) / 60_000L)
    }

    private fun scheduleAlarm(alarmManager: AlarmManager, triggerAtMillis: Long, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }
    }

    private fun requestCode(entryId: Long): Int = (entryId xor (entryId ushr 32)).toInt()

    private fun notificationId(entryId: Long): Int = NOTIFICATION_BASE_ID + requestCode(entryId).and(0x0fffffff)
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getLongExtra("entry_id", 0L)
        val text = intent.getStringExtra("text").orEmpty()
        val dueAtMillis = intent.getLongExtra("due_at", System.currentTimeMillis())
        TaskReminderScheduler.showDueNotification(context.applicationContext, entryId, text, dueAtMillis)
    }
}
