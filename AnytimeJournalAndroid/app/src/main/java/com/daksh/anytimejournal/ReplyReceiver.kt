package com.daksh.anytimejournal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationHelper.ACTION_SAVE_REPLY) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationHelper.KEY_REPLY_TEXT)
        val kind = intent.getStringExtra(NotificationHelper.EXTRA_ENTRY_KIND)
            ?: JournalEntryInput.KIND_JOURNAL

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val savedEntryId = EntryRepository.from(appContext).saveReply(replyText, kind = kind)
                if (savedEntryId != null && kind == JournalEntryInput.KIND_TASK) {
                    TaskReminderScheduler.schedule(
                        appContext,
                        savedEntryId,
                        replyText?.toString().orEmpty(),
                        System.currentTimeMillis() + JournalEntryInput.DEFAULT_TASK_REMINDER_MS,
                    )
                }
                NotificationHelper.showPersistentInput(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
