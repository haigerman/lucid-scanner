package io.github.haigerman.lucidscanner.scan

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Punkt in Bildkoordinaten, normiert auf 0..1 (x nach rechts, y nach unten). */
data class Pt(val x: Float, val y: Float)

/** Die vier Ecken eines Blatts: oben links, oben rechts, unten rechts, unten links. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val corners: List<Pt> get() = listOf(tl, tr, br, bl)

    /** Größter Abstand zweier einander entsprechender Ecken. */
    fun maxCornerDistance(other: Quad): Float {
        var m = 0f
        val a = corners
        val b = other.corners
        for (i in 0 until 4) m = max(m, hypot(a[i].x - b[i].x, a[i].y - b[i].y))
        return m
    }

    companion object {
        val FULL = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
    }
}

enum class Sighting {
    /** Kein Blatt im Bild. */
    NONE,

    /** Etwas Helles ist da, ragt aber über den Bildrand. */
    PARTIAL,

    /** Blatt vollständig erkannt. */
    FOUND,
}

class Detection(val sighting: Sighting, val quad: Quad?)

/**
 * Findet ein helles Blatt auf dunklerem Untergrund.
 *
 * Ablauf: glätten → Schwelle nach Otsu → größte helle Fläche → konvexe Hülle → auf vier Ecken
 * reduzieren → Kanten als Geraden an den Umriss anpassen und schneiden. Arbeitet auf einem
 * kleinen Helligkeitsbild (ca. 240–480 Pixel Kantenlänge) und braucht keine Android-Klassen.
 */
object DocumentDetector {

    /** Mindestabstand der mittleren Helligkeit von Blatt und Untergrund (0..255). */
    private const val MIN_CONTRAST = 32

    /** Mindestanteil des Blatts an der Bildfläche. */
    private const val MIN_AREA = 0.07f

    fun detect(lum: IntArray, w: Int, h: Int): Detection {
        require(lum.size >= w * h)
        if (w < 16 || h < 16) return Detection(Sighting.NONE, null)
        val img = blur(lum, w, h)

        val threshold = otsu(img, w * h) ?: return Detection(Sighting.NONE, null)

        // --- zusammenhängende helle Flächen
        val labels = IntArray(w * h)
        val stack = IntArray(w * h)
        val minArea = (MIN_AREA * w * h).toInt()
        var bestLabel = 0
        var bestArea = 0
        var partialSeen = false
        var next = 0
        for (start in 0 until w * h) {
            if (labels[start] != 0 || img[start] <= threshold) continue
            next++
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            var area = 0
            var border = 0
            while (sp > 0) {
                val p = stack[--sp]
                area++
                val x = p % w
                val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border++
                if (x > 0 && labels[p - 1] == 0 && img[p - 1] > threshold) { labels[p - 1] = next; stack[sp++] = p - 1 }
                if (x < w - 1 && labels[p + 1] == 0 && img[p + 1] > threshold) { labels[p + 1] = next; stack[sp++] = p + 1 }
                if (y > 0 && labels[p - w] == 0 && img[p - w] > threshold) { labels[p - w] = next; stack[sp++] = p - w }
                if (y < h - 1 && labels[p + w] == 0 && img[p + w] > threshold) { labels[p + w] = next; stack[sp++] = p + w }
            }
            if (area < minArea) continue
            if (border > 2) {
                partialSeen = true
            } else if (area > bestArea) {
                bestArea = area
                bestLabel = next
            }
        }
        val miss = Detection(if (partialSeen) Sighting.PARTIAL else Sighting.NONE, null)
        if (bestLabel == 0) return miss

        // --- Umriss der Fläche
        var n = 0
        val bx = IntArray(bestArea)
        val by = IntArray(bestArea)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * w + x
                if (labels[p] != bestLabel) continue
                val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                    labels[p - 1] != bestLabel || labels[p + 1] != bestLabel ||
                    labels[p - w] != bestLabel || labels[p + w] != bestLabel
                if (edge) { bx[n] = x; by[n] = y; n++ }
            }
        }

        val hull = convexHull(bx, by, n)
        if (hull.size < 4) return miss
        val hullArea = polygonArea(hull)
        // Ein Blatt füllt seine Hülle fast ganz aus (Text reißt nur kleine Löcher).
        if (bestArea < 0.78 * hullArea) return miss

        val rough = reduceToFour(hull)
        val quad = refine(rough, bx, by, n)

        // --- Plausibilität
        val quadArea = polygonArea(quad)
        if (quadArea <= 0.0 || hullArea < 0.88 * quadArea || hullArea > 1.1 * quadArea) return miss
        val minSide = 0.14 * min(w, h)
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val c = quad[(i + 3) % 4]
            if (hypot(b[0] - a[0], b[1] - a[1]) < minSide) return miss
            val angle = Math.toDegrees(angleBetween(b[0] - a[0], b[1] - a[1], c[0] - a[0], c[1] - a[1]))
            if (angle < 50 || angle > 130) return miss
            if (a[0] < -1 || a[1] < -1 || a[0] > w + 1 || a[1] > h + 1) return Detection(Sighting.PARTIAL, null)
        }
        return Detection(Sighting.FOUND, order(quad, w, h))
    }

    // ------------------------------------------------------------------ Seiten-Fingerabdruck

    const val SIG_W = 12
    const val SIG_H = 16

    /**
     * Grober Helligkeits-Fingerabdruck des Blattinhalts (unabhängig von Lage und Belichtung).
     * Damit lässt sich erkennen, ob noch dieselbe Seite im Bild liegt.
     */
    fun signature(lum: IntArray, w: Int, h: Int, quad: Quad): FloatArray {
        val raw = FloatArray(SIG_W * SIG_H)
        val n = 6 // Abtastpunkte je Zelle und Richtung
        for (gy in 0 until SIG_H) {
            for (gx in 0 until SIG_W) {
                var acc = 0f
                for (s in 0 until n * n) {
                    val u = 0.07f + 0.86f * (gx + (s % n + 0.5f) / n) / SIG_W
                    val v = 0.07f + 0.86f * (gy + (s / n + 0.5f) / n) / SIG_H
                    val tx = quad.tl.x + (quad.tr.x - quad.tl.x) * u
                    val ty = quad.tl.y + (quad.tr.y - quad.tl.y) * u
                    val bx = quad.bl.x + (quad.br.x - quad.bl.x) * u
                    val by = quad.bl.y + (quad.br.y - quad.bl.y) * u
                    val px = ((tx + (bx - tx) * v) * w).toInt().coerceIn(0, w - 1)
                    val py = ((ty + (by - ty) * v) * h).toInt().coerceIn(0, h - 1)
                    acc += lum[py * w + px]
                }
                raw[gy * SIG_W + gx] = acc / (n * n)
            }
        }
        // Nachbarzellen mitteln: kleine Verschiebungen des Blatts sollen nichts ausmachen
        val out = FloatArray(SIG_W * SIG_H)
        var sum = 0f
        for (gy in 0 until SIG_H) {
            for (gx in 0 until SIG_W) {
                var acc = 0f
                var cnt = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val x = gx + dx
                        val y = gy + dy
                        if (x < 0 || y < 0 || x >= SIG_W || y >= SIG_H) continue
                        acc += raw[y * SIG_W + x]
                        cnt++
                    }
                }
                out[gy * SIG_W + gx] = acc / cnt
                sum += acc / cnt
            }
        }
        val mean = max(1f, sum / out.size)
        for (i in out.indices) out[i] = out[i] / mean
        return out
    }

    /** Mittlere Abweichung zweier Fingerabdrücke in Helligkeitsstufen (0..255). */
    fun signatureDiff(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += abs(a[i] - b[i])
        return d / a.size * 255f
    }

    // ------------------------------------------------------------------ Bausteine

    private fun blur(src: IntArray, w: Int, h: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = src[row + max(0, x - 1)]
                val r = src[row + min(w - 1, x + 1)]
                tmp[row + x] = l + src[row + x] + r
            }
        }
        for (y in 0 until h) {
            val up = max(0, y - 1) * w
            val down = min(h - 1, y + 1) * w
            val row = y * w
            for (x in 0 until w) out[row + x] = (tmp[up + x] + tmp[row + x] + tmp[down + x]) / 9
        }
        return out
    }

    /** Schwelle zwischen hell und dunkel; null, wenn das Bild keine zwei klar getrennten Helligkeiten hat. */
    private fun otsu(img: IntArray, count: Int): Int? {
        val hist = IntArray(256)
        for (i in 0 until count) hist[img[i].coerceIn(0, 255)]++
        var total = 0.0
        for (i in 0 until 256) total += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = -1.0
        var bestT = 0
        var bestLow = 0.0
        var bestHigh = 0.0
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = count - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (total - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; bestT = t; bestLow = mB; bestHigh = mF }
        }
        if (best < 0 || bestHigh - bestLow < MIN_CONTRAST) return null
        return bestT
    }

    /** Konvexe Hülle (Andrews Monotone Chain); Ergebnis als Liste von [x, y]. */
    private fun convexHull(xs: IntArray, ys: IntArray, n: Int): List<DoubleArray> {
        if (n < 3) return emptyList()
        val idx = (0 until n).sortedWith(compareBy({ xs[it] }, { ys[it] }))
        val hull = IntArray(2 * n)
        var k = 0
        fun cross(o: Int, a: Int, b: Int): Long =
            (xs[a] - xs[o]).toLong() * (ys[b] - ys[o]) - (ys[a] - ys[o]).toLong() * (xs[b] - xs[o])
        for (i in idx) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], i) <= 0) k--
            hull[k++] = i
        }
        val lower = k + 1
        for (j in idx.size - 2 downTo 0) {
            val i = idx[j]
            while (k >= lower && cross(hull[k - 2], hull[k - 1], i) <= 0) k--
            hull[k++] = i
        }
        // Pixelmitte statt Pixelecke
        return (0 until k - 1).map { doubleArrayOf(xs[hull[it]] + 0.5, ys[hull[it]] + 0.5) }
    }

    private fun polygonArea(p: List<DoubleArray>): Double {
        var a = 0.0
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            a += p[i][0] * q[1] - q[0] * p[i][1]
        }
        return abs(a) / 2
    }

    /** Entfernt so lange die Ecke, die am wenigsten Fläche kostet, bis vier übrig sind. */
    private fun reduceToFour(hull: List<DoubleArray>): List<DoubleArray> {
        val pts = hull.toMutableList()
        while (pts.size > 4) {
            var bestI = 0
            var bestLoss = Double.MAX_VALUE
            for (i in pts.indices) {
                val a = pts[(i + pts.size - 1) % pts.size]
                val b = pts[i]
                val c = pts[(i + 1) % pts.size]
                val loss = abs((b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1]))
                if (loss < bestLoss) { bestLoss = loss; bestI = i }
            }
            pts.removeAt(bestI)
        }
        return pts
    }

    /**
     * Passt jede der vier Kanten als Gerade an die Umrisspunkte in ihrer Nähe an und schneidet
     * benachbarte Geraden. Das trifft die Ecken genauer als einzelne Pixel – auch bei runden
     * oder leicht umgeknickten Ecken.
     */
    private fun refine(quad: List<DoubleArray>, bx: IntArray, by: IntArray, n: Int): List<DoubleArray> {
        // je Kante: Punkt auf der Geraden (px, py) und Richtung (dx, dy)
        val lines = Array(4) { DoubleArray(4) }
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            val ux = (b[0] - a[0]) / len
            val uy = (b[1] - a[1]) / len
            val tol = max(2.0, 0.035 * len)
            var cnt = 0
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (j in 0 until n) {
                val x = bx[j] + 0.5
                val y = by[j] + 0.5
                val t = ((x - a[0]) * ux + (y - a[1]) * uy) / len
                if (t < 0.12 || t > 0.88) continue
                val d = abs((x - a[0]) * uy - (y - a[1]) * ux)
                if (d > tol) continue
                cnt++; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
            }
            if (cnt >= 8) {
                val mx = sx / cnt
                val my = sy / cnt
                val theta = 0.5 * atan2(2 * (sxy / cnt - mx * my), (sxx / cnt - mx * mx) - (syy / cnt - my * my))
                lines[i] = doubleArrayOf(mx, my, cos(theta), sin(theta))
            } else {
                lines[i] = doubleArrayOf(a[0], a[1], ux, uy)
            }
        }
        return (0 until 4).map { i ->
            // Ecke i liegt zwischen Kante i-1 und Kante i
            intersect(lines[(i + 3) % 4], lines[i]) ?: quad[i]
        }
    }

    private fun intersect(l1: DoubleArray, l2: DoubleArray): DoubleArray? {
        val den = l1[2] * l2[3] - l1[3] * l2[2]
        if (abs(den) < 1e-6) return null
        val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / den
        return doubleArrayOf(l1[0] + t * l1[2], l1[1] + t * l1[3])
    }

    private fun angleBetween(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val c = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))
        return acos(c.coerceIn(-1.0, 1.0))
    }

    /** Sortiert im Uhrzeigersinn, beginnend oben links, und normiert auf 0..1. */
    private fun order(q: List<DoubleArray>, w: Int, h: Int): Quad {
        val cx = q.sumOf { it[0] } / 4
        val cy = q.sumOf { it[1] } / 4
        val sorted = q.sortedBy { atan2(it[1] - cy, it[0] - cx) }
        val first = sorted.indices.minByOrNull { sorted[it][0] + sorted[it][1] } ?: 0
        fun pt(i: Int): Pt {
            val p = sorted[(first + i) % 4]
            return Pt((p[0] / w).toFloat().coerceIn(0f, 1f), (p[1] / h).toFloat().coerceIn(0f, 1f))
        }
        return Quad(pt(0), pt(1), pt(2), pt(3))
    }
}
