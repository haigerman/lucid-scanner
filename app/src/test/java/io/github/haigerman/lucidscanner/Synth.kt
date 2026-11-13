package io.github.haigerman.lucidscanner

import io.github.haigerman.lucidscanner.scan.Pt
import io.github.haigerman.lucidscanner.scan.Quad
import java.util.Random

/** Künstliche Kamerabilder: helles Blatt mit „Textzeilen“ auf dunklem, verrauschtem Tisch. */
object Synth {

    fun scene(
        w: Int, h: Int, quad: Quad?,
        paper: Int = 215, table: Int = 80, noise: Int = 8, seed: Long = 1,
        textSeed: Long = 7, shadow: Boolean = false,
    ): IntArray {
        val rnd = Random(seed)
        val out = IntArray(w * h)
        // Zeilenmuster je Seite: welche Zeilen sind „beschrieben“ und wie lang
        val t = Random(textSeed)
        val lines = FloatArray(40) { if (t.nextFloat() < 0.75f) 0.3f + 0.6f * t.nextFloat() else 0f }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val fx = (x + 0.5f) / w
                val fy = (y + 0.5f) / h
                var v = table + (20 * fx).toInt() // leichter Helligkeitsverlauf im Tisch
                if (quad != null) {
                    val uv = inverse(quad, fx, fy)
                    if (uv != null) {
                        v = paper
                        if (shadow) v -= (50 * uv[0]).toInt()
                        val line = (uv[1] * 40).toInt().coerceIn(0, 39)
                        val inLine = (uv[1] * 40) % 1f < 0.5f
                        if (inLine && uv[0] > 0.1f && uv[0] < 0.1f + 0.8f * lines[line] && uv[1] > 0.08f && uv[1] < 0.92f) v -= 60
                    }
                }
                out[y * w + x] = (v + rnd.nextInt(2 * noise + 1) - noise).coerceIn(0, 255)
            }
        }
        return out
    }

    /** Liegt (x, y) im Viereck? Wenn ja: ungefähre Blattkoordinaten (u, v). */
    private fun inverse(q: Quad, x: Float, y: Float): FloatArray? {
        val c = q.corners
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) < 0) return null
        }
        fun dist(a: Pt, b: Pt): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            return kotlin.math.abs(dx * (y - a.y) - dy * (x - a.x)) / kotlin.math.hypot(dx, dy)
        }
        val dl = dist(q.bl, q.tl)
        val dr = dist(q.tr, q.br)
        val dt = dist(q.tl, q.tr)
        val db = dist(q.br, q.bl)
        return floatArrayOf(dl / (dl + dr), dt / (dt + db))
    }
}
