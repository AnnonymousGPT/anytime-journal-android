package com.daksh.anytimejournal

import android.view.View

/**
 * Phase-1 boundary for feed/chat entry rendering.
 *
 * MainActivity currently delegates page matching to PageRenderer and still owns the
 * concrete Android views. Moving entryBlock/collabEntryBlock here is the next safe step.
 */
class EntryListRenderer(
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onEditEntry(view: View, entry: EntryEntity)
        fun onEntryDeleted()
    }

    fun matchesPage(entry: EntryEntity, activeFilter: String?): Boolean {
        return PageRenderer.entryMatchesPage(entry, activeFilter)
    }

    fun edit(view: View, entry: EntryEntity) {
        callbacks.onEditEntry(view, entry)
    }

    fun deleted() {
        callbacks.onEntryDeleted()
    }
}
