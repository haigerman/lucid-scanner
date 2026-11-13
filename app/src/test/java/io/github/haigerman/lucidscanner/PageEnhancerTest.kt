package io.github.haigerman.lucidscanner

import io.github.haigerman.lucidscanner.scan.PageEnhancer
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEnhancerTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun shadowedPaperBecomesWhiteAndTextStaysDark() {
        val w = 400
        val h = 560
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val paper = 210 - 90 * x / w // kräftiger Schatten nach rechts
            val text = y % 20 < 3 && x % 50 > 10
            px[y * w + x] = gray(if (text) paper / 4 else paper)
        }
        PageEnhancer.enhance(px, w, h)
        var minPaper = 255
        var maxText = 0
        for (y in 0 until h) for (x in 0 until w) {
            val v = px[y * w + x] and 0xFF
            val text = y % 20 < 3 && x % 50 > 10
            if (text) maxText = maxOf(maxText, v) else if (y % 20 in 8..14) minPaper = minOf(minPaper, v)
        }
        assertTrue("Papier nicht weiß: $minPaper", minPaper >= 245)
        assertTrue("Text nicht dunkel: $maxText", maxText <= 70)
    }

    @Test fun keepsColour() {
        val w = 200
        val h = 200
        val px = IntArray(w * h) { gray(200) }
        val red = (0xFF shl 24) or (180 shl 16) or (30 shl 8) or 30
        for (y in 90 until 110) for (x in 90 until 110) px[y * w + x] = red
        PageEnhancer.enhance(px, w, h)
        val c = px[100 * w + 100]
        assertTrue(((c shr 16) and 0xFF) > 150 && ((c shr 8) and 0xFF) < 60)
    }
}
