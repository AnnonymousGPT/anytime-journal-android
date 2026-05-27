package com.daksh.anytimejournal

import android.app.Activity

/**
 * Phase-1 boundary for the top-level native screen shell.
 *
 * MainActivity still owns the actual view tree while the refactor is in flight.
 * The next migration step moves buildHeader/buildSearchPanel/buildCapturePanel wiring here.
 */
class MainScreenRenderer(
    private val activity: Activity,
) {
    fun context(): Activity = activity
}
