package de.ph1l1pp5.scanbird.data

import android.content.Context
import android.net.Uri

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var standardText: String
        get() = prefs.getString(KEY_TEXT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TEXT, value).apply()

    /** SAF-Zielordner des Backups (0.5.0) — null heisst: kein Backup eingerichtet. */
    var backupTreeUri: Uri?
        get() = prefs.getString(KEY_BACKUP, null)?.let { raw ->
            try { Uri.parse(raw) } catch (e: Exception) { null }
        }
        set(value) = prefs.edit().putString(KEY_BACKUP, value?.toString()).apply()

    /** Backup-Nachfrage dauerhaft abgelehnt (0.5.2) — einrichten geht trotzdem immer. */
    var backupPromptDeclined: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_DECLINED, false)
        set(value) = prefs.edit().putBoolean(KEY_PROMPT_DECLINED, value).apply()

    companion object {
        private const val KEY_TEXT = "standard_text"
        private const val KEY_BACKUP = "backup_tree_uri"
        private const val KEY_PROMPT_DECLINED = "backup_prompt_declined"
    }
}
