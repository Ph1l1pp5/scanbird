package de.ph1l1pp5.scanbird.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import de.ph1l1pp5.scanbird.data.ScanStore
import java.io.File

/**
 * Lokaler Titel-Leser (M3): rendert die erste Seite des fertigen PDFs und liest
 * sie komplett offline mit Tesseract (deutsches Bestmodell, beim Bau in die
 * APK gepackt). Titelkandidat ist die größte Textzeile auf dem GANZEN Blatt —
 * oben gewichtet bevorzugt, denn Briefe tragen ihren Titel gern mittig; Datums-
 * und Kopfzeilen sind vom Kandidatentisch ausgeschlossen; Formular-, Adress-
 * und nur geratene Zeilen werden kräftig abgewertet. Läuft asynchron
 * nach dem Speichern; die App wartet nie auf die OCR.
 */
object TitleOcr {

    @Volatile
    private var engine: TessBaseAPI? = null

    private data class Line(val text: String, val top: Int, val height: Int, val left: Int, val width: Int, val conf: Float)

    /** Liest die erste Seite und schlägt einen Titel vor — null, wenn nichts Brauchbares lesbar ist. */
    fun extractTitle(context: Context, fileName: String): String? = synchronized(this) {
        val api = obtainEngine(context) ?: return null
        val fd = ScanStore.openRead(context, ScanStore.locate(context, fileName)) ?: return null
        val page = renderFirstPage(fd) ?: return null
        try {
            val lines = readLines(api, page)
            // Ganzes Blatt statt oberem Drittel: Briefe tragen ihren Titel gern mittig.
            // Wertung = Zeilenhöhe × sanftes Positionsgewicht × Titelqualität ×
            // Lese-Konfidenz — Adress-, Anrede- und Formularzeilen werden so stark
            // abgewertet, dass selbst eine übergroße Box ihnen den Titel nicht mehr
            // stehlen kann; Zeilen, die Tesseract nur geraten hat, ebenso.
            val pageH = page.height.toFloat()
            val pageW = page.width.toFloat()
            // PLZ-Flag je Zeile: Wer direkt ueber „22045 Hamburg“ sitzt, gehoert zum
            // Adressblock (Name, Strasse, Praxis) — das ist nie der Titel.
            val plzFlags = lines.map { isPlzLine(it.text) }
            var best: Line? = null
            var bestScore = 0f
            lines.forEachIndexed { i, line ->
                if (line.text.length >= 3 && line.text.count { c -> c.isLetter() } >= 3 &&
                    !isDateOrMetaLine(line.text)
                ) {
                    val score = line.height *
                        (1.2f - 0.4f * (line.top + line.height / 2f) / pageH) *
                        sideFactor(line, pageW) * titleQuality(line.text) *
                        confFactor(line.conf) * capsFactor(line.text) *
                        addressFactor(i, lines, plzFlags)
                    if (score > bestScore) {
                        bestScore = score
                        best = line
                    }
                }
            }
            val title = best?.text
            title?.let { clean(it) } ?: fallbackTopStrip(api, page)
        } finally {
            page.recycle()
        }
    }

    /** Einmalige Engine-Initialisierung; das Modell liegt als Asset in der APK und wird nach filesDir kopiert. */
    private fun obtainEngine(context: Context): TessBaseAPI? {
        engine?.let { return it }
        return try {
            val tessdataDir = File(context.filesDir, "tessdata")
            val model = File(tessdataDir, "deu.traineddata")
            // Modell frisch halten: Wechselt das gebündelte Modell (z. B. fast -> best),
            // erneuert sich die Kopie auf dem Gerät beim nächsten OCR-Lauf automatisch.
            val asset = try {
                context.assets.open("tessdata/deu.traineddata").use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            if (asset != null && model.length() != asset.size.toLong()) {
                tessdataDir.mkdirs()
                model.writeBytes(asset)
            }
            val api = TessBaseAPI()
            if (!api.init(context.filesDir.absolutePath + File.separator, "deu")) {
                null
            } else {
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                engine = api
                api
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Rendert Seite 1 des PDFs hochskaliert (2480 px ≈ 300 dpi) — bei 1240 px verkleben Fettbuchstaben zu Blob-Glyphen. */
    private fun renderFirstPage(fd: ParcelFileDescriptor): Bitmap? {
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(fd)
            if (renderer.pageCount < 1) return null
            var page: PdfRenderer.Page? = null
            try {
                page = renderer.openPage(0)
                val scale = 2480f / page.width
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            } finally {
                try { page?.close() } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            return null
        } finally {
            try { renderer?.close() } catch (e: Exception) { }
            try { fd.close() } catch (e: Exception) { }
        }
    }

    /** Liest alle Textzeilen mit ihren Boxen aus; bei Iterator-Zickzacker bleibt die Liste leer. */
    private fun readLines(api: TessBaseAPI, page: Bitmap): List<Line> {
        val lines = mutableListOf<Line>()
        try {
            api.setImage(page)
            api.getUTF8Text() // stößt die Erkennung an — erst danach darf der Iterator laufen
            val iter = api.resultIterator ?: return lines
            iter.begin()
            do {
                val rect = iter.getBoundingRect(PageIteratorLevel.RIL_TEXTLINE)
                val text = iter.getUTF8Text(PageIteratorLevel.RIL_TEXTLINE)?.trim() ?: ""
                val conf = iter.confidence(PageIteratorLevel.RIL_TEXTLINE)
                if (text.isNotEmpty() && rect.height() > 0) {
                    lines.add(Line(text, rect.top, rect.height(), rect.left, rect.width(), conf))
                }
            } while (iter.next(PageIteratorLevel.RIL_TEXTLINE))
            iter.delete()
        } catch (e: Exception) {
            // Iterator-Straße unpassierbar — der obere-Streifen-Fallback greift unten.
        } finally {
            api.clear()
        }
        return lines
    }

    /** Notgang: nur den oberen Streifen lesen, die erste brauchbare Zeile ist der Titel. */
    private fun fallbackTopStrip(api: TessBaseAPI, full: Bitmap): String? {
        val h = (full.height * 0.35f).toInt().coerceAtLeast(1)
        val strip = Bitmap.createBitmap(full, 0, 0, full.width, h)
        try {
            api.setImage(strip)
            val text = api.getUTF8Text() ?: return null
            return text.lineSequence().map { clean(it) }.firstOrNull { it != null }
        } catch (e: Exception) {
            return null
        } finally {
            api.clear()
            strip.recycle()
        }
    }

    /**
     * Briefkopf-Logos wohnen oben rechts: Zeilen, deren Mitte rechts der
     * Seitenmitte sitzt, sind Schmuck, kein Titel — kraeftig abgewertet.
     */
    private fun sideFactor(line: Line, pageW: Float): Float {
        val centerX = (line.left + line.width / 2f) / pageW
        return if (centerX > 0.62f) 0.3f else 1f
    }

    /**
     * GROSSBUCHSTABEN-Bonus: Praxis-Ueberschriften stehen gern komplett versal
     * („LIEFERSCHEIN Nr. …“, „RÜCKSENDECHEIN“). Erst ab 8 Buchstaben zahlt der
     * Bonus — kurze Logo-Fetzen wie „VERLAG“ oder „GmbH“ gehen leer aus.
     */
    private fun capsFactor(text: String): Float {
        val bonus = text.split(" ").any { w ->
            w.length >= 8 && w.all { it.isLetter() && it.isUpperCase() }
        }
        return if (bonus) 1.7f else 1f
    }

    /**
     * PLZ-Zeile: genau 5 Ziffern in Folge, dann Leerzeichen, dann Buchstabe
     * („22045 Hamburg“) — Aktenzeichen mit laengeren Ziffernwoertern zaehlen nicht.
     */
    private fun isPlzLine(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            if (text[i].isDigit()) {
                var j = i
                while (j < text.length && text[j].isDigit()) j++
                if (j - i == 5 && j < text.length - 1 && text[j] == ' ' &&
                    text[j + 1].isLetter() && (i == 0 || !text[i - 1].isDigit())
                ) return true
                i = j
            } else {
                i++
            }
        }
        return false
    }

    /**
     * Adressblock-Killer: Zeilen, ueber denen (innerhalb der naechsten zwei
     * Zeilen in Leserichtung) eine PLZ-Zeile liegt, sind Name oder Strasse.
     * Groesse vergleichen: Eine deutliche Ueberschrift ueber einer PLZ-Zeile
     * bleibt unbestraft, gleiche Fliesstext-Hoehe wird abgewertet.
     */
    private fun addressFactor(i: Int, lines: List<Line>, plzFlags: List<Boolean>): Float {
        for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
            if (plzFlags[j] && lines[i].height <= lines[j].height * 1.3f) return 0.3f
        }
        return 1f
    }

    /**
     * Lese-Konfidenz (0-100) als Wertungsfaktor: Sichere Zeilen zählen voll,
     * Geratene fast gar nicht — Mülltitel bekommen so keine Chance mehr.
     */
    private fun confFactor(conf: Float): Float = when {
        conf >= 75f -> 1f
        conf >= 55f -> 0.7f
        else -> 0.2f
    }

    /**
     * Titelqualität: Formular- und Adressdeko ist nie der Titel. Schrägstriche,
     * Klammern, Postfächer, Ziffernwürste, Anreden, Formular-Etiketten,
     * Tabellenköpfe und zusammengewürfelte Riesenzeilen werden abgewertet —
     * ein echter Titel übersteht das schadlos.
     */
    private fun titleQuality(text: String): Float {
        var q = 1f
        if (text.contains('/')) q *= 0.25f
        if (text.contains('(') || text.contains(')')) q *= 0.5f
        if (text.contains('@')) q *= 0.3f
        val t = text.trim()
        if (t.startsWith("Frau ") || t.startsWith("Herrn ") || t.startsWith("Herr ") || t.startsWith("Sehr ")) q *= 0.2f
        if (text.length > 55) q *= 0.6f
        // Geister-Zeilen: fast nur Kürzel und Einzelzeichen sind Tesseract-Rauschen.
        val words = t.split(" ").filter { it.isNotEmpty() }
        val letters = text.count { it.isLetter() }.toFloat()
        // Versal-Pardon: Eine Zeile mit GROSSER Dokument-Art („LIEFERSCHEIN
        // Nr. TLIE72612418“) ist ein gueltiger Titel MIT Aktenzeichen — die
        // Ziffernwurst- und Buchstabenmangel-Strafen greifen fuer sie nicht.
        val capsWord = words.any { w -> w.length >= 8 && w.all { it.isLetter() && it.isUpperCase() } }
        if (!capsWord) {
            if (text.count { it.isDigit() }.toFloat() / text.length >= 0.3f) q *= 0.4f
            if (text.length > 0 && letters / text.length < 0.7f) q *= 0.2f
        }
        if (words.count { it.length == 1 } >= 2) q *= 0.3f
        if (words.size >= 2 && words.all { it.length <= 3 }) q *= 0.3f
        // Aufzählungs-Punkte sind Inhalt, kein Titel.
        if (t.startsWith("-") || t.startsWith("–") || t.startsWith("*") || t.startsWith("•")) q *= 0.3f
        // Formular-Etiketten sind Felddeko, kein Titel (Anschrift, Kundennummer —
        // „anschr“ reicht als Treffer, weil Tesseract das Wort gern verbiegt).
        val lower = t.lowercase()
        if (lower.contains("anschr") || lower.contains("kundennummer")) q *= 0.3f
        // Tabellenkopf-Wörter: Pos., Art-Nr., Artikel, Bezeichnung, Menge —
        // wer zwei davon in EINER Zeile trägt, ist Spaltenbeschriftung.
        val tableHits = listOf("pos.", "art-nr", "artikel", "bezeichnung", "menge", "mge.").count { lower.contains(it) }
        if (tableHits >= 2) q *= 0.2f
        // Kamelhuftiger Markenname („IntegrationsHilfen“): Firmen- und Vereins-
        // Logos sind Briefkopf, kein Titel.
        var prevLower = false
        for (c in t) {
            if (prevLower && c.isUpperCase()) {
                q *= 0.3f
                break
            }
            prevLower = c.isLowerCase()
        }
        return q
    }

    /**
     * Datums- und Briefkopf-Deko ist nie der Titel: Zeilen mit „, den“ oder einem
     * Datum TT.MM.JJJJ fliegen raus — ohne Regex, nur Ziffern- und Punktsichtung.
     */
    private fun isDateOrMetaLine(text: String): Boolean {
        val t = text
        if (t.contains(", den ")) return true
        var i = 0
        while (i < t.length) {
            if (t[i].isDigit()) {
                var j = i
                while (j < t.length && t[j].isDigit()) j++
                if (j - i in 1..2 && j < t.length && t[j] == '.') {
                    var k = j + 1
                    while (k < t.length && t[k].isDigit()) k++
                    if (k - j - 1 in 1..2 && k < t.length && t[k] == '.') {
                        var m = k + 1
                        while (m < t.length && t[m].isDigit()) m++
                        if (m - k - 1 in 2..4) return true
                    }
                }
                i = j
            } else {
                i++
            }
        }
        return false
    }

    /** Putzt einen gelesenen Titel: Leerraum zusammen, Schmuckzeichen weg, Mindestlänge. */
    private fun clean(raw: String): String? {
        val t = raw.replace(Regex("[\\s]+"), " ").trim(' ', '-', '_', '|', ':', '.')
        return if (t.length >= 3 && t.any { c -> c.isLetter() }) t else null
    }
}
