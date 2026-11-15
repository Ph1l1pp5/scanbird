package de.ph1l1pp5.scanbird.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Findet das Blatt im Foto: die größte helle Fläche gegen den dunkleren Untergrund.
 * Die vier Ecken werden über Extremwerte von x+y und x-y bestimmt, das Ergebnis
 * mit Perspektivkorrektur (setPolyToPoly) auf A4 gebracht (200 dpi — seit 0.4.2,
 * vorher 150 dpi: mehr Auflösung fuer schaerferen Text und stabilerer Titel-OCR).
 * Seit 0.5.29 wird JEDE Ecke lokal auf voller Auflösung nachgemessen: Der
 * Thumbnail-Finder trifft sie nur auf gut zehn bis achtzig Pixel — nach dem
 * Warp blieb genau so viel Restperspektive im Blatt. Das Nachmessen trennt im
 * Fenster um die Grobecke Papier vom Untergrund (Otsu) und nimmt den Extremwert
 * der hellen Region, jetzt pixelgenau; zwei Vollpixel Polster halten den
 * Papierrand im Bild. Scheitert die Erkennung, wird das ganze Bild mittig auf
 * A4 beschnitten. Alles lokal, ohne ML Kit, ohne Netz.
 */
object DocumentFinder {

    private const val A4_WIDTH = 1654
    private const val A4_HEIGHT = 2339

    fun extractPage(source: Bitmap): Bitmap {
        return try {
            val quad = findQuad(source)
            if (quad != null) {
                warpToA4(source, refineQuad(source, quad))
            } else {
                centerCropA4(source)
            }
        } catch (e: Exception) {
            centerCropA4(source)
        }
    }

    private fun findQuad(source: Bitmap): FloatArray? {
        val scale = 256f / max(source.width, source.height)
        val w = max(1, (source.width * scale).toInt().coerceAtLeast(1))
        val h = max(1, (source.height * scale).toInt().coerceAtLeast(1))
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()

        val lum = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            lum[i] = ((p and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + ((p shr 16) and 0xFF) * 114) / 1000
        }

        var backgroundSum = 0L
        var backgroundCount = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) {
                    backgroundSum += lum[y * w + x]
                    backgroundCount++
                }
            }
        }
        val backgroundMean = (backgroundSum / max(1L, backgroundCount.toLong())).toInt()

        val histogram = IntArray(256)
        for (v in lum) histogram[v]++
        var acc = 0L
        var cnt = 0
        var paperMean = 0
        for (v in 255 downTo 0) {
            acc += v.toLong() * histogram[v]
            cnt += histogram[v]
            if (cnt >= (w * h) / 10) {
                paperMean = (acc / cnt).toInt()
                break
            }
        }

        if (paperMean - backgroundMean < 25) return null

        val threshold = (backgroundMean + paperMean) / 2

        val label = IntArray(w * h) { -1 }
        val queue = IntArray(w * h)
        var bestSize = 0
        var bestLabel = -1
        var currentLabel = 0
        for (start in pixels.indices) {
            if (lum[start] < threshold || label[start] != -1) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = currentLabel
            var size = 0
            while (head < tail) {
                val idx = queue[head++]
                size++
                val x = idx % w
                val y = idx / w
                if (x > 0 && lum[idx - 1] >= threshold && label[idx - 1] == -1) {
                    label[idx - 1] = currentLabel
                    queue[tail++] = idx - 1
                }
                if (x < w - 1 && lum[idx + 1] >= threshold && label[idx + 1] == -1) {
                    label[idx + 1] = currentLabel
                    queue[tail++] = idx + 1
                }
                if (y > 0 && lum[idx - w] >= threshold && label[idx - w] == -1) {
                    label[idx - w] = currentLabel
                    queue[tail++] = idx - w
                }
                if (y < h - 1 && lum[idx + w] >= threshold && label[idx + w] == -1) {
                    label[idx + w] = currentLabel
                    queue[tail++] = idx + w
                }
            }
            if (size > bestSize) {
                bestSize = size
                bestLabel = currentLabel
            }
            currentLabel++
        }

        if (bestLabel < 0 || bestSize < (w * h) / 6) return null

        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE
        var maxDiff = Int.MIN_VALUE
        var topLeft = 0
        var bottomRight = 0
        var topRight = 0
        var bottomLeft = 0
        for (idx in pixels.indices) {
            if (label[idx] != bestLabel) continue
            val x = idx % w
            val y = idx / w
            val sum = x + y
            val diff = x - y
            if (sum < minSum) { minSum = sum; topLeft = idx }
            if (sum > maxSum) { maxSum = sum; bottomRight = idx }
            if (diff > maxDiff) { maxDiff = diff; topRight = idx }
            if (diff < minDiff) { minDiff = diff; bottomLeft = idx }
        }

        val scaleX = source.width.toFloat() / w
        val scaleY = source.height.toFloat() / h
        val pad = 6f
        fun point(idx: Int): Pair<Float, Float> {
            val px = (idx % w) * scaleX
            val py = (idx / w) * scaleY
            return Pair(px, py)
        }
        val tl = point(topLeft)
        val tr = point(topRight)
        val br = point(bottomRight)
        val bl = point(bottomLeft)
        return floatArrayOf(
            tl.first - pad, tl.second - pad,
            tr.first + pad, tr.second - pad,
            br.first + pad, br.second + pad,
            bl.first - pad, bl.second + pad
        )
    }

    /**
     * Ecken nachmessen (0.5.29): Der Quad-Finder arbeitet auf einem 256-px-
     * Thumbnail, dazu kommt sein sechs-Pixel-Polster — seine Ecken sitzen
     * gut zehn bis achtzig Vollpixel daneben, und nach dem Warp bleibt genau
     * so viel Restperspektive im Blatt. Jede Ecke wird deshalb lokal auf
     * VOLLER Aufloesung nachgemessen (Fenster +/- 96 px um die Grobecke):
     * Otsu trennt dort Papier vom dunkleren Untergrund, die Ecke ist der
     * Extremwert (x+y bzw. x−y je Ecke) der hellen Region — pixelgenau.
     * Zwei Vollpixel Polster halten den Papierrand im Bild. Gelingt das
     * Nachmessen einer Ecke nicht, bleibt ihre Grobecke; wird der vermessene
     * Quad unplaublicb (zusammengefallene Kante, Verhaeltnissprung), gilt
     * der ganze Grobe Quad.
     */
    private fun refineQuad(source: Bitmap, coarse: FloatArray): FloatArray {
        val result = coarse.copyOf()
        for (i in 0 until 4) {
            val c = refineCorner(source, coarse[i * 2], coarse[i * 2 + 1], i)
            if (c != null) {
                result[i * 2] = if (i == 0 || i == 3) c.first - 2f else c.first + 2f
                result[i * 2 + 1] = if (i == 0 || i == 1) c.second - 2f else c.second + 2f
            }
        }
        return if (quadPlausible(result)) result else coarse
    }

    /** Eine Ecke im lokalen Fenster nachmessen — null, wenn dort keine klare Trennung liegt. */
    private fun refineCorner(source: Bitmap, cx: Float, cy: Float, corner: Int): Pair<Float, Float>? {
        val r = 96
        val x0 = (cx - r).toInt().coerceAtLeast(0)
        val x1 = (cx + r).toInt().coerceAtMost(source.width - 1)
        val y0 = (cy - r).toInt().coerceAtLeast(0)
        val y1 = (cy + r).toInt().coerceAtMost(source.height - 1)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w < 24 || h < 24) return null
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, x0, y0, w, h)
        val lum = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            lum[i] = ((p and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + ((p shr 16) and 0xFF) * 114) / 1000
        }
        val hist = IntArray(256)
        for (v in lum) hist[v]++
        val threshold = otsu(hist, lum.size)
        var bright = 0
        for (v in threshold until 256) bright += hist[v]
        // Ohne klare Trennung (fast alles hell oder fast alles dunkel) wird
        // nicht geraten — dann bleibt die Grobecke.
        if (bright < w * h / 10 || bright > w * h * 9 / 10) return null
        var bestIdx = -1
        var bestVal = 0
        for (idx in lum.indices) {
            if (lum[idx] < threshold) continue
            val x = idx % w
            val y = idx / w
            val v = when (corner) {
                0 -> -x - y        // oben-links: Minimum von x+y
                1 -> x - y         // oben-rechts: Maximum von x-y
                2 -> x + y         // unten-rechts: Maximum von x+y
                else -> y - x      // unten-links: Minimum von x-y
            }
            if (bestIdx < 0 || v > bestVal) {
                bestVal = v
                bestIdx = idx
            }
        }
        if (bestIdx < 0) return null
        return Pair((x0 + bestIdx % w).toFloat(), (y0 + bestIdx / w).toFloat())
    }

    /** Kleiner Otsu: Schwelle mit maximaler Zwischenklassen-Varianz im Histogramm. */
    private fun otsu(hist: IntArray, total: Int): Int {
        var sumAll = 0L
        for (v in hist.indices) sumAll += v.toLong() * hist[v]
        var sumB = 0L
        var wB = 0L
        var best = 0f
        var bestT = 128
        for (t in hist.indices) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = total.toLong() - wB
            if (wF == 0L) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toFloat() / wB
            val mF = (sumAll - sumB).toFloat() / wF
            val between = wB.toFloat() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                bestT = t
            }
        }
        return bestT
    }

    /** Quad-Plausibilitaet: keine zusammengefallene Kante, Seitenverhaeltnis um A4 herum. */
    private fun quadPlausible(quad: FloatArray): Boolean {
        fun len(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            return sqrt(dx * dx + dy * dy)
        }
        val top = len(quad[0], quad[1], quad[2], quad[3])
        val bottom = len(quad[4], quad[5], quad[6], quad[7])
        val left = len(quad[0], quad[1], quad[6], quad[7])
        val right = len(quad[2], quad[3], quad[4], quad[5])
        if (minOf(top, bottom, left, right) < 200f) return false
        val w = (top + bottom) / 2f
        val h = (left + right) / 2f
        val ratio = w / h
        return ratio in 0.4f..1.3f
    }

    private fun warpToA4(source: Bitmap, quad: FloatArray): Bitmap {
        val target = floatArrayOf(
            0f, 0f,
            (A4_WIDTH - 1).toFloat(), 0f,
            (A4_WIDTH - 1).toFloat(), (A4_HEIGHT - 1).toFloat(),
            0f, (A4_HEIGHT - 1).toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(quad, 0, target, 0, 4)) {
            return centerCropA4(source)
        }
        val result = Bitmap.createBitmap(A4_WIDTH, A4_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
        canvas.drawBitmap(source, matrix, paint)
        return result
    }

    private fun centerCropA4(source: Bitmap): Bitmap {
        val sourceRatio = source.width.toFloat() / source.height.toFloat()
        val targetRatio = A4_WIDTH.toFloat() / A4_HEIGHT.toFloat()
        var cropWidth = source.width
        var cropHeight = source.height
        if (sourceRatio > targetRatio) {
            cropWidth = (source.height * targetRatio).toInt()
        } else {
            cropHeight = (source.width / targetRatio).toInt()
        }
        val left = (source.width - cropWidth) / 2
        val top = (source.height - cropHeight) / 2
        val cropped = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
        return if (cropped.width == A4_WIDTH && cropped.height == A4_HEIGHT) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, A4_WIDTH, A4_HEIGHT, true)
        }
    }
}
