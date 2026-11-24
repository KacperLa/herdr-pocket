package dev.kaspy.herdr

import android.content.SharedPreferences

/** Viewer settings belong to this app, not to a file, terminal or retained Activity. */
internal class FileViewerPreferences(private val prefs: SharedPreferences) {
    fun wrap(): Boolean = prefs.getBoolean(WRAP, true)
    fun lineNumbers(): Boolean = prefs.getBoolean(LINE_NUMBERS, false)

    fun setWrap(value: Boolean) {
        prefs.edit().putBoolean(WRAP, value).apply()
    }

    fun setLineNumbers(value: Boolean) {
        prefs.edit().putBoolean(LINE_NUMBERS, value).apply()
    }

    private companion object {
        const val WRAP = "file_viewer_wrap"
        const val LINE_NUMBERS = "file_viewer_line_numbers"
    }
}
