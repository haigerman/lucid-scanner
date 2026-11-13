package io.github.haigerman.lucidscanner.scan

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

object Perspective {

    /**
     * Wahres Seitenverhältnis (Breite : Höhe) eines schräg fotografierten Rechtecks.
     *
     * Aus den vier Ecken im Foto lässt sich die Brennweite und daraus das Verhältnis berechnen
     * (Zhang & He, „Whiteboard Scanning and Image Enhancement“), solange die Bildmitte die optische
     * Achse ist – bei Handykameras ist das so.
     *
     * Ecken in Pixeln als [x0, y0, …] in der Reihenfolge oben links, oben rechts, unten rechts,
     * unten links; [imageW]/[imageH] sind die Maße des ganzen Fotos.
     */
    fun aspectRatio(c: FloatArray, imageW: Float, imageH: Float): Float {
        val cx = imageW / 2.0
        val cy = imageH / 2.0
        // m1 oben links, m2 oben rechts, m3 unten links, m4 unten rechts – relativ zur Bildmitte
        val m1 = doubleArrayOf(c[0] - cx, c[1] - cy, 1.0)
        val m2 = doubleArrayOf(c[2] - cx, c[3] - cy, 1.0)
        val m3 = doubleArrayOf(c[6] - cx, c[7] - cy, 1.0)
        val m4 = doubleArrayOf(c[4] - cx, c[5] - cy, 1.0)

        val simple = run {
            val w = (hypot(m2[0] - m1[0], m2[1] - m1[1]) + hypot(m4[0] - m3[0], m4[1] - m3[1])) / 2
            val h = (hypot(m3[0] - m1[0], m3[1] - m1[1]) + hypot(m4[0] - m2[0], m4[1] - m2[1])) / 2
            (w / h).toFloat()
        }

        val d2 = dot(cross(m2, m4), m3)
        val d3 = dot(cross(m3, m4), m2)
        if (abs(d2) < 1e-9 || abs(d3) < 1e-9) return simple
        val k2 = dot(cross(m1, m4), m3) / d2
        val k3 = dot(cross(m1, m4), m2) / d3
        val n2 = doubleArrayOf(k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 - 1)
        val n3 = doubleArrayOf(k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 - 1)

        // Blatt genau von oben: keine Perspektive, die Kantenlängen stimmen schon
        val flat2 = abs(n2[2]) < 0.015
        val flat3 = abs(n3[2]) < 0.015
        if (flat2 && flat3) return simple

        // Ist das Blatt nur um eine Achse gekippt (ein Kantenpaar bleibt im Foto parallel), lässt
        // sich die Brennweite nicht berechnen. Dann gilt der übliche Wert einer Handy-Hauptkamera
        // (rund 26 mm Kleinbild, also etwa 0,6 Bilddiagonalen).
        val diagonal = hypot(imageW.toDouble(), imageH.toDouble())
        val typical = (0.6 * diagonal) * (0.6 * diagonal)
        var f2 = if (flat2 || flat3) typical else -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
        if (f2.isNaN() || f2 < typical / 4 || f2 > typical * 9) f2 = typical

        val w2 = (n2[0] * n2[0] + n2[1] * n2[1]) / f2 + n2[2] * n2[2]
        val h2 = (n3[0] * n3[0] + n3[1] * n3[1]) / f2 + n3[2] * n3[2]
        if (w2 <= 0 || h2 <= 0) return simple
        val ratio = sqrt(w2 / h2).toFloat()
        // als Sicherung: nie weit von der einfachen Schätzung weg
        return if (ratio.isNaN() || ratio < simple / 2 || ratio > simple * 2) simple else ratio
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
