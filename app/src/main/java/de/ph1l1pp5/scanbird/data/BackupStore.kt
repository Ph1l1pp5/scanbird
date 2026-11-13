package de.ph1l1pp5.scanbird.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/**
 * Provider-agnostisches Backup ueber das Storage Access Framework (SAF):
 * Der Nutzer waehlt EINMAL einen Zielordner in einer beliebigen Cloud-App,
 * einem USB-Laufwerk oder aehnlichem — die App merkt sich die URI dauerhaft
 * und kopiert danach automatisch jede fertige PDF dorthin. Ohne Cloud-SDK,
 * ohne Account, ohne Internet-Berechtigung; die App kennt den Anbieter nicht.
 * Wechselt der Anbieter, wechselt nur der gewaehlte Ordner.
 *
 * Der Zielordner bleibt FLACH: alle PDFs liegen direkt drin, der Titel steckt
 * im Dateinamen. listChildren liefert die Namensliste — die Grundlage fuer
 * den spaeteren Restore (Verlauf aus dem Backup rekonstruieren).
 *
 * Fehlverhalten ist bewusst STILL: Ist das Ziel nicht erreichbar (Cloud
 * offline, App deinstalliert), geschieht nichts — beim naechsten Scan oder
 * App-Start zieht der Rueckstand automatisch nach. Selbstheilend statt laut.
 */
object BackupStore {

    /** Ordner-Picker: URI mitsamt dauerhaft haltbarer Berechtigung anfordern. */
    fun pickFolderIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSIST_URI_PERMISSION
        )
    }

    fun target(context: Context): Uri? = SettingsStore(context).backupTreeUri

    /** Neues Ziel setzen: Berechtigung dauerhaft nehmen, das alte freigeben. */
    fun saveTarget(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        val old = target(context)
        if (old != null && old != uri) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
        }
        SettingsStore(context).backupTreeUri = uri
    }

    /** Ziel entfernen: Berechtigung freigeben, Einstellung loeschen. */
    fun clearTarget(context: Context) {
        val old = target(context)
        if (old != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
        }
        SettingsStore(context).backupTreeUri = null
    }

    /** Lesbarer Ordnername fuer die Einstellungen (z. B. „Scanbird-Backup“). */
    fun displayName(uri: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val name = id.substringAfterLast('/')
        val tail = if (name.contains(':')) name.substringAfter(':') else name
        return if (tail.isBlank()) id else tail
    }

    /**
     * Rueckstand sichern: alle Scans ohne Backup-Kopie in den Zielordner
     * ziehen. Kein Ziel oder lesbarer Zielordner → still ausfallen; der
     * naechste Scan oder Start versucht es wieder. Liefert true, wenn der
     * ganze Rueckstand gesichert ist.
     */
    suspend fun ensureBackedUp(context: Context, dao: ScanDao): Boolean =
        withContext(Dispatchers.IO) {
            val tree = target(context) ?: return@withContext false
            try {
                val existing = listChildren(context, tree)
                    ?: return@withContext false
                for (scan in dao.findNotBackedUp()) {
                    val fileName = scan.pdfFileName
                    if (fileName in existing) {
                        dao.markBackedUp(scan.id)
                        continue
                    }
                    val pfd = ScanStore.openRead(context, ScanStore.locate(context, fileName))
                    if (pfd == null) continue
                    val created = DocumentsContract.createDocument(
                        context.contentResolver, tree, "application/pdf", fileName
                    )
                    if (created == null) return@withContext false
                    var copyOk = false
                    try {
                        context.contentResolver.openOutputStream(created)?.use { out ->
                            FileInputStream(pfd.fileDescriptor).use { input ->
                                input.copyTo(out)
                            }
                        }
                        copyOk = true
                    } catch (e: Exception) {
                        try { context.contentResolver.delete(created, null, null) } catch (e2: Exception) { }
                    } finally {
                        try { pfd.close() } catch (e: Exception) { }
                    }
                    if (!copyOk) return@withContext false
                    dao.markBackedUp(scan.id)
                }
                true
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Alte Backup-Kopie nach einer Umbenennung aus dem Ziel entfernen, falls
     * vorhanden — so bleibt der flache Ordner frei von Rumpelkopien, und die
     * Datei wandert beim naechsten Sicherungslauf unter ihrem neuen Namen hoch.
     */
    fun removeBackupName(context: Context, fileName: String) {
        val tree = target(context) ?: return
        try {
            val root = DocumentsContract.getTreeDocumentId(tree)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idCol >= 0 && nameCol >= 0) {
                    while (c.moveToNext()) {
                        if (c.getString(nameCol) == fileName) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                                tree, c.getString(idCol)
                            )
                            try { context.contentResolver.delete(docUri, null, null) } catch (e: Exception) { }
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    /** Namen aller Dateien im Zielordner — Restore-Grundlage fuer spaeter. */
    private fun listChildren(context: Context, tree: Uri): HashSet<String>? = try {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        val names = HashSet<String>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            val col = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (col >= 0) while (c.moveToNext()) names.add(c.getString(col))
        }
        names
    } catch (e: Exception) {
        null
    }
}
