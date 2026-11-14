package de.ph1l1pp5.scanbird.pdf

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Stempel im Inneren der PDF (0.5.20): /Producer (Scanbird) markiert die
 * Datei als Scanbird-Scan — unabhaengig vom Dateinamen, der in der Cloud
 * jederzeit umbenannt werden kann. /Keywords (SB<Zeitstempel>) ist die
 * Identitaet des Scans (Millisekunden seines ersten Speicherns),
 * /CreationDate sein originales Datum, /Title der Titel beim Speichern.
 * PdfWriter schreibt das Info-Objekt physisch an den Dateianfang, deshalb
 * reicht zum Wiedererkennen ein kleiner Leseblock statt der ganzen
 * Megabyte-Datei.
 */
object PdfMarker {

    data class Marker(val title: String?, val stampMs: Long)

    /** Fester Wiedererkennungs-Stempel in /Producer. */
    const val PRODUCER = "Scanbird"

    /** Titel als PDF-Hex-String: UTF-16BE mit BOM — Umlaute sicher, ohne Escapes. */
    fun titleHex(title: String): String {
        val bytes = title.toByteArray(Charsets.UTF_16BE)
        val hex = StringBuilder("FEFF")
        for (b in bytes) hex.append(String.format("%02X", b))
        return hex.toString()
    }

    /** Stempel aus den ersten Bytes einer PDF lesen. Null, wenn die Datei
     *  keinen Scanbird-Stempel traegt — dann gehoert sie uns nicht. */
    fun readFrom(input: InputStream): Marker? {
        val head = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        input.use { stream ->
            while (total < 64 * 1024) {
                val read = stream.read(buffer)
                if (read < 0) break
                head.write(buffer, 0, read)
                total += read
            }
        }
        val text = head.toString("ISO-8859-1")
        if (!text.contains("/Producer ($PRODUCER)")) return null
        val stamp = Regex("\\(SB(\\d+)\\)").find(text)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val title = Regex("/Title <([0-9A-Fa-f]*)>").find(text)
            ?.groupValues?.get(1)
            ?.let { hex -> decodeTitle(hex) }
        return Marker(title, stamp)
    }

    /** Hex-String zurueck in Text — BOM beruecksichtigen, Fehler still als null. */
    private fun decodeTitle(hex: String): String? {
        if (hex.length < 4 || hex.length % 2 != 0) return null
        return try {
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = ((Character.digit(hex[i * 2], 16) shl 4) or
                    Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
            val offset = if (bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
            ) 2 else 0
            String(bytes, offset, bytes.size - offset, Charsets.UTF_16BE)
        } catch (e: Exception) {
            null
        }
    }
}
