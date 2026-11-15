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
 *     Papierweiß. (2) Sauvola-Binarisierung mit Grauband für knackscharfen
 *     Scanner-Kontrast — hellgraue Briefkopfzeilen bleiben als Grauton lesbar
 *     statt unter Weiß zu verschwinden. (3) Kippung bis ±8 Grad über Profil-Bewertung geraderücken. (4) Dunkle Tischreste
 *     an den Rändern abschneiden, damit das Blatt den Rahmen füllt.
 *     (5) Papierkorn — pures Kopierer-Weiß wird zu Papier um 250 mit feinem
 *     Korn und Deckelschatten-Vignette. Seit 0.5.30 wischt dazwischen eine
 *     Staub-Retusche isolierte Mini-Flecken zu Papier.
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
        // 0.5.30: Staub-Retusche direkt nach der Sauvola — die Kippungs-
        // Messung profitiert gleich mit (runderes Profil).
        val binary = dustRemoval(sauvola(flat, w, h), w, h)
        val angle = estimateSkew(binary, w, h)

        var bmp = binaryToBitmap(binary, w, h)
        bmp = reframe(bmp, angle)
        bmp = trimEdgesAndFill(bmp)
        return paperGrain(bmp)
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
     * Sauvola-Binarisierung mit Graukeil (Band seit 0.5.21, Keil seit 0.5.23,
     * Keil über geglättetem Ton seit 0.5.24): lokale Schwelle aus Fenster-
     * Mittelwert und -Streuung. Klar ueber der Schwelle (+Rand) wird Papier-
     * weiss, klar darunter Schrift-Schwarz — und der Streifen dazwischen wird
     * als KEIL abgebildet: direkt ueber der Schwelle fast schwarz, am Bandrand
     * weiss. Hellgraue Briefkopfzeilen fielen unter der harten Binarisierung
     * spurlos durch; das 0.5.21-Band rettete sie als Roh-Grauton, aber der
     * lag so nah an der Papierweiss, dass der Absender weich und unscharf
     * wirkte — der 0.5.23-Keil drueckte ihn dunkel und steil. 0.5.24 behebt
     * die letzte Unruhe: Der Keil liest den Ton nicht mehr aus dem EINZELNEN
     * Pixel, sondern aus seinem 3x3-Gemittel — die Sauvola-Schwelle flackert
     * bei hellem Grau mitten durch die Buchstaben und zerriss Glyphen in
     * Schwarz/Keil/Weiss-Flecken; das Gemittel mittelt das Flackern weg,
     * die Zeile wird ein ruhiger, satter Grauton wie vom Flachbett. Seit
     * 0.5.25 faengt ein Weisspunkt-Boden in flachen Fenstern zudem Tinte,
     * die nur wenige Stufen dunkler als Papier ist — die Sauvola-Schwelle
     * allein hiess sie Weiss. Seit 0.5.27 ist der Ton zweistufig: Strich-
     * kerne lesen das echte Pixel, Flaches das Gemittel.
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
        val smooth = smooth3(src, w, h)
        val out = IntArray(src.size)
        val half = win / 2
        val k = 0.2f
        val r = 128f
        val band = 24f
        // Keil-Steilheit: 10 → der Bandstreifen (24 Graustufen) wird auf
        // rund 240 Ausgangsstufen gespreizt — Tunable: Absender noch zu blass →
        // hoeher (12-14); Hintergrund fleckig → niedriger (6-8).
        val steep = 10f
        // Boden-Kern-Steilheit (0.5.30): 12 — die blassgrau gedruckte Brief-
        // kopfzeile zeichnet einen Hauch satter, ohne den Ton zu faelschen
        // (das Original misst rund ein Drittel der Fliesstext-Dunkelheit).
        // Tunable: noch matter → hoeher; Ton wirkt unecht → zurueck auf 10.
        val floorSteep = 12f
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
                val sauvo = mean * (1f + k * (sd / r - 1f))
                // Weisspunkt-Boden (0.5.25, in 0.5.28 neu gefasst): In fast
                // flachen Fenstern (Streuung unter 10) liegt die Sauvola-
                // Schwelle weit unter dem Papier — hellgraue Briefkopf-Tinte,
                // die nur wenige Stufen dunkler ist, flog zu Weiss. Boden-
                // Fenster bekommen jetzt einen EIGENEN Weg: Weiss-Grenze bei
                // Papier minus halbem Band, Schwarz-Grenze bei Papier minus
                // eineinhalbfachem Band — und dazwischen wird blasse Tinte
                // aus dem PAPIER-Abstand bewertet (Kern roh und satt, Flaches
                // gemaessigt und fast weiss), nicht aus dem Abstand zur tiefen
                // Sauvola-Schwelle. Tunable: Streuungs-Gate 10.
                val floor = sd < 10f && sauvo < mean - band
                val threshold = if (floor) mean - band * 1.5f else sauvo
                val v = src[y * w + x]
                val sm = smooth[y * w + x]
                out[y * w + x] = when {
                    v > threshold + band -> 255
                    v <= threshold -> 0
                    floor -> {
                        // Boden-Fenster (0.5.28, 0.5.29): Weiss-Grenze = Papier
                        // - 12 (halbes Band), Schwarz-Grenze = Papier - 36
                        // (Band*1.5), dazwischen zaehlt der Abstand zum PAPIER:
                        // Kerne (roh mindestens EIN DRITTEL Band tiefer UND
                        // Umfeld mitgezogen) nehmen vollen Kontrast aus dem
                        // echten Pixel — die Absenderzeile wird satt statt
                        // blass (0.5.28 las die Flanke unter halbem Band blass;
                        // 0.5.29 zieht die Kerntiefe auf ein Drittel runter).
                        // Alles Flache (Rauschen, JPEG-Klumpen, Staub) liest den
                        // sanften Ton aus dem 3x3-Gemittel und verwischt fast
                        // zu Weiss — die Schlieren vom Handyfoto verschwinden.
                        // Tunables: Kerntiefe 8 (ein Drittel Band), Mitzieh-
                        // Gate 3, sanfte Steilheit 3, Kern-Steilheit 12.
                        val dRaw = mean - v
                        val dSm = mean - sm
                        if (dRaw >= band / 3f && dSm >= 3f) {
                            (255 - dRaw * floorSteep).toInt().coerceIn(0, 255)
                        } else {
                            (255 - dSm * 3f).toInt().coerceIn(0, 255)
                        }
                    }
                    else -> {
                        // Text-Fenster: zweistufiger Keil (0.5.27) — DERBE Tinte
                        // (Pixel roh unter halber Bandtiefe UND Umfeld mitgezogen)
                        // nimmt ihren Ton aus dem ECHTEN Pixel, Strichkerne
                        // behalten vollen Kontrast; alles Flache bleibt beim
                        // geglaetteten Ton und verwischt zu Nichts.
                        val base = if (v < mean - band * 0.5f && sm < mean - 3f) v else sm
                        (255 - (band - (base - threshold)) * steep).toInt().coerceIn(0, 255)
                    }
                }
            }
        }
        return out
    }

    /**
     * Staub-Retusche (0.5.30): Isolierte Mini-Blobs werden zu Papier. Alles,
     * was als eigener Fleck unter der Staub-Flaeche bleibt und im Isolations-
     * Abstand keinen GROESSEREN Blob findet, ist Staub, Koernung oder JPEG-
     * Klumpen und wird weiss. Zeichen bleiben unberuehrt: i-Punkte, Umlaut-
     * Dots, Kommas und Satzpunkte haengen nahe an Text — nur groessere Blobs
     * zaehlen als Nachbar, Staub neben Staub schuetzt sich nicht gegenseitig.
     * Echte Fasern und Flecken ueber der Staub-Flaeche bleiben (authentisch).
     * Tunables: Ton-Grenze 160 (was satt gezeichnet wurde), Staub-Flaeche
     * 18 px (~0,26 mm² bei 200 dpi), Isolation 14 px (~1,8 mm).
     */
    private fun dustRemoval(gray: IntArray, w: Int, h: Int): IntArray {
        val darkLimit = 160
        val dustArea = 18
        val isolation = 14
        val label = IntArray(w * h) { -1 }
        val queue = IntArray(w * h)
        val areas = mutableListOf<Int>()
        val boxes = mutableListOf<IntArray>()
        val pixelsOf = mutableListOf<IntArray>()
        var nextLabel = 0
        for (i in gray.indices) {
            if (label[i] != -1 || gray[i] >= darkLimit) continue
            val id = nextLabel++
            var head = 0
            var tail = 0
            queue[tail++] = i
            label[i] = id
            var minX = i % w
            var maxX = minX
            var minY = i / w
            var maxY = minY
            while (head < tail) {
                val p = queue[head++]
                val px = p % w
                val py = p / w
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (px > 0 && label[p - 1] == -1 && gray[p - 1] < darkLimit) { label[p - 1] = id; queue[tail++] = p - 1 }
                if (px < w - 1 && label[p + 1] == -1 && gray[p + 1] < darkLimit) { label[p + 1] = id; queue[tail++] = p + 1 }
                if (py > 0 && label[p - w] == -1 && gray[p - w] < darkLimit) { label[p - w] = id; queue[tail++] = p - w }
                if (py < h - 1 && label[p + w] == -1 && gray[p + w] < darkLimit) { label[p + w] = id; queue[tail++] = p + w }
            }
            areas.add(tail)
            boxes.add(intArrayOf(minX, minY, maxX, maxY))
            pixelsOf.add(queue.copyOf(tail))
        }
        for (id in 0 until areas.size) {
            if (areas[id] > dustArea) continue
            val b = boxes[id]
            var isolated = true
            for (other in areas.indices) {
                if (other == id || areas[other] <= dustArea) continue
                val o = boxes[other]
                val dx = max(max(b[0] - o[2], o[0] - b[2]), 0)
                val dy = max(max(b[1] - o[3], o[1] - b[3]), 0)
                if (dx * dx + dy * dy <= isolation * isolation) { isolated = false; break }
            }
            if (isolated) for (p in pixelsOf[id]) gray[p] = 255
        }
        return gray
    }

    /**
     * 3x3-Box-Gemittel, separierbar in zwei Laufen (horizontal, dann vertikal),
     * Raender geklemmt. Liefert die ruhige Tonlage, aus der der Graukeil liest.
     */
    private fun smooth3(src: IntArray, w: Int, h: Int): IntArray {
        val tmp = IntArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val a = src[row + max(0, x - 1)]
                val b = src[row + x]
                val c = src[row + min(w - 1, x + 1)]
                tmp[row + x] = (a + b + c) / 3
            }
        }
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val up = max(0, y - 1) * w
            val mid = y * w
            val down = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                out[mid + x] = (tmp[up + x] + tmp[mid + x] + tmp[down + x]) / 3
            }
        }
        return out
    }

    /**
     * Papierkorn (0.5.24): Pures Weiss 255 wirkt steril wie vom Kopierer —
     * Flachbett-Scans zeigen Papier um 248-252 mit feinem Korn und einem
     * leichten Deckelschatten an den Ecken. Reine Weiss-Pixel bekommen darum
     * Grundton 250, deterministisches Positionen-Rauschen (±2) und eine
     * sanfte Vignette; Schrift-Schwarz und Grautone bleiben unangetastet.
     * Tunables: Grundton 250, Vignette 0.012 (0 = aus).
     */
    private fun paperGrain(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxD = sqrt(cx * cx + cy * cy)
        val vignette = 0.012f
        for (y in 0 until h) {
            val dy = (y - cy) / maxD
            for (x in 0 until w) {
                val i = y * w + x
                if (px[i] == -0x1) { // 0xFFFFFFFF: pures Weiss
                    val noise = ((((x * 73856093) xor (y * 19349663)) % 5) + 5) % 5 - 2
                    val dx = (x - cx) / maxD
                    val vig = (vignette * (dx * dx + dy * dy) * 255f).toInt()
                    val v = (250 + noise - vig).coerceIn(0, 255)
                    px[i] = -0x1000000 or (v shl 16) or (v shl 8) or v
                }
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
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
