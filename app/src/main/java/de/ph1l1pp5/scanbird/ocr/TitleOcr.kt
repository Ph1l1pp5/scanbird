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
 * sie komplett offline mit Tesseract (deutsches Schnellmodell, beim Bau in die
 * APK gepackt). Titelkandidat ist die größte Textzeile im oberen Blattdrittel —
 * auf Praxisblättern ist das die fettgedruckte Überschrift. Läuft asynchron
 * nach dem Speichern; die App wartet nie auf die OCR.
 */
object TitleOcr {

    @Volatile
    private var engine: TessBaseAPI? = null

    private data class Line(val text: String, val top: Int, val height: Int)

    /** Liest die erste Seite und schlägt einen Titel vor — null, wenn nichts Brauchbares lesbar ist. */
    fun extractTitle(context: Context, fileName: String): String? = synchronized(this) {
        val api = obtainEngine(context) ?: return null
        val fd = ScanStore.openRead(context, ScanStore.locate(context, fileName)) ?: return null
        val page = renderFirstPage(fd) ?: return null
        try {
            val lines = readLines(api, page)
            val topArea = page.height * 0.35f
            val title = lines
                .filter { it.top <= topArea && it.text.length >= 3 && it.text.count { c -> c.isLetter() } >= 3 }
                .maxByOrNull { it.height }
                ?.text
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
            if (!model.exists() || model.length() == 0L) {
                tessdataDir.mkdirs()
                context.assets.open("tessdata/deu.traineddata").use { input ->
                    model.outputStream().use { output -> input.copyTo(output) }
                }
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

    /** Rendert Seite 1 des PDFs auf rund 1000 px Breite — fürs Titel-Lesen reicht das locker. */
    private fun renderFirstPage(fd: ParcelFileDescriptor): Bitmap? {
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(fd)
            if (renderer.pageCount < 1) return null
            var page: PdfRenderer.Page? = null
            try {
                page = renderer.openPage(0)
                val scale = 1000f / page.width
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
                if (text.isNotEmpty() && rect.height() > 0) {
                    lines.add(Line(text, rect.top, rect.height()))
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

    /** Putzt einen gelesenen Titel: Leerraum zusammen, Schmuckzeichen weg, Mindestlänge. */
    private fun clean(raw: String): String? {
        val t = raw.replace(Regex("[\\s]+"), " ").trim(' ', '-', '_', '|', ':', '.')
        return if (t.length >= 3 && t.any { c -> c.isLetter() }) t else null
    }
}
