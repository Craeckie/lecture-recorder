package org.Craeckie.lecturerecorder

import android.content.Context
import android.util.Log

// The capture-mode override the user picked in the in-app selector, persisted across
// launches.
//
// Why this exists at all: which mode actually reaches an attached USB microphone is an
// empirical question about the device, not something the shell can derive -- see the
// probe in docs/superpowers/plans/2026-08-30-capture-mode-resolution.md. Being able to
// switch modes between two recordings, on the phone, is what makes that measurable
// without a computer attached; it stays afterwards as the manual override for a room
// where the automatic rule guesses wrong.
//
// `null` means "no override": MicBridge then falls back to the automatic rule (USB
// selected -> voice, otherwise raw). Every write goes through CaptureModes.sanitize, so a
// value from an older build that no longer names a mode reads back as null rather than
// reaching the getUserMedia patch.
class CaptureModePreference(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var override: String?
        get() = CaptureModes.sanitize(prefs.getString(KEY_MODE, null))
        set(value) {
            val clean = CaptureModes.sanitize(value)
            prefs.edit().apply {
                if (clean == null) remove(KEY_MODE) else putString(KEY_MODE, clean)
            }.apply()
            Log.i(LOG_TAG, "Capture mode override set to '${clean ?: "automatic"}'")
        }

    private companion object {
        const val PREFS_NAME = "capture-mode"
        const val KEY_MODE = "override"
    }
}
