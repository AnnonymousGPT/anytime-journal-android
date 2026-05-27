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
import kotlin.math.max

object FocusSessionScheduler {
    private const val CHANNEL_ID = "anytime_focus_sessions_v2"
    const val ACTION_SAVE_UNLOCK_INTENT = "com.daksh.anytimejournal.SAVE_UNLOCK_INTENT"
    private const val ACTION_FOCUS_REMINDER = "com.daksh.anytimejournal.FOCUS_REMINDER"
    const val KEY_FOCUS_PURPOSE = "focus_purpose"
    private const val EXTRA_DURATION_MINUTES = "duration_minutes"
    private const val EXTRA_PURPOSE = "purpose"
    private const val EXTRA_DUE_AT = "due_at"
    private const val PROMPT_NOTIFICATION_ID = 4200
    private const val REMINDER_NOTIFICATION_BASE_ID = 4300

    fun showUnlockPrompt(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(manager)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            PROMPT_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            PROMPT_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_journal_notification)
                .setContentTitle("Before unlock")
                .setContentText("Kaam likho, duration choose karo")
                .setShowWhen(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(contentIntent)
                .addAction(intentAction(context, 10))
                .addAction(intentAction(context, 30))
                .addAction(intentAction(context, 60))
                .setGroup(NotificationHelper.GROUP_KEY)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setSortKey("1_before_unlock")
                .build(),
        )
        NotificationHelper.refreshCompactHub(context)
    }

    fun scheduleFocusReminder(context: Context, purpose: String, durationMinutes: Int) {
        val cleanPurpose = purpose.trim()
        if (cleanPurpose.isBlank()) return
        val minutes = max(1, durationMinutes)
        val now = System.currentTimeMillis()
        val dueAtMillis = now + minutes * 60_000L
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val requestCode = requestCode(now, cleanPurpose)
        val intent = Intent(context, FocusSessionReceiver::class.java).apply {
            action = ACTION_FOCUS_REMINDER
            putExtra(EXTRA_PURPOSE, cleanPurpose)
            putExtra(EXTRA_DUE_AT, dueAtMillis)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleAlarm(alarmManager, dueAtMillis, pendingIntent)
        showActiveFocus(context, cleanPurpose, minutes, dueAtMillis, requestCode)
    }

    internal fun handleReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SAVE_UNLOCK_INTENT -> {
                val purpose = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_FOCUS_PURPOSE)
                    ?.toString()
                    .orEmpty()
                val minutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 10)
                scheduleFocusReminder(context, purpose, minutes)
                context.getSystemService(NotificationManager::class.java).cancel(PROMPT_NOTIFICATION_ID)
                saveFocusStart(context, purpose, minutes)
            }
            ACTION_FOCUS_REMINDER -> {
                val purpose = intent.getStringExtra(EXTRA_PURPOSE).orEmpty()
                val dueAtMillis = intent.getLongExtra(EXTRA_DUE_AT, System.currentTimeMillis())
                showDueFocus(context, purpose, dueAtMillis)
                saveFocusDue(context, purpose)
            }
        }
    }

    private fun intentAction(context: Context, minutes: Int): NotificationCompat.Action {
        val replyIntent = Intent(context, FocusSessionReceiver::class.java).apply {
            action = ACTION_SAVE_UNLOCK_INTENT
            putExtra(EXTRA_DURATION_MINUTES, minutes)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            4210 + minutes,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(KEY_FOCUS_PURPOSE)
            .setLabel("Kaam likho")
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_journal_notification,
            "${minutes}m",
            pendingIntent,
        )
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAuthenticationRequired(false)
            .build()
    }

    private fun showActiveFocus(
        context: Context,
        purpose: String,
        minutes: Int,
        dueAtMillis: Long,
        requestCode: Int,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(manager)
        manager.notify(
            REMINDER_NOTIFICATION_BASE_ID + requestCode.and(0x0fff),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_journal_notification)
                .setContentTitle("Focus reminder set")
                .setContentText(EntryUiFormatter.compactPreview(purpose, 80))
                .setSubText("$minutes min")
                .setWhen(dueAtMillis)
                .setShowWhen(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setGroup(NotificationHelper.GROUP_KEY)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setSortKey("4_focus_active_$dueAtMillis")
                .build(),
        )
        NotificationHelper.refreshCompactHub(context)
    }

    private fun showDueFocus(context: Context, purpose: String, dueAtMillis: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(manager)
        manager.notify(
            REMINDER_NOTIFICATION_BASE_ID + requestCode(dueAtMillis, purpose).and(0x0fff),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_journal_notification)
                .setContentTitle("Task kya tha?")
                .setContentText(EntryUiFormatter.compactPreview(purpose, 90))
                .setWhen(dueAtMillis)
                .setShowWhen(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setGroup(NotificationHelper.GROUP_KEY)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setSortKey("1_focus_due_$dueAtMillis")
                .build(),
        )
        NotificationHelper.refreshCompactHub(context)
    }

    private fun saveFocusStart(context: Context, purpose: String, minutes: Int) {
        if (purpose.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            EntryRepository.from(context).saveReply(
                "Focus start: $purpose #task #focus ${minutes}m",
                kind = JournalEntryInput.KIND_JOURNAL,
            )
        }
    }

    private fun saveFocusDue(context: Context, purpose: String) {
        if (purpose.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            EntryRepository.from(context).saveReply(
                "Focus check: $purpose #journal #focus",
                kind = JournalEntryInput.KIND_JOURNAL,
            )
        }
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Focus prompts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Unlock intent prompts and focus reminders"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

    private fun requestCode(seed: Long, purpose: String): Int {
        return (seed xor purpose.hashCode().toLong()).toInt().and(0x0fffffff)
    }
}

class UnlockPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            FocusSessionScheduler.showUnlockPrompt(context.applicationContext)
        }
    }
}

class FocusSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        Thread {
            try {
                FocusSessionScheduler.handleReceive(context.applicationContext, intent)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}

class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val appContext = context.applicationContext
            JournalReminderScheduler.scheduleNext(appContext)
            runCatching {
                val serviceIntent = Intent(appContext, BubbleInputService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(serviceIntent)
                } else {
                    appContext.startService(serviceIntent)
                }
            }
        }
    }
}
