package de.ph1l1pp5.scanbird.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import java.io.File

object PdfWriter {

    // A4 bei 150 dpi: ausreichend für Textblätter, schonend für Speicher und Tempo
    private const val A4_WIDTH = 1240
    private const val A4_HEIGHT = 1754

    fun write(bitmap: Bitmap, target: File) {
        val document = PdfDocument()
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, 1).create()
        )
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        // Bitmap proportional mittig auf die A4-Fläche legen
        val scale = minOf(
            A4_WIDTH.toFloat() / bitmap.width,
            A4_HEIGHT.toFloat() / bitmap.height
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (A4_WIDTH - width) / 2f
        val top = (A4_HEIGHT - height) / 2f
        canvas.drawBitmap(bitmap, left, top, null)

        document.finishPage(page)
        target.outputStream().use { document.writeTo(it) }
        document.close()
    }
}
