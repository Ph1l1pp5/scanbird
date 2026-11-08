package de.ph1l1pp5.scanbird.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max

/**
 * Findet das Blatt im Foto: die größte helle Fläche gegen den dunkleren Untergrund.
 * Die vier Ecken werden über Extremwerte von x+y und x-y bestimmt, das Ergebnis
 * mit Perspektivkorrektur (setPolyToPoly) auf A4 gebracht (150 dpi).
 * Scheitert die Erkennung, wird das ganze Bild mittig auf A4 beschnitten.
 * Alles lokal, ohne ML Kit, ohne Netz.
 */
object DocumentFinder {

    private const val A4_WIDTH = 1240
    private const val A4_HEIGHT = 1754

    fun extractPage(source: Bitmap): Bitmap {
        return try {
            val quad = findQuad(source)
            if (quad != null) warpToA4(source, quad) else centerCropA4(source)
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
        val backgroundMean = (backgroundSum / max(1L, backgroundCount)).toInt()

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
