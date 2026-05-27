package com.daksh.anytimejournal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

object NotificationHelper {
    const val CHANNEL_ID = "anytime_journal_input_silent_v2"
    private const val SERVICE_CHANNEL_ID = "anytime_journal_service_hidden_v3"
    private const val LEGACY_INPUT_CHANNEL_ID = "anytime_journal_input"
    private const val MENTION_CHANNEL_ID = "anytime_journal_mentions"
    private const val CALL_CHANNEL_ID = "anytime_journal_calls"
    const val GROUP_KEY = "com.daksh.anytimejournal.ALL_NOTIFICATIONS"
    const val NOTIFICATION_ID = 1001
    private const val MENTION_NOTIFICATION_BASE_ID = 2100
    private const val CALL_NOTIFICATION_ID = 2401
    const val ACTION_SAVE_REPLY = "com.daksh.anytimejournal.SAVE_REPLY"
    const val KEY_REPLY_TEXT = "reply_text"
    const val EXTRA_ENTRY_KIND = "entry_kind"

    fun foregroundBubbleNotification(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        createServiceChannel(manager)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            7,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_journal_notification)
            .setContentTitle("Anytime Journal")
            .setContentText("")
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSortKey("9_service")
            .build()
    }

    fun showPersistentInput(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createChannel(manager)
        manager.notify(NOTIFICATION_ID, buildNotification(context))
    }

    fun refreshCompactHub(context: Context) {
        showPersistentInput(context)
    }

    fun showMention(context: Context, author: String, body: String, createdAtMillis: Long, targetProfile: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createMentionChannel(manager)
        val notificationId = MENTION_NOTIFICATION_BASE_ID + "$author|$body|$createdAtMillis"
            .hashCode()
            .and(0x0fffffff)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_COLLAB
            putExtra(MainActivity.EXTRA_OPEN_COLLAB, true)
            putExtra(MainActivity.EXTRA_PROFILE, targetProfile)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MENTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_journal_notification)
            .setContentTitle("$author mentioned you")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(createdAtMillis)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSortKey("2_mentions_$createdAtMillis")
            .build()
        manager.notify(notificationId, notification)
        refreshCompactHub(context)
    }

    fun showIncomingCall(context: Context, author: String, targetProfile: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createCallChannel(manager)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID,
            callIntent(context, targetProfile, accept = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val acceptPendingIntent = PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID + 1,
            callIntent(context, targetProfile, accept = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_journal_notification)
            .setContentTitle("${author.removePrefix("@")} calling")
            .setContentText("Accept starts your mic")
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(openPendingIntent, true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_journal_notification, "Reply", openPendingIntent)
            .addAction(R.drawable.ic_journal_notification, "Accept", acceptPendingIntent)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSortKey("1_call")
            .build()
        manager.notify(CALL_NOTIFICATION_ID, notification)
        refreshCompactHub(context)
    }

    fun cancelIncomingCall(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(CALL_NOTIFICATION_ID)
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        cleanupLegacySilentChannels(manager)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Anytime Journal Input",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Persistent quick input for journal and idea entries"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun createServiceChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        cleanupLegacySilentChannels(manager)
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "Anytime Journal Background",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Minimal background service for bubble and live collab"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private fun cleanupLegacySilentChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.deleteNotificationChannel(LEGACY_INPUT_CHANNEL_ID)
    }

    private fun createMentionChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            MENTION_CHANNEL_ID,
            "Anytime Journal Mentions",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Live notifications when another collab user mentions you"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun createCallChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CALL_CHANNEL_ID,
            "Anytime Journal Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming WebRTC calls from collab users"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun callIntent(context: Context, targetProfile: String, accept: Boolean): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_COLLAB
            putExtra(MainActivity.EXTRA_OPEN_COLLAB, true)
            putExtra(MainActivity.EXTRA_PROFILE, targetProfile)
            putExtra(MainActivity.EXTRA_ACCEPT_CALL, accept)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    private fun buildNotification(context: Context): Notification {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_journal_notification)
            .setContentTitle("Anytime Journal")
            .setContentText("Quick capture, reminders, chat")
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(contentIntent)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSortKey("0_hub")
            .addAction(buildReplyAction(context, JournalEntryInput.KIND_JOURNAL, "Journal", 0))
            .addAction(buildReplyAction(context, JournalEntryInput.KIND_IDEA, "Idea", 1))
            .addAction(buildReplyAction(context, JournalEntryInput.KIND_TASK, "Task", 2))
            .build()
    }

    private fun buildReplyAction(
        context: Context,
        kind: String,
        label: String,
        requestCode: Int,
    ): NotificationCompat.Action {
        val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
            action = ACTION_SAVE_REPLY
            putExtra(EXTRA_ENTRY_KIND, kind)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Write $label entry")
            .build()

        return NotificationCompat.Action.Builder(
            R.drawable.ic_journal_notification,
            label,
            replyPendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAuthenticationRequired(false)
            .setShowsUserInterface(false)
            .build()
    }
}
