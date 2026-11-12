package de.ph1l1pp5.scanbird.pdf

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Minimal-PDF mit JPEG-Einbettung (DCTDecode): eine A4-Seite bei 150 dpi,
 * das Foto als JPEG komprimiert eingebettet statt als Rohpixel.
 *
 * Androids PdfDocument bettet Bitmaps unkomprimiert ein — das blaehte jede
 * Scan-Seite auf mehrere Megabyte auf (lahme Uploads, fette Mail-Anhaenge).
 * JPEG-Einbettung bringt dieselbe Seite auf ein paar Hundert Kilobyte.
 * Handgebaut ohne Abhaengigkeiten: Catalog, Pages, Page, JPEG-XObject,
 * Content-Stream, xref — Baseline-PDF, das jeder Viewer und PdfRenderer oeffnet.
 */
object PdfWriter {

    // A4 bei 150 dpi in Pixeln; PDF-Punkte: 1 px = 72/150 pt
    private const val A4_WIDTH_PX = 1240
    private const val A4_HEIGHT_PX = 1754
    private const val PX_TO_PT = 72f / 150f

    /** Schreibt die A4-Seite mit dem Foto als JPEG ins Ziel. Schliesst den Stream nicht. */
    fun writeTo(bitmap: Bitmap, target: OutputStream) {
        // Foto als JPEG einbetten — Baseline-Encoding, wie DCTDecode es verlangt.
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }

        // Bitmap proportional mittig auf die A4-Flaeche legen
        val scale = minOf(
            A4_WIDTH_PX.toFloat() / bitmap.width,
            A4_HEIGHT_PX.toFloat() / bitmap.height
        )
        val drawWidth = bitmap.width * scale * PX_TO_PT
        val drawHeight = bitmap.height * scale * PX_TO_PT
        val offsetX = (A4_WIDTH_PX * PX_TO_PT - drawWidth) / 2f
        val offsetY = (A4_HEIGHT_PX * PX_TO_PT - drawHeight) / 2f
        val pageWidth = A4_WIDTH_PX * PX_TO_PT
        val pageHeight = A4_HEIGHT_PX * PX_TO_PT

        val content = "q " + fmt(drawWidth) + " 0 0 " + fmt(drawHeight) + " " +
            fmt(offsetX) + " " + fmt(offsetY) + " cm /Im0 Do Q"
        val contentBytes = content.toByteArray(Charsets.US_ASCII)

        val pdf = ByteArrayOutputStream()
        val offsets = IntArray(5)
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
        bytes("<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        startObj(3)
        bytes(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + fmt(pageWidth) + " " + fmt(pageHeight) +
                "] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>\nendobj\n"
        )
        startObj(4)
        bytes(
            "<< /Type /XObject /Subtype /Image /Width " + bitmap.width +
                " /Height " + bitmap.height +
                " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " +
                jpeg.size + " >>\nstream\n"
        )
        pdf.write(jpeg)
        bytes("\nendstream\nendobj\n")
        startObj(5)
        bytes("<< /Length " + contentBytes.size + " >>\nstream\n")
        pdf.write(contentBytes)
        bytes("\nendstream\nendobj\n")

        val xrefPos = pdf.size()
        bytes("xref\n0 6\n")
        bytes("0000000000 65535 f \n")
        for (i in 1..5) {
            bytes(String.format(Locale.US, "%010d 00000 n \n", offsets[i - 1]))
        }
        bytes("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n")
        bytes(xrefPos.toString())
        bytes("\n%EOF\n")

        target.write(pdf.toByteArray())
        target.flush()
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)
}
