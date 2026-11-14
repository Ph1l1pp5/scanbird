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

    /** Aufgeloeste Dokument-ID des Scanbird-Unterordners (0.5.12). Der
     *  Alltagsbetrieb liest sie direkt — ohne Wurzel-Abfrage. */
    var backupFolderId: String?
        get() = prefs.getString(KEY_FOLDER_ID, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_ID, value).apply()

    /** Zielordner-URI, zu dem backupFolderId gehoert — wechselt das Ziel,
     *  ist die gemerkte ID wertlos und wird ignoriert. */
    var backupFolderTree: String?
        get() = prefs.getString(KEY_FOLDER_TREE, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_TREE, value).apply()

    companion object {
        private const val KEY_TEXT = "standard_text"
        private const val KEY_BACKUP = "backup_tree_uri"
        private const val KEY_FOLDER_ID = "backup_folder_id"
        private const val KEY_FOLDER_TREE = "backup_folder_tree"
    }
}
