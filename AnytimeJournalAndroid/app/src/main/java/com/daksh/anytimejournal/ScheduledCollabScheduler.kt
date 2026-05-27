package com.daksh.anytimejournal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object ScheduledCollabScheduler {
    private const val ACTION_SEND_COLLAB = "com.daksh.anytimejournal.SEND_SCHEDULED_COLLAB"
    private const val EXTRA_ENTRY_ID = "entry_id"
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_SEND_AT = "send_at"
    private const val EXTRA_LOCAL_PROFILE = "local_profile"

    fun schedule(
        context: Context,
        entryId: Long,
        text: String,
        sendAtMillis: Long,
        localProfile: String,
    ) {
        if (sendAtMillis <= System.currentTimeMillis()) {
            cancel(context, entryId)
            return
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val sendIntent = pendingIntent(
            context,
            entryId,
            text,
            sendAtMillis,
            localProfile,
            PendingIntent.FLAG_UPDATE_CURRENT,
        ) ?: return
        scheduleAlarm(alarmManager, sendAtMillis, sendIntent)
    }

    fun cancel(context: Context, entryId: Long) {
        val pendingIntent = pendingIntent(context, entryId, "", 0L, "", PendingIntent.FLAG_NO_CREATE)
        if (pendingIntent != null) {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun pendingIntent(
        context: Context,
        entryId: Long,
        text: String,
        sendAtMillis: Long,
        localProfile: String,
        flag: Int,
    ): PendingIntent? {
        val intent = Intent(context, ScheduledCollabReceiver::class.java).apply {
            action = ACTION_SEND_COLLAB
            putExtra(EXTRA_ENTRY_ID, entryId)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_SEND_AT, sendAtMillis)
            putExtra(EXTRA_LOCAL_PROFILE, localProfile)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(entryId),
            intent,
            flag or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun handleReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND_COLLAB) return
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
        val sendAtMillis = intent.getLongExtra(EXTRA_SEND_AT, System.currentTimeMillis())
        val localProfile = intent.getStringExtra(EXTRA_LOCAL_PROFILE).orEmpty().trim().lowercase()
        if (text.isBlank()) return
        postCloud(context.applicationContext, text, sendAtMillis, localProfile)
    }

    private fun postCloud(context: Context, text: String, createdAtMillis: Long, localProfile: String) {
        val config = AppPrefs.readCloudConfig(context) ?: return

        val parsed = CollabMessage.parse(text, localProfile.ifBlank { "@daksh" })
        val sourceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"
        val payload = JSONObject()
            .put("source_id", sourceId)
            .put("author", parsed.author)
            .put("body", parsed.body)
            .put("text", text)
            .put("kind", JournalEntryInput.KIND_COLLAB)
            .put("created_at_millis", createdAtMillis)
            .toString()

        runCatching {
            val connection = (URL("${config.restUrl}/collab_entries").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CLOUD_TIMEOUT_MS
                readTimeout = CLOUD_TIMEOUT_MS
                doOutput = true
                setRequestProperty("apikey", config.anonKey)
                setRequestProperty("Authorization", "Bearer ${config.anonKey}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "return=minimal")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
        }
    }

    private fun scheduleAlarm(alarmManager: AlarmManager, triggerAtMillis: Long, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }
    }

    private fun requestCode(entryId: Long): Int {
        return REQUEST_OFFSET + (entryId xor (entryId ushr 32)).toInt().and(0x0fffffff)
    }

    private const val REQUEST_OFFSET = 600_000
    private const val CLOUD_TIMEOUT_MS = 1500
}

class ScheduledCollabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        Thread {
            try {
                ScheduledCollabScheduler.handleReceive(context, intent)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
