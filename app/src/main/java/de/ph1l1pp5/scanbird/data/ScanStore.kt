package de.ph1l1pp5.scanbird.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/**
 * Heimat der fertigen PDFs: ab Android 10 (API 29) der oeffentliche Ordner
 * Download/Scanbird ueber den MediaStore — ohne Zusatzberechtigung, dafuer
 * sichtbar fuer Dateimanager, USB-Transfer und Cloud-Backups. Darunter und
 * fuer Alteintrage bleibt es der interne App-Speicher (filesDir/scans),
 * der bislang einzige Ablageort.
 */
object ScanStore {

    private const val RELATIVE_DIR = "Download/Scanbird"
    private const val FILE_PROVIDER = "de.ph1l1pp5.scanbird.fileprovider"

    /** Eine angelegte, noch offene PDF-Auslieferung. */
    class PdfTarget internal constructor(
        private val context: Context,
        val stream: OutputStream,
        private val uri: Uri?,
        private val file: File?
    ) {
        /** Nach erfolgreichem Schreiben: Stream schliessen, Datei freigeben. */
        fun finish() {
            try { stream.close() } catch (e: Exception) { }
            if (uri != null) {
                val done = ContentValues()
                done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, done, null, null)
            }
        }

        /** Nach Fehlschlag: Datei beseitigen, keine halben PDFs liegen lassen. */
        fun abandon() {
            try { stream.close() } catch (e: Exception) { }
            if (uri != null) {
                try { context.contentResolver.delete(uri, null, null) } catch (e: Exception) { }
            } else if (file != null) {
                try { file.delete() } catch (e: Exception) { }
            }
        }
    }

    /** Ort eines Scans: oeffentlich im Download-Ordner oder intern. */
    sealed class Location {
        data class Public(val uri: Uri) : Location()
        data class Internal(val file: File) : Location()
    }

    /** Interne Ablage — aeltere Scans und Geraete unter Android 10. */
    fun internalDir(context: Context): File = File(context.filesDir, "scans")

    /** Legt eine neue PDF-Datei an und liefert den Stream zum Schreiben. */
    fun createPdf(context: Context, fileName: String): PdfTarget {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw IllegalStateException("Download-Ordner nicht beschreibbar")
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("PDF-Stream laesst sich nicht oeffnen")
            return PdfTarget(context, stream, uri, null)
        }
        val dir = internalDir(context)
        dir.mkdirs()
        val file = File(dir, fileName)
        return PdfTarget(context, file.outputStream(), null, file)
    }

    /** Findet die Datei zu einem Verlaufs-Eintrag (intern hat Vorrang, dann MediaStore). */
    fun locate(context: Context, fileName: String): Location {
        val internal = File(internalDir(context), fileName)
        if (internal.exists()) return Location.Internal(internal)
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND " +
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                arrayOf(fileName, RELATIVE_DIR + "%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0).toString()
                    )
                } else {
                    null
                }
            }
            if (uri != null) return Location.Public(uri)
        }
        return Location.Internal(internal)
    }

    /** Lesender Zugriff fuer die Vorschau (PdfRenderer). */
    fun openRead(context: Context, location: Location): ParcelFileDescriptor? =
        when (location) {
            is Location.Public -> context.contentResolver.openFileDescriptor(location.uri, "r")
            is Location.Internal -> ParcelFileDescriptor.open(
                location.file, ParcelFileDescriptor.MODE_READ_ONLY
            )
        }

    /** Share-Uri fuer die Mail: MediaStore-Uri direkt, interne ueber FileProvider. */
    fun shareUri(context: Context, location: Location): Uri = when (location) {
        is Location.Public -> location.uri
        is Location.Internal -> FileProvider.getUriForFile(context, FILE_PROVIDER, location.file)
    }

    /** Loescht die Datei; true bei Erfolg. */
    fun delete(context: Context, location: Location): Boolean = when (location) {
        is Location.Public -> context.contentResolver.delete(location.uri, null, null) > 0
        is Location.Internal -> location.file.delete()
    }

    /**
     * Benennt die Datei um. Liefert den tatsaechlichen neuen Dateinamen — der
     * MediaStore macht Namen eindeutig (Suffix "(1)"), das uebernehmen wir mit.
     * Bei Fehlschlag: null, der Verlauf behaelt den alten Namen.
     */
    fun rename(context: Context, location: Location, newFileName: String): String? =
        when (location) {
            is Location.Public -> {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName)
                if (context.contentResolver.update(location.uri, values, null, null) > 0) {
                    context.contentResolver.query(
                        location.uri,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                        null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } else {
                    null
                }
            }
            is Location.Internal -> {
                val target = File(location.file.parentFile, newFileName)
                if (target.exists()) {
                    null
                } else if (location.file.renameTo(target)) {
                    newFileName
                } else {
                    null
                }
            }
        }

    /** Intent, der den Scanbird-Ordner im Dateimanager oeffnet (ab Android 10). */
    fun folderIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 29) return null
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Download/Scanbird"
        )
        return Intent(Intent.ACTION_VIEW).apply { data = docUri }
    }
}
