package de.ph1l1pp5.scanbird.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Scan-Politur (M2.2): macht aus dem Knips den Flachbett-Look. Vier Waschgänge:
 * (1) Beleuchtung plätten — das Bild wird durch sein großes Box-Gemittel geteilt,
 *     das hebt Schatten, Überbelichtung und Knitterschatten auf gleichmäßiges
 *     Papierweiß. (2) Sauvola-Binarisierung für knackscharfen Scanner-Kontrast.
 *     (3) Kippung bis ±8 Grad über Profil-Bewertung geraderücken. (4) Dunkle Tischreste
 *     an den Rändern abschneiden, damit das Blatt den Rahmen füllt.
 * Keine externe Bibliothek — nur Summentabellen und Matrizen, alles offline.
 */
object ScanPolish {

    /** Voller Waschgang; liefert immer ein neues Bitmap in A4-Maß. */
    fun polish(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Graustufen-Helligkeit (Zeilen-Deko wie Farbranden fällt hier schon weg).
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        val flat = flatten(gray, w, h)
        val binary = sauvola(flat, w, h)
        val angle = estimateSkew(binary, w, h)

        var bmp = binaryToBitmap(binary, w, h)
        bmp = reframe(bmp, angle)
        bmp = trimEdgesAndFill(bmp)
        return bmp
    }

    /** Beleuchtung plätten: großes Box-Gemittel als Hintergrundschätzung, Teilen normalisiert. */
    private fun flatten(gray: IntArray, w: Int, h: Int): IntArray {
        val win = max(31, w / 8)
        val box = boxAverages(gray, w, h, win)
        val out = IntArray(gray.size)
        for (i in gray.indices) {
            val bg = box[i]
            val v = if (bg < 8) gray[i] else (gray[i] * 240) / bg
            out[i] = if (v > 255) 255 else v
        }
        return out
    }

    /** Box-Gemittel über eine summierte Flächentabelle; Fensterränder klemmen an die Bildkante. */
    private fun boxAverages(src: IntArray, w: Int, h: Int, win: Int): IntArray {
        val iw = w + 1
        val sum = LongArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += src[y * w + x]
                sum[(y + 1) * iw + (x + 1)] = sum[y * iw + (x + 1)] + rowSum
            }
        }
        val out = IntArray(src.size)
        val half = win / 2
        for (y in 0 until h) {
            val y0 = max(0, y - half)
            val y1 = min(h - 1, y + half)
            for (x in 0 until w) {
                val x0 = max(0, x - half)
                val x1 = min(w - 1, x + half)
                val s = sum[(y1 + 1) * iw + (x1 + 1)] - sum[y0 * iw + (x1 + 1)] -
                    sum[(y1 + 1) * iw + x0] + sum[y0 * iw + x0]
                out[y * w + x] = (s / ((y1 - y0 + 1) * (x1 - x0 + 1))).toInt()
            }
        }
        return out
    }

    /**
     * Sauvola-Binarisierung: lokale Schwelle aus Fenster-Mittelwert und -Streuung.
     * Robust gegen Restschatten und feine Grautöne — der Scanner-Kontrast schlechthin.
     */
    private fun sauvola(src: IntArray, w: Int, h: Int): IntArray {
        val win = max(15, w / 32)
        val iw = w + 1
        val sum = LongArray(iw * (h + 1))
        val sq = LongArray(iw * (h + 1))
        for (y in 0 until h) {
            var rs = 0L
            var rsq = 0L
            for (x in 0 until w) {
                val v = src[y * w + x].toLong()
                rs += v
                rsq += v * v
                sum[(y + 1) * iw + (x + 1)] = sum[y * iw + (x + 1)] + rs
                sq[(y + 1) * iw + (x + 1)] = sq[y * iw + (x + 1)] + rsq
            }
        }
        val out = IntArray(src.size)
        val half = win / 2
        val k = 0.2f
        val r = 128f
        for (y in 0 until h) {
            val y0 = max(0, y - half)
            val y1 = min(h - 1, y + half)
            val rows = (y1 - y0 + 1).toLong()
            for (x in 0 until w) {
                val x0 = max(0, x - half)
                val x1 = min(w - 1, x + half)
                val n = rows * (x1 - x0 + 1)
                val s = sum[(y1 + 1) * iw + (x1 + 1)] - sum[y0 * iw + (x1 + 1)] -
                    sum[(y1 + 1) * iw + x0] + sum[y0 * iw + x0]
                val s2 = sq[(y1 + 1) * iw + (x1 + 1)] - sq[y0 * iw + (x1 + 1)] -
                    sq[(y1 + 1) * iw + x0] + sq[y0 * iw + x0]
                val mean = s.toFloat() / n
                val variance = s2.toFloat() / n - mean * mean
                val sd = if (variance > 0f) sqrt(variance) else 0f
                val threshold = mean * (1f + k * (sd / r - 1f))
                out[y * w + x] = if (src[y * w + x] > threshold) 255 else 0
            }
        }
        return out
    }

    /**
     * Kippung schätzen (bis ±8 Grad): für jeden Kandidatenwinkel werden die schwarzen Pixel auf
     * gedrehte Zeilen projiziert — bei passendem Winkel rücken Textzeilen auf wenige
     * Projektionszeilen, das Profil wird messerscharf (Summe der Quadrate maximal).
     */
    private fun estimateSkew(binary: IntArray, w: Int, h: Int): Float {
        var n = 0
        for (i in binary.indices step 2) if (binary[i] == 0) n++
        if (n < 500) return 0f
        val xs = IntArray(n)
        val ys = IntArray(n)
        var k = 0
        for (i in binary.indices step 2) {
            if (binary[i] == 0) {
                xs[k] = i % w
                ys[k] = i / w
                k++
            }
        }
        // Bei textreichen Blättern die Stützpunkte dünnen — 150.000 reichen locker,
        // auch wenn 65 Winkelkandidaten durchprobiert werden.
        var count = n
        if (n > 150000) {
            val stride = (n + 149999) / 150000
            count = 0
            var i = 0
            while (i < n) {
                xs[count] = xs[i]
                ys[count] = ys[i]
                count++
                i += stride
            }
        }
        val cx = w / 2f
        var bestAngle = 0f
        var bestScore = -1L
        var score0 = 0L
        for (step in 0 until 65) {
            val deg = -8f + 0.25f * step
            val slope = tan(deg * PI.toFloat() / 180f)
            val hist = IntArray(h + 64)
            for (i in 0 until count) {
                val yPrime = (ys[i] - slope * (xs[i] - cx)).toInt() + 32
                if (yPrime >= 0 && yPrime < hist.size) hist[yPrime]++
            }
            var score = 0L
            for (v in hist) score += v.toLong() * v
            if (deg == 0f) score0 = score
            if (score > bestScore) {
                bestScore = score
                bestAngle = deg
            }
        }
        // Sicherheitsmargin: nur drehen, wenn der Winkel das Profil deutlich schärft —
        // Scheinwinkel bei Stempeln und Grafiken bleiben so unbeanstandet.
        return if (bestScore * 100 > score0 * 115) bestAngle else 0f
    }

    private fun binaryToBitmap(binary: IntArray, w: Int, h: Int): Bitmap {
        val colors = IntArray(binary.size)
        for (i in binary.indices) {
            val v = binary[i]
            colors[i] = -0x1000000 or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(colors, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Geraderücken: um den geschätzten Kippwinkel drehen, verlustarm auf A4-Ausschnitt ziehen. */
    private fun reframe(bmp: Bitmap, angleDeg: Float): Bitmap {
        if (abs(angleDeg) < 0.3f) return bmp
        val w = bmp.width
        val h = bmp.height
        val m = Matrix()
        m.postRotate(-angleDeg, w / 2f, h / 2f)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
        if (rotated === bmp) return bmp
        bmp.recycle()
        // Größtes achsenfestes Rechteck im ORIGINAL-Seitenverhältnis, das garantiert
        // INNERHALB des gedrehten Blatts liegt — analytisch gelöst statt blind vom
        // Rahmen geschnitten (u·cosθ + v·sinθ = w/2, u·sinθ + v·cosθ = h/2, u/v = w/h).
        // Keine schwarzen Eckkeile mehr, an den Ecken geht nur weißer Blattrand flöten.
        val a = w.toFloat() / h
        val rad = abs(angleDeg) * PI.toFloat() / 180f
        val cosR = cos(rad)
        val sinR = sin(rad)
        val s1 = w / (a * cosR + sinR)
        val s2 = h / (a * sinR + cosR)
        val s = min(s1, s2)
        val cropW = (a * s).toInt().coerceAtMost(rotated.width)
        val cropH = s.toInt().coerceAtMost(rotated.height)
        val x = (rotated.width - cropW) / 2
        val y = (rotated.height - cropH) / 2
        val cropped = Bitmap.createBitmap(rotated, x, y, cropW, cropH)
        rotated.recycle()
        // … und wieder auf A4-Maß zurückziehen.
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        if (cropped !== scaled) cropped.recycle()
        return scaled
    }

    /**
     * Randbeschnitt: dunkle Tischreste (mehr als die Hälfte der Zeile/Spalte schwarz)
     * von den Rändern abschneiden und das Blatt wieder auf volle A4-Fläche ziehen.
     * Maximal 8 Prozent pro Seite — ein echtes Dokument wird nie angetastet.
     */
    private fun trimEdgesAndFill(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val rowDark = FloatArray(h)
        val colDark = FloatArray(w)
        for (y in 0 until h) {
            var d = 0
            for (x in 0 until w) if (((px[y * w + x] shr 16) and 0xFF) < 128) d++
            rowDark[y] = d.toFloat() / w
        }
        for (x in 0 until w) {
            var d = 0
            for (y in 0 until h) if (((px[y * w + x] shr 16) and 0xFF) < 128) d++
            colDark[x] = d.toFloat() / h
        }
        val capY = h * 8 / 100
        val capX = w * 8 / 100
        var top = 0
        while (top < capY && rowDark[top] > 0.5f) top++
        var bottom = 0
        while (bottom < capY && rowDark[h - 1 - bottom] > 0.5f) bottom++
        var left = 0
        while (left < capX && colDark[left] > 0.5f) left++
        var right = 0
        while (right < capX && colDark[w - 1 - right] > 0.5f) right++
        if (top + bottom + left + right == 0) return bmp
        val cw = (w - left - right).coerceAtLeast(w / 4)
        val ch = (h - top - bottom).coerceAtLeast(h / 4)
        val cropped = Bitmap.createBitmap(bmp, left, top, cw, ch)
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        if (cropped !== bmp) cropped.recycle()
        if (scaled !== cropped) bmp.recycle()
        return scaled
    }
}
