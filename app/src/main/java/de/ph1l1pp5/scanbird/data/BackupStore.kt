package de.ph1l1pp5.scanbird.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import de.ph1l1pp5.scanbird.pdf.PdfMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Provider-agnostisches Backup ueber das Storage Access Framework (SAF):
 * Der Nutzer waehlt EINMAL einen Wunschordner in einer beliebigen Cloud-App,
 * auf einem USB-Laufwerk oder aehnlichem — die App merkt sich die URI
 * dauerhaft und kopiert danach automatisch jede fertige PDF hinein. Ohne
 * Cloud-SDK, ohne Account, ohne Internet-Berechtigung; die App kennt den
 * Anbieter nicht. Wechselt der Anbieter, wechselt nur der gewaehlte Ordner.
 *
 * Seit 0.5.20 gilt das Wunschordner-Prinzip: Das gewaehlte Ziel IST das
 * Backup — kein Scanbird-Unterordner, kein Anlegen, nichts aufzuloesen.
 * Damit gibt es auch keine Test-Ordner oder Probe-Dateien mehr: Was Scanbird
 * in die Cloud schreibt, sind ausschliesslich echte Scans. Die eigene PDF
 * erkennt die Wiederherstellung am Scanbird-Stempel im Datei-Inneren
 * (/Producer), unabhaengig vom Dateinamen — in der Cloud umbenannte Dateien
 * bleiben auffindbar. Ansehen und Loeschen in der Cloud macht die zugehoerige
 * Cloud-App (openCloudApp), denn Loeschen und Oeffnen ueber SAF unterstuetzen
 * manche Anbieter (kDrive) nicht. Eine leere Ordnerabfrage kann dort auch
 * gelogen sein, deshalb wiederholt listFolder leere Ergebnisse kurz — ganz
 * ohne Schreib-Proben, die als Muell liegen bleiben koennten.
 *
 * Fehlverhalten ist bewusst STILL: Ist das Ziel nicht erreichbar (Cloud
 * offline, App deinstalliert), geschieht nichts — beim naechsten Scan oder
 * App-Start zieht der Rueckstand automatisch nach. Selbstheilend statt laut.
 */
object BackupStore {

    /** Eine Datei im Zielordner. */
    data class BackupFile(
        val documentId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val lastModified: Long
    )

    /** Namens-Präfix der Cloud-Kopien — Kosmetik fuer Menschen im Zielordner.
     *  Das Wiedererkennen laeuft ueber den Stempel, nicht ueber den Namen. */
    const val CLOUD_PREFIX = "Scanbird — "

    /** Ordner-Picker: URI mitsamt dauerhaft haltbarer Berechtigung anfordern. */
    fun pickFolderIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }

    fun target(context: Context): Uri? = SettingsStore(context).backupTreeUri

    /** Lesbares Ziel-Etikett fuer die Einstellungen: der gewaehlte Ordner. */
    fun targetLabel(context: Context): String? {
        val uri = target(context) ?: return null
        return folderName(context, uri)
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

    /**
     * Echter Ordnername laut Anbieter (0.5.13): eine einzelne Dokument-Abfrage
     * fragt COLUMN_DISPLAY_NAME direkt beim Provider. Liefert null, wenn der
     * Anbieter die Abfrage nicht unterstuetzt; dann greift der ID-Fallback.
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

    /** Dokument-ID des Ziels: der gewaehlte Ordner selbst (0.5.20). */
    fun targetFolderId(context: Context): String? =
        target(context)?.let { DocumentsContract.getTreeDocumentId(it) }

    /**
     * Ziel-Check fuer die Einstellungen: Laesst sich der Zielordner abfragen?
     * Nur harte Fehler (offline, Berechtigung weg, Anbieter fehlt) werden
     * gemeldet — ein lesbares Ziel ist ok, auch wenn es leer ist: Leere kann
     * echt sein, und gelogenes Leere ist harmlos, seit die App nichts mehr
     * anlegt und das Wiedererkennen im Datei-Inneren laeuft.
     */
    suspend fun checkTarget(context: Context): String? = withContext(Dispatchers.IO) {
        val tree = target(context) ?: return@withContext null
        val folderId = targetFolderId(context) ?: return@withContext null
        val files = listFolder(context, tree, folderId, attempts = 3)
        if (files == null) {
            "Zielordner-Abfrage fehlgeschlagen — Internetverbindung oder Berechtigung prüfen."
        } else {
            null
        }
    }

    /**
     * Rueckstand sichern: alle Scans ohne Backup-Kopie in den Wunschordner
     * ziehen, unter dem Scanbird-Namen mit dem aktuellen Titel. Kein Ziel
     * oder lesbarer Zielordner → still ausfallen; der naechste Scan oder
     * Start versucht es wieder. Liefert true, wenn der ganze Rueckstand
     * gesichert ist.
     */
    suspend fun ensureBackedUp(context: Context, dao: ScanDao): Boolean =
        withContext(Dispatchers.IO) {
            val tree = target(context) ?: return@withContext false
            val folderId = targetFolderId(context) ?: return@withContext false
            try {
                val existing = listFolder(context, tree, folderId, attempts = 2)
                    ?.mapTo(HashSet()) { it.name } ?: return@withContext false
                for (scan in dao.findNotBackedUp()) {
                    val cloudName = cloudFileName(scan.title)
                    if (cloudName in existing) {
                        dao.markBackedUp(scan.id)
                        continue
                    }
                    val pfd = ScanStore.openRead(context, ScanStore.locate(context, scan.pdfFileName))
                    if (pfd == null) continue
                    val created = DocumentsContract.createDocument(
                        context.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, folderId),
                        "application/pdf", cloudName
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
     * Wiederherstellen (0.5.20): Jede PDF im Wunschordner, die den
     * Scanbird-Stempel traegt und der App noch nicht bekannt ist, in den
     * Arbeitsordner ziehen und als Verlaufs-Eintrag anlegen — neues Geraet,
     * verbundene Cloud, gleiche Liste. Der Stempel entscheidet, nicht der
     * Name: In der Cloud umbenannte Dateien bleiben auffindbar, fremde PDFs
     * bleiben draussen. Identitaet ist der Zeistempel (SB<ms>) aus dem
     * Stempel — schon bekannte Scans werden uebersprungen.
     * Liefert die Zahl der zurueckgeholten Scans; null bei Fehler.
     */
    suspend fun restoreMissing(context: Context, dao: ScanDao): Int? =
        withContext(Dispatchers.IO) {
            val tree = target(context) ?: return@withContext null
            val folderId = targetFolderId(context) ?: return@withContext null
            try {
                val files = listFolder(context, tree, folderId, attempts = 5)
                    ?: return@withContext null
                var restored = 0
                for (file in files) {
                    if (!file.name.endsWith(".pdf", ignoreCase = true)) continue
                    val marker = readMarker(context, tree, file.documentId) ?: continue
                    if (dao.countByCreatedAt(marker.stampMs) > 0) continue
                    if (restoreOne(context, tree, file, marker, dao)) restored++
                }
                restored
            } catch (e: Exception) {
                null
            }
        }

    /** Eine erkannte Cloud-PDF in den Arbeitsordner ziehen und eintragen. */
    private suspend fun restoreOne(
        context: Context,
        tree: Uri,
        file: BackupFile,
        marker: PdfMarker.Marker,
        dao: ScanDao
    ): Boolean {
        // Titel-Quelle: Der Cloud-Name traegt den aktuellen Titel (Uploads
        // laufen unter ihm); fehlt das Muster — etwa nach Umbenennen in der
        // Cloud — greift der Titel aus dem Stempel, letzter Fallback der Name.
        val title = if (file.name.startsWith(CLOUD_PREFIX)) {
            titleFromFileName(file.name.removePrefix(CLOUD_PREFIX))
        } else {
            marker.title ?: titleFromFileName(file.name)
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY)
            .format(Date(marker.stampMs))
        val fileName = "Scan_" + stamp + "__" + fileTitle(title) + ".pdf"
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId)
        val targetPdf = try {
            ScanStore.createPdf(context, fileName)
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
                title = title,
                createdAt = marker.stampMs,
                pdfFileName = fileName,
                backedUp = true
            )
        )
        return true
    }

    /**
     * Cloud-App des Ziels oeffnen (0.5.20): Ansehen und Loeschen laufen dort
     * nativ — Loeschen und Oeffnen ueber SAF sind bei manchen Anbietern
     * kaputt, in der Cloud-App funktioniert beides. Aus der Ziel-URI folgt
     * der SAF-Provider, aus ihm das App-Paket. Fallback: die System-
     * Ordneransicht, die zuverlaessig anzeigt. Liefert false, wenn beides
     * misslingt.
     */
    fun openCloudApp(context: Context): Boolean {
        val tree = target(context) ?: return false
        try {
            val pm = context.packageManager
            val provider = tree.authority?.let { pm.resolveContentProvider(it, 0) }
            if (provider != null) {
                val launch = pm.getLaunchIntentForPackage(provider.packageName)
                if (launch != null) {
                    context.startActivity(launch)
                    return true
                }
            }
        } catch (e: Exception) {
        }
        return try {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
            context.startActivity(view)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** App-Label des Cloud-Anbieters (fuer den Knopf-Text). */
    fun cloudAppLabel(context: Context): String? {
        val tree = target(context) ?: return null
        return try {
            val pm = context.packageManager
            val provider = tree.authority?.let { pm.resolveContentProvider(it, 0) }
                ?: return null
            pm.getApplicationInfo(provider.packageName, 0).loadLabel(pm).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Cloud-Dateiname eines Scans: Scanbird-Rahmen um den feilen Titel. */
    fun cloudFileName(title: String): String = CLOUD_PREFIX + fileTitle(title) + ".pdf"

    /**
     * Anzeige-Titel aus einem Dateinamen: Zeitstempel-Präfix abtrennen,
     * Unterstriche zu Leerzeichen machen — so sieht die Liste aus wie der
     * Verlauf.
     */
    fun titleFromFileName(name: String): String {
        val base = name.removeSuffix(".pdf")
        val tail = if (base.contains("__")) base.substringAfter("__") else base
        val title = tail.replace('_', ' ').trim()
        return if (title.isBlank()) name else title
    }

    /**
     * Dateinamen-Feile: Umlaute deutsch transliterieren, Sonderzeichen raus,
     * Ergebnis reines ASCII. Umlaute im Dateinamen brachten sonst Uploads zum
     * Straucheln — im Verlauf steht der Titel trotzdem mit echten Umlauten.
     */
    fun fileTitle(raw: String): String =
        raw.replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("ß", "ss")
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
            .replace(' ', '_')
            .map { if (it.code in 32..126) it else '_' }
            .joinToString("")
            .let { cutAtWordBoundary(it, 40) }

    /** Kappung an der Wortgrenze: nie mitten im Wort abschneiden. */
    private fun cutAtWordBoundary(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.take(max)
        val atWord = cut.lastIndexOf('_')
        return if (atWord >= max / 2) cut.substring(0, atWord) else cut.trimEnd('_')
    }

    /** Scanbird-Stempel aus einer Datei im Ziel lesen (null: nicht unserer). */
    private fun readMarker(context: Context, tree: Uri, documentId: String): PdfMarker.Marker? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        return try {
            context.contentResolver.openInputStream(docUri)?.use { input ->
                PdfMarker.readFrom(input)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ordnerinhalt mit Wiederholungen: Manche Anbieter (kDrive) liefern
     * gelegentlich still eine leere Liste — dann kurz warten und nochmal,
     * erst das letzte (leere) Ergebnis gilt. Liefert null, wenn jeder
     * Versuch eine Ausnahme war (hartes Versagen, z. B. offline).
     */
    private suspend fun listFolder(
        context: Context,
        tree: Uri,
        folderId: String,
        attempts: Int
    ): List<BackupFile>? {
        for (attempt in 0 until attempts) {
            if (attempt > 0) delay(1200)
            try {
                val files = queryFiles(context, tree, folderId)
                if (files.isNotEmpty() || attempt == attempts - 1) return files
            } catch (e: Exception) {
            }
        }
        return null
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
