package io.github.haigerman.lucidscanner.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Macht aus einem Foto eine saubere Seite: gleicht ungleichmäßige Beleuchtung und Schatten aus,
 * zieht das Papier auf Weiß und verstärkt den Kontrast etwas. Farben bleiben erhalten.
 */
object PageEnhancer {

    /** Bearbeitet [pixels] (ARGB) an Ort und Stelle. */
    fun enhance(pixels: IntArray, w: Int, h: Int) {
        if (w < 8 || h < 8) return
        val cell = max(8, max(w, h) / 72)
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell

        // --- Papierhelligkeit je Kachel: helles Ende der Helligkeitsverteilung
        val paper = FloatArray(gw * gh)
        val hist = IntArray(256)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                hist.fill(0)
                var count = 0
                val x1 = min(w, (gx + 1) * cell)
                val y1 = min(h, (gy + 1) * cell)
                var y = gy * cell
                while (y < y1) {
                    var x = gx * cell
                    while (x < x1) {
                        hist[luma(pixels[y * w + x])]++
                        count++
                        x += 2
                    }
                    y += 2
                }
                var remaining = count / 5 // 80. Perzentil
                var v = 255
                while (v > 0 && remaining >= hist[v]) { remaining -= hist[v]; v-- }
                paper[gy * gw + gx] = v.toFloat()
            }
        }
        // Kacheln ohne Papier (Fotos, dunkle Flächen, fette Überschriften) nicht hochziehen:
        // Sie bekommen die Papierhelligkeit ihrer Umgebung.
        val sorted = paper.copyOf().also { it.sort() }
        val typical = sorted[sorted.size * 3 / 4]
        val floor = max(40f, 0.45f * typical)
        fillFromNeighbours(paper, gw, gh, floor, typical)
        val smooth = smooth(paper, gw, gh)

        // --- Kennlinie: Weißpunkt knapp unter Papierhelligkeit, etwas steilerer Kontrast
        val curve = IntArray(256) { v ->
            val x = min(1.0, v / (255.0 * 0.92))
            (255.0 * x.pow(1.25)).toInt().coerceIn(0, 255)
        }

        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, gh - 1f)
            val y0 = fy.toInt()
            val y1 = min(gh - 1, y0 + 1)
            val wy = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, gw - 1f)
                val x0 = fx.toInt()
                val x1 = min(gw - 1, x0 + 1)
                val wx = fx - x0
                val top = smooth[y0 * gw + x0] * (1 - wx) + smooth[y0 * gw + x1] * wx
                val bottom = smooth[y1 * gw + x0] * (1 - wx) + smooth[y1 * gw + x1] * wx
                val bg = top * (1 - wy) + bottom * wy
                val gain = (255f * 256f / bg).toInt()
                val c = pixels[y * w + x]
                val r = curve[min(255, (((c shr 16) and 0xFF) * gain) shr 8)]
                val g = curve[min(255, (((c shr 8) and 0xFF) * gain) shr 8)]
                val b = curve[min(255, ((c and 0xFF) * gain) shr 8)]
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun luma(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /** Ersetzt alle Kacheln unter [floor] schrittweise durch den Mittelwert ihrer gültigen Nachbarn. */
    private fun fillFromNeighbours(grid: FloatArray, w: Int, h: Int, floor: Float, fallback: Float) {
        val valid = BooleanArray(grid.size) { grid[it] >= floor }
        if (valid.none { it }) {
            grid.fill(max(floor, fallback))
            return
        }
        while (true) {
            val filled = ArrayList<Int>()
            val values = ArrayList<Float>()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (valid[y * w + x]) continue
                    var acc = 0f
                    var n = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val xx = x + dx
                            val yy = y + dy
                            if (xx < 0 || yy < 0 || xx >= w || yy >= h || !valid[yy * w + xx]) continue
                            acc += grid[yy * w + xx]
                            n++
                        }
                    }
                    if (n > 0) { filled += y * w + x; values += acc / n }
                }
            }
            if (filled.isEmpty()) return
            for (i in filled.indices) { grid[filled[i]] = values[i]; valid[filled[i]] = true }
        }
    }

    private fun smooth(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                        acc += src[yy * w + xx]
                        n++
                    }
                }
                out[y * w + x] = acc / n
            }
        }
        return out
    }
}
