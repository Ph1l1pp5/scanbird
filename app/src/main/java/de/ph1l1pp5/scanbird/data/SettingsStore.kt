package de.ph1l1pp5.scanbird.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var standardText: String
        get() = prefs.getString(KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY, value).apply()

    companion object {
        private const val KEY = "standard_text"
    }
}
