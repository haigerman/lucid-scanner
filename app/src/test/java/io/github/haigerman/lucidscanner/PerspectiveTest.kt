package io.github.haigerman.lucidscanner

import io.github.haigerman.lucidscanner.scan.Perspective
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PerspectiveTest {
    private val imageW = 1200f
    private val imageH = 1600f

    /** Fotografiert ein Rechteck [w] x [h] mit einer Lochkamera, gekippt um [tiltX]/[tiltY] und gedreht um [spin] (Grad). */
    private fun shoot(w: Double, h: Double, tiltX: Double, tiltY: Double, spin: Double, focal: Double = 1800.0): FloatArray {
        val out = FloatArray(8)
        val corners = listOf(-w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2)
        val ax = Math.toRadians(tiltX); val ay = Math.toRadians(tiltY); val az = Math.toRadians(spin)
        corners.forEachIndexed { i, (px, py) ->
            // erst in der Blattebene drehen, dann um y und x kippen
            var x = px * cos(az) - py * sin(az)
            var y = px * sin(az) + py * cos(az)
            var z = 0.0
            val x1 = x * cos(ay) + z * sin(ay); val z1 = -x * sin(ay) + z * cos(ay)
            x = x1; z = z1
            val y2 = y * cos(ax) - z * sin(ax); val z2 = y * sin(ax) + z * cos(ax)
            y = y2; z = z2 + 45.0
            out[2 * i] = (imageW / 2 + focal * (x + 1.0) / z).toFloat()
            out[2 * i + 1] = (imageH / 2 + focal * (y - 0.5) / z).toFloat()
        }
        return out
    }

    @Test fun a4FromAbove() =
        assertEquals(21f / 29.7f, Perspective.aspectRatio(shoot(21.0, 29.7, 0.0, 0.0, 0.0), imageW, imageH), 0.005f)

    @Test fun a4StronglyTilted() {
        assertEquals(21f / 29.7f, Perspective.aspectRatio(shoot(21.0, 29.7, -35.0, 12.0, 6.0), imageW, imageH), 0.01f)
        assertEquals(21f / 29.7f, Perspective.aspectRatio(shoot(21.0, 29.7, 25.0, -20.0, -10.0), imageW, imageH), 0.01f)
    }

    @Test fun landscapeAndOtherFormats() {
        assertEquals(29.7f / 21f, Perspective.aspectRatio(shoot(29.7, 21.0, -30.0, 10.0, 3.0), imageW, imageH), 0.02f)
        assertEquals(1f, Perspective.aspectRatio(shoot(15.0, 15.0, -30.0, 15.0, 8.0), imageW, imageH), 0.015f)
        // Kassenzettel
        assertEquals(8f / 25f, Perspective.aspectRatio(shoot(8.0, 25.0, -28.0, 8.0, 4.0), imageW, imageH), 0.01f)
    }

    @Test fun tiltedAroundOneAxisOnly() {
        // Brennweite hier nicht berechenbar: Mit der einer üblichen Handykamera (0,6 Diagonalen = 1200) passt es genau …
        assertEquals(21f / 29.7f, Perspective.aspectRatio(shoot(21.0, 29.7, -30.0, 0.0, 0.0, focal = 1200.0), imageW, imageH), 0.01f)
        // … und mit etwas längerer oder kürzerer Brennweite (23–30 mm) bleibt der Fehler so klein, dass DIN-Formate noch erkannt werden
        assertEquals(21f / 29.7f, Perspective.aspectRatio(shoot(21.0, 29.7, -30.0, 0.0, 0.0, focal = 1400.0), imageW, imageH), 0.035f)
        assertEquals(21f / 29.7f, Perspective.aspectRatio(shoot(21.0, 29.7, -30.0, 0.0, 0.0, focal = 1050.0), imageW, imageH), 0.035f)
    }
}
