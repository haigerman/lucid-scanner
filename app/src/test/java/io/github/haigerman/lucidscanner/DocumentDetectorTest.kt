package io.github.haigerman.lucidscanner

import io.github.haigerman.lucidscanner.scan.DocumentDetector
import io.github.haigerman.lucidscanner.scan.Pt
import io.github.haigerman.lucidscanner.scan.Quad
import io.github.haigerman.lucidscanner.scan.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentDetectorTest {
    private val w = 240
    private val h = 320

    private fun assertFinds(quad: Quad, tolerance: Float = 0.012f, shadow: Boolean = false, noise: Int = 8) {
        val d = DocumentDetector.detect(Synth.scene(w, h, quad, shadow = shadow, noise = noise), w, h)
        assertEquals(Sighting.FOUND, d.sighting)
        val err = d.quad!!.maxCornerDistance(quad)
        assertTrue("Eckenfehler $err bei $quad → ${d.quad}", err < tolerance)
    }

    @Test fun findsStraightSheet() =
        assertFinds(Quad(Pt(0.15f, 0.12f), Pt(0.85f, 0.12f), Pt(0.85f, 0.88f), Pt(0.15f, 0.88f)))

    @Test fun findsTiltedSheet() =
        assertFinds(Quad(Pt(0.22f, 0.10f), Pt(0.88f, 0.18f), Pt(0.78f, 0.90f), Pt(0.10f, 0.80f)))

    @Test fun findsSheetInPerspective() =
        assertFinds(Quad(Pt(0.25f, 0.15f), Pt(0.75f, 0.15f), Pt(0.92f, 0.90f), Pt(0.08f, 0.90f)))

    @Test fun findsSmallSheet() =
        assertFinds(Quad(Pt(0.35f, 0.30f), Pt(0.70f, 0.33f), Pt(0.67f, 0.68f), Pt(0.32f, 0.65f)))

    @Test fun findsSheetWithShadowAndNoise() =
        assertFinds(Quad(Pt(0.18f, 0.14f), Pt(0.84f, 0.11f), Pt(0.86f, 0.86f), Pt(0.14f, 0.89f)), tolerance = 0.02f, shadow = true, noise = 16)

    @Test fun findsStronglyRotatedSheet() {
        // um ca. 40° gedreht: Ecken stimmen, auch wenn „oben links“ dann Ansichtssache ist
        val q = Quad(Pt(0.50f, 0.15f), Pt(0.88f, 0.42f), Pt(0.50f, 0.85f), Pt(0.12f, 0.58f))
        val d = DocumentDetector.detect(Synth.scene(w, h, q), w, h)
        assertEquals(Sighting.FOUND, d.sighting)
        for (c in q.corners) {
            val nearest = d.quad!!.corners.minOf { kotlin.math.hypot(it.x - c.x, it.y - c.y) }
            assertTrue("Ecke $c fehlt (Abstand $nearest)", nearest < 0.015f)
        }
    }

    @Test fun emptyTableIsNoSheet() {
        assertEquals(Sighting.NONE, DocumentDetector.detect(Synth.scene(w, h, null), w, h).sighting)
    }

    @Test fun sheetBeyondFrameIsPartial() {
        val q = Quad(Pt(0.3f, -0.2f), Pt(1.2f, -0.2f), Pt(1.2f, 0.7f), Pt(0.3f, 0.7f))
        val d = DocumentDetector.detect(Synth.scene(w, h, q), w, h)
        assertEquals(Sighting.PARTIAL, d.sighting)
    }

    @Test fun handOverSheetIsNotASheet() {
        // dunkler Keil ragt weit ins Blatt: keine saubere Vierecksform mehr
        val q = Quad(Pt(0.15f, 0.12f), Pt(0.85f, 0.12f), Pt(0.85f, 0.88f), Pt(0.15f, 0.88f))
        val img = Synth.scene(w, h, q)
        for (y in 0 until h) for (x in 0 until w) {
            if (x > w * 0.45 && y > h * 0.35 && y < h * 0.75) img[y * w + x] = 90
        }
        assertTrue(DocumentDetector.detect(img, w, h).sighting != Sighting.FOUND)
    }

    @Test fun cornerOrderIsClockwiseFromTopLeft() {
        val q = Quad(Pt(0.2f, 0.15f), Pt(0.8f, 0.2f), Pt(0.82f, 0.85f), Pt(0.18f, 0.8f))
        val d = DocumentDetector.detect(Synth.scene(w, h, q), w, h).quad
        assertNotNull(d)
        assertTrue(d!!.tl.x < d.tr.x && d.tl.y < d.bl.y && d.br.x > d.bl.x && d.br.y > d.tr.y)
    }

    @Test fun signatureTellsPagesApart() {
        val q = Quad(Pt(0.15f, 0.12f), Pt(0.85f, 0.12f), Pt(0.85f, 0.88f), Pt(0.15f, 0.88f))
        val moved = Quad(Pt(0.19f, 0.15f), Pt(0.88f, 0.17f), Pt(0.86f, 0.91f), Pt(0.17f, 0.89f))
        fun sig(quad: Quad, text: Long, seed: Long, paper: Int = 215): FloatArray {
            val img = Synth.scene(w, h, quad, textSeed = text, seed = seed, paper = paper)
            val found = DocumentDetector.detect(img, w, h).quad!!
            return DocumentDetector.signature(img, w, h, found)
        }
        val page1 = sig(q, 7, 1)
        val samePageShaky = sig(moved, 7, 2, paper = 200)
        val page2 = sig(q, 99, 3)
        val same = DocumentDetector.signatureDiff(page1, samePageShaky)
        val other = DocumentDetector.signatureDiff(page1, page2)
        assertTrue("gleiche Seite: $same", same < 3f)
        assertTrue("andere Seite: $other", other > 5.5f)
    }
}
