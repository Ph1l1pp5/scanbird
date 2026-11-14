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
 * Seit 0.5.12 liegt das Backup wieder IM festen Unterordner „Scanbird“ im
 * gewaehlten Ziel (oder direkt im Ziel, wenn dieses so heisst) — erkannt,
 * angelegt oder gemerkt. Gegen kDrives Aussetzer von 0.5.10 haertet die
 * Aufloesung ab: Die einmal gewonnene Dokument-ID wird gemerkt und im Alltag
 * direkt genutzt (keine Wurzel-Abfrage, kein Anlegen), und Kandidaten werden
 * nach Namen und Livetuechtigkeit geprueft statt nach MIME-Typ. „Aus Cloud
 * wiederherstellen“ zieht jede PDF dieses Ordners, die die App noch nicht
 * kennt, in den Arbeitsordner und legt Verlaufseintraege an — neues Geraet,
 * gleiche Liste.
 *
 * Fehlverhalten ist bewusst STILL: Ist das Ziel nicht erreichbar (Cloud
 * offline, App deinstalliert), geschieht nichts — beim naechsten Scan oder
 * App-Start zieht der Rueckstand automatisch nach. Selbstheilend statt laut.
 */
object BackupStore {

    /** Eine Datei im Backup-Zielordner — Grundlage der Aufräum-Ansicht. */
    data class BackupFile(
        val documentId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val lastModified: Long
    )

    /** Fester Ordnername im Ziel — das Wiedererkennungszeichen des Backups. */
    const val FOLDER_NAME = "Scanbird"

    /** Ordner-Picker: URI mitsamt dauerhaft haltbarer Berechtigung anfordern. */
    fun pickFolderIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }

    fun target(context: Context): Uri? = SettingsStore(context).backupTreeUri

    /** Lesbares Ziel-Etikett fuer die Einstellungen: der gewaehlte Ordner
     *  mit seinem Scanbird-Unterordner (0.5.12). */
    fun targetLabel(context: Context): String? {
        val uri = target(context) ?: return null
        val name = folderName(context, uri)
        return if (name == FOLDER_NAME) name else name + "/" + FOLDER_NAME
    }

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
        // Neues Ziel: die gemerkte Unterordner-ID gehoert zum alten Ziel.
        SettingsStore(context).backupFolderId = null
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
        SettingsStore(context).backupFolderId = null
    }

    /**
     * Echter Ordnername laut Anbieter (0.5.13): eine einzelne Dokument-Abfrage
     * fragt COLUMN_DISPLAY_NAME direkt beim Provider. Zuvor wurde der Name aus
     * der Dokument-ID erraten — kDrive-IDs tragen den Namen aber nicht, weshalb
     * der direkt gewaehlte Scanbird-Ordner nicht erkannt und ein doppelter
     * Unterordner angelegt wurde. Liefert null, wenn der Anbieter die Abfrage
     * nicht unterstuetzt; dann greift der ID-Fallback.
     */
    fun realDisplayName(context: Context, tree: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Ordnername: erst den Anbieter fragen (zuverlaessig), sonst ID-Raten. */
    fun folderName(context: Context, tree: Uri): String =
        realDisplayName(context, tree) ?: displayName(tree)

    /** Fallback: Name aus der Dokument-ID schneiden — nur noch Notnagel. */
    fun displayName(uri: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val name = id.substringAfterLast('/')
        val tail = if (name.contains(':')) name.substringAfter(':') else name
        return if (tail.isBlank()) id else tail
    }

    /**
     * Backup-Ordner aufloesen (0.5.12): Das Ziel IST wieder der gewaehlte
     * Ordner PLUS fester "Scanbird"-Unterordner — erkannt, angelegt oder
     * gemerkt. Zwei Haertungen gegen kDrives Aussetzer von 0.5.10:
     * 1. Die einmal aufgeloeste Dokument-ID wird gemerkt; der Alltagsbetrieb
     *    fragt sie direkt ab — keine Wurzel-Abfrage, kein Anlegen.
     * 2. Bei der Suche zaehlt der NAME, nicht der MIME-Typ; ein Treffer muss
     *    sich listen lassen, sonst ist er kein Ordner.
     * Liefert die Dokument-ID oder im Fehlerfall eine Diagnose.
     */
    data class ResolvedFolder(val documentId: String?, val error: String?)

    fun resolveFolder(context: Context): ResolvedFolder {
        val tree = target(context)
            ?: return ResolvedFolder(null, "Kein Zielordner gespeichert.")
        val settings = SettingsStore(context)
        // 1. Der gewaehlte Ordner heisst selbst "Scanbird": direkt nutzen —
        //    mit echtem Namen laut Anbieter (0.5.13), nicht geraten aus der
        //    ID, sonst entsteht hier ein Scanbird im Scanbird.
        if (folderName(context, tree) == FOLDER_NAME) {
            return ResolvedFolder(
                DocumentsContract.getTreeDocumentId(tree), null
            )
        }
        // 2. Gemerkte Unterordner-ID: laesst sich der Ordner noch listen,
        //    lebt er — dann direkt nutzen, ganz ohne Wurzel-Abfrage.
        val cachedId = settings.backupFolderId
        if (cachedId != null && settings.backupFolderTree == tree.toString()) {
            try {
                queryFiles(context, tree, cachedId)
                return ResolvedFolder(cachedId, null)
            } catch (e: Exception) {
                settings.backupFolderId = null
            }
        }
        // 3. Volle Aufloesung: Kandidaten nach Namen suchen, jeden als
        //    Ordner pruefen, sonst anlegen. Nur Erfolg wird gemerkt.
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val candidates = try {
            queryFiles(context, tree, rootId).filter { it.name == FOLDER_NAME }
        } catch (e: Exception) {
            return ResolvedFolder(
                null,
                "Wurzel-Abfrage: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
        for (candidate in candidates) {
            try {
                queryFiles(context, tree, candidate.documentId)
                settings.backupFolderTree = tree.toString()
                settings.backupFolderId = candidate.documentId
                return ResolvedFolder(candidate.documentId, null)
            } catch (e: Exception) {
            }
        }
        if (candidates.isNotEmpty()) {
            return ResolvedFolder(
                null,
                "Ordner pruefen: \"$FOLDER_NAME\" existiert, laesst sich aber " +
                    "nicht oeffnen — evtl. Sync-Problem der Cloud-App."
            )
        }
        val created = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                DocumentsContract.Document.MIME_TYPE_DIR,
                FOLDER_NAME
            )
        } catch (e: Exception) {
            return ResolvedFolder(
                null,
                "Ordner anlegen: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
        if (created == null) {
            return ResolvedFolder(
                null,
                "Ordner anlegen: createDocument lieferte null — existiert " +
                    "\"$FOLDER_NAME\" schon? Dann ihn direkt als Ziel whlen."
            )
        }
        val createdId = DocumentsContract.getDocumentId(created)
        settings.backupFolderTree = tree.toString()
        settings.backupFolderId = createdId
        return ResolvedFolder(createdId, null)
    }

    /**
     * Rueckstand sichern: alle Scans ohne Backup-Kopie in den Backup-Ordner
     * ziehen. Kein Ziel oder lesbarer Zielordner → still ausfallen; der
     * naechste Scan oder Start versucht es wieder. Liefert true, wenn der
     * ganze Rueckstand gesichert ist.
     */
    suspend fun ensureBackedUp(context: Context, dao: ScanDao): Boolean =
        withContext(Dispatchers.IO) {
            val tree = target(context) ?: return@withContext false
            // 0.5.12: Erkannter oder angelegter Scanbird-Unterordner —
            // im Alltag direkt ueber die gemerkte ID.
            val folderId = resolveFolder(context).documentId
                ?: return@withContext false
            try {
                val existing = queryFiles(context, tree, folderId)
                    .mapTo(HashSet()) { it.name }
                for (scan in dao.findNotBackedUp()) {
                    val fileName = scan.pdfFileName
                    if (fileName in existing) {
                        dao.markBackedUp(scan.id)
                        continue
                    }
                    val pfd = ScanStore.openRead(context, ScanStore.locate(context, fileName))
                    if (pfd == null) continue
                    val created = DocumentsContract.createDocument(
                        context.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, folderId),
                        "application/pdf", fileName
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
     * Wiederherstellen (0.5.2): Jede PDF aus dem Backup-Ordner, die die App
     * noch nicht kennt, in den Arbeitsordner kopieren und als Verlaufseintrag
     * anlegen — neues Geraet, verbundene Cloud, gleiche Liste. Der Vermerk
     * backedUp steht ab sofort auf wahr, die Datei ist ja schon in der Cloud.
     * Liefert die Zahl der zurueckgeholten Scans; null bei Fehler.
     */
    suspend fun restoreMissing(context: Context, dao: ScanDao): Int? =
        withContext(Dispatchers.IO) {
            val tree = target(context) ?: return@withContext null
            val folderId = resolveFolder(context).documentId
                ?: return@withContext null
            try {
                val known = dao.allFileNames().toHashSet()
                var restored = 0
                for (file in queryFiles(context, tree, folderId)) {
                    if (file.name !in known &&
                        file.name.endsWith(".pdf", ignoreCase = true)
                    ) {
                        if (restoreOne(context, tree, file, dao)) restored++
                    }
                }
                restored
            } catch (e: Exception) {
                null
            }
        }

    /** Zahl wiederherstellbarer PDFs — steuert, ob der Restore-Knopf erscheint. */
    suspend fun countRestorable(context: Context, dao: ScanDao): Int =
        withContext(Dispatchers.IO) {
            val tree = target(context) ?: return@withContext 0
            val folderId = resolveFolder(context).documentId
                ?: return@withContext 0
            try {
                val known = dao.allFileNames().toHashSet()
                queryFiles(context, tree, folderId).count {
                    it.name !in known && it.name.endsWith(".pdf", ignoreCase = true)
                }
            } catch (e: Exception) {
                0
            }
        }

    /** Eine Cloud-PDF in den Arbeitsordner ziehen und eintragen. */
    private suspend fun restoreOne(
        context: Context,
        tree: Uri,
        file: BackupFile,
        dao: ScanDao
    ): Boolean {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId)
        val targetPdf = try {
            ScanStore.createPdf(context, file.name)
        } catch (e: Exception) {
            return false
        }
        var ok = false
        try {
            context.contentResolver.openInputStream(docUri)?.use { input ->
                targetPdf.stream.use { input.copyTo(it) }
            }
            ok = true
        } catch (e: Exception) {
        }
        if (!ok) {
            try { targetPdf.abandon() } catch (e: Exception) { }
            return false
        }
        try { targetPdf.finish() } catch (e: Exception) { }
        dao.insert(
            ScanEntity(
                title = titleFromFileName(file.name),
                createdAt = if (file.lastModified > 0) {
                    file.lastModified
                } else {
                    System.currentTimeMillis()
                },
                pdfFileName = file.name,
                backedUp = true
            )
        )
        return true
    }

    /**
     * Alte Backup-Kopie nach einer Umbenennung aus dem Scanbird-Ordner
     * entfernen, falls vorhanden — so bleibt der Ordner frei von Rumpelkopien,
     * und die Datei wandert beim naechsten Sicherungslauf unter ihrem neuen
     * Namen hoch.
     */
    fun removeBackupName(context: Context, fileName: String) {
        val tree = target(context) ?: return
        val folderId = resolveFolder(context).documentId ?: return
        try {
            for (child in queryFiles(context, tree, folderId)) {
                if (child.name == fileName) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(
                        tree, child.documentId
                    )
                    try { context.contentResolver.delete(docUri, null, null) } catch (e: Exception) { }
                    break
                }
            }
        } catch (e: Exception) {
        }
    }

    /**
     * Alle Dateien im Backup-Zielordner, neueste zuerst — die Aufräum-Ansicht in
     * den Einstellungen. Seit 0.5.10 mit Diagnose: Schlägt ein Schritt fehl,
     * nennt error den Schritt samt Ausnahme — statt null und Stille.
     */
    data class ListResult(
        val files: List<BackupFile>?,
        val error: String?
    )

    fun listBackupFiles(context: Context): ListResult {
        val tree = target(context)
            ?: return ListResult(null, "Kein Zielordner gespeichert.")
        // 0.5.12: Auflösung über resolveFolder (gemerkte ID im Alltag), dann
        // nur PDFs listen — der Aufräum-Bildschirm gehört den Scans, nicht
        // dem sonstigen Inhalt der Cloud.
        val resolved = resolveFolder(context)
        if (resolved.documentId == null) {
            return ListResult(null, resolved.error)
        }
        return try {
            ListResult(
                queryFiles(context, tree, resolved.documentId)
                    .filter { it.name.endsWith(".pdf", ignoreCase = true) },
                null
            )
        } catch (e: Exception) {
            ListResult(
                null,
                "Ordnerinhalt-Abfrage: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * Dateien im Scanbird-Ordner loeschen (Cloud-Aufräumen). Das Backup ist
     * danach bewusst weg: Aufräumen heisst NICHT beim naechsten Lauf wieder
     * hochladen — der backedUp-Vermerk in der Datenbank bleibt unberuehrt.
     * Die Scans auf dem Geraet bleiben immer erhalten. Liefert die Zahl der
     * geloeschten Dateien.
     */
    fun deleteBackupFiles(context: Context, documentIds: List<String>): Int {
        val tree = target(context) ?: return 0
        var deleted = 0
        for (id in documentIds) {
            try {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (context.contentResolver.delete(docUri, null, null) > 0) deleted++
            } catch (e: Exception) {
            }
        }
        return deleted
    }

    /**
     * Umbenennen in der Cloud (Aufräum-Ansicht). Liefert den neuen Namen oder
     * null, wenn der Anbieter das Umbenennen nicht unterstuetzt oder es
     * misslingt.
     */
    fun renameBackupFile(
        context: Context,
        documentId: String,
        newFileName: String
    ): String? {
        val tree = target(context) ?: return null
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            val renamed = DocumentsContract.renameDocument(
                context.contentResolver, docUri, newFileName
            )
            if (renamed != null) newFileName else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Anzeige-Titel aus dem Backup-Dateinamen: Zeitstempel-Präfix abtrennen,
     * Unterstriche zu Leerzeichen machen — so sieht die Cloud-Liste aus wie
     * der Verlauf.
     */
    fun titleFromFileName(name: String): String {
        val base = name.removeSuffix(".pdf")
        val tail = if (base.contains("__")) base.substringAfter("__") else base
        val title = tail.replace('_', ' ').trim()
        return if (title.isBlank()) name else title
    }

    /** Kinder eines Ordners im Ziel: alle Dokumente mit Name, Typ, Groesse,
     *  Aenderungsdatum — neueste zuerst. */
    private fun queryFiles(
        context: Context,
        tree: Uri,
        parentDocId: String
    ): List<BackupFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, parentDocId
        )
        val result = ArrayList<BackupFile>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idCol >= 0 && nameCol >= 0) {
                while (c.moveToNext()) {
                    result.add(
                        BackupFile(
                            documentId = c.getString(idCol) ?: continue,
                            name = c.getString(nameCol) ?: "",
                            mime = if (mimeCol >= 0) c.getString(mimeCol) ?: "" else "",
                            size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                            lastModified = if (modCol >= 0) c.getLong(modCol) else 0L
                        )
                    )
                }
            }
        }
        result.sortByDescending { it.lastModified }
        return result
    }
}
