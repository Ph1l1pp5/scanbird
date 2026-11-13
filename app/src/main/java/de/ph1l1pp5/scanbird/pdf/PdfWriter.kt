package de.ph1l1pp5.scanbird.pdf

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Minimal-PDF mit JPEG-Einbettung (DCTDecode): A4-Seiten bei 150 dpi,
 * das Foto je Seite als JPEG komprimiert eingebettet statt als Rohpixel.
 *
 * Androids PdfDocument bettet Bitmaps unkomprimiert ein — das blaehte jede
 * Scan-Seite auf mehrere Megabyte auf (lahme Uploads, fette Mail-Anhaenge).
 * JPEG-Einbettung bringt dieselbe Seite auf ein paar Hundert Kilobyte.
 * Handgebaut ohne Abhaengigkeiten: Catalog, Pages mit Kids/Count, je Seite
 * Page + JPEG-XObject + Content-Stream, saubere xref — Baseline-PDF, das
 * jeder Viewer und PdfRenderer oeffnet. Seit 0.4.0 mehrseitenfaehig.
 */
object PdfWriter {

    // A4 bei 150 dpi in Pixeln; PDF-Punkte: 1 px = 72/150 pt
    private const val A4_WIDTH_PX = 1240
    private const val A4_HEIGHT_PX = 1754
    private const val PX_TO_PT = 72f / 150f

    /** Eine fertige PDF-Seite: JPEG-Bytes plus Pixelmasse. */
    data class Page(val width: Int, val height: Int, val jpeg: ByteArray)

    /** Packt ein poliertes Bitmap als JPEG-Seite — das Bitmap darf danach recycelt werden. */
    fun pageOf(bitmap: Bitmap): Page {
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        return Page(bitmap.width, bitmap.height, jpeg)
    }

    /** Schreibt alle Seiten als ein gemeinsames PDF ins Ziel. Schliesst den Stream nicht. */
    fun writeTo(pages: List<Page>, target: OutputStream) {
        if (pages.isEmpty()) {
            throw IllegalArgumentException("PDF ohne Seiten")
        }
        val n = pages.size
        val pageWidth = A4_WIDTH_PX * PX_TO_PT
        val pageHeight = A4_HEIGHT_PX * PX_TO_PT

        val pdf = ByteArrayOutputStream()
        val offsets = IntArray(2 + n * 3)
        fun bytes(s: String) {
            pdf.write(s.toByteArray(Charsets.US_ASCII))
        }
        fun startObj(id: Int) {
            offsets[id - 1] = pdf.size()
            bytes(id.toString() + " 0 obj\n")
        }

        bytes("%PDF-1.4\n")
        startObj(1)
        bytes("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        startObj(2)
        val kids = (0 until n).joinToString(" ") { (3 + it * 3).toString() + " 0 R" }
        bytes("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>\nendobj\n")

        for (index in 0 until n) {
            val page = pages[index]
            val pageId = 3 + index * 3
            val imageId = pageId + 1
            val contentId = pageId + 2

            // Bitmap proportional mittig auf die A4-Flaeche legen
            val scale = minOf(
                A4_WIDTH_PX.toFloat() / page.width,
                A4_HEIGHT_PX.toFloat() / page.height
            )
            val drawWidth = page.width * scale * PX_TO_PT
            val drawHeight = page.height * scale * PX_TO_PT
            val offsetX = (pageWidth - drawWidth) / 2f
            val offsetY = (pageHeight - drawHeight) / 2f
            val content = "q " + fmt(drawWidth) + " 0 0 " + fmt(drawHeight) + " " +
                fmt(offsetX) + " " + fmt(offsetY) + " cm /Im0 Do Q"
            val contentBytes = content.toByteArray(Charsets.US_ASCII)

            startObj(pageId)
            bytes(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + fmt(pageWidth) + " " + fmt(pageHeight) +
                    "] /Resources << /XObject << /Im0 " + imageId + " 0 R >> >> /Contents " +
                    contentId + " 0 R >>\nendobj\n"
            )
            startObj(imageId)
            bytes(
                "<< /Type /XObject /Subtype /Image /Width " + page.width +
                    " /Height " + page.height +
                    " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " +
                    page.jpeg.size + " >>\nstream\n"
            )
            pdf.write(page.jpeg)
            bytes("\nendstream\nendobj\n")
            startObj(contentId)
            bytes("<< /Length " + contentBytes.size + " >>\nstream\n")
            pdf.write(contentBytes)
            bytes("\nendstream\nendobj\n")
        }

        val xrefPos = pdf.size()
        bytes("xref\n0 " + (3 + n * 3) + "\n")
        bytes("0000000000 65535 f \n")
        for (i in 1..(2 + n * 3)) {
            bytes(String.format(Locale.US, "%010d 00000 n \n", offsets[i - 1]))
        }
        bytes("trailer\n<< /Size " + (3 + n * 3) + " /Root 1 0 R >>\nstartxref\n")
        bytes(xrefPos.toString())
        bytes("\n%EOF\n")

        target.write(pdf.toByteArray())
        target.flush()
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)
}
