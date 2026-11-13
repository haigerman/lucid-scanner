package io.github.haigerman.lucidscanner

import io.github.haigerman.lucidscanner.scan.CaptureTrigger
import io.github.haigerman.lucidscanner.scan.DocumentDetector
import io.github.haigerman.lucidscanner.scan.Phase
import io.github.haigerman.lucidscanner.scan.Pt
import io.github.haigerman.lucidscanner.scan.Quad
import io.github.haigerman.lucidscanner.scan.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTriggerTest {
    private val quad = Quad(Pt(0.2f, 0.2f), Pt(0.8f, 0.2f), Pt(0.8f, 0.8f), Pt(0.2f, 0.8f))
    private fun shifted(d: Float) = Quad(Pt(0.2f + d, 0.2f), Pt(0.8f + d, 0.2f), Pt(0.8f + d, 0.8f), Pt(0.2f + d, 0.8f))
    private fun sig(v: Float) = FloatArray(DocumentDetector.SIG_W * DocumentDetector.SIG_H) { if (it % 2 == 0) 1f + v else 1f - v }

    /** Füttert Bilder im 50-ms-Takt; liefert die Zeit, zu der ausgelöst wurde (oder -1). */
    private fun CaptureTrigger.run(from: Long, to: Long, q: Quad?, s: FloatArray?): Long {
        var t = from
        while (t <= to) {
            val state = onFrame(if (q != null) Sighting.FOUND else Sighting.NONE, q, s, t)
            if (state.fire) return t
            t += 50
        }
        return -1
    }

    @Test fun firesAfterHoldingStill() {
        val t = CaptureTrigger(holdMs = 600)
        assertEquals(600, t.run(0, 2000, quad, sig(0f)))
    }

    @Test fun movingSheetNeverFires() {
        val t = CaptureTrigger(holdMs = 600)
        var time = 0L
        var fired = false
        repeat(60) {
            fired = fired || t.onFrame(Sighting.FOUND, shifted(it * 0.03f % 0.15f), sig(0f), time).fire
            time += 50
        }
        assertFalse(fired)
    }

    @Test fun samePageIsNotCapturedTwice() {
        val t = CaptureTrigger(holdMs = 600)
        assertTrue(t.run(0, 2000, quad, sig(0f)) > 0)
        assertEquals(Phase.CAPTURING, t.onFrame(Sighting.FOUND, quad, sig(0f), 700).phase)
        t.onCaptured(sig(0f))
        // dieselbe Seite bleibt liegen, die Hand wackelt etwas
        assertEquals(-1, t.run(800, 6000, shifted(0.01f), sig(0.005f)))
        assertEquals(Phase.WAIT_NEXT, t.onFrame(Sighting.FOUND, quad, sig(0f), 6050).phase)
    }

    @Test fun nextPageAfterSheetWasGone() {
        val t = CaptureTrigger(holdMs = 600)
        t.run(0, 2000, quad, sig(0f))
        t.onCaptured(sig(0f))
        assertEquals(-1, t.run(700, 1200, null, null))
        // neues Blatt mit gleichem Aussehen (z. B. gleiche Textverteilung) wird trotzdem erfasst
        assertEquals(1300 + 600, t.run(1300, 4000, quad, sig(0f)))
    }

    @Test fun nextPageWhenContentChanges() {
        val t = CaptureTrigger(holdMs = 600)
        t.run(0, 2000, quad, sig(0f))
        t.onCaptured(sig(0f))
        assertTrue(t.run(700, 4000, quad, sig(0.1f)) > 0)
    }

    @Test fun briefFlickerDoesNotRearm() {
        val t = CaptureTrigger(holdMs = 600, lostMs = 250)
        t.run(0, 2000, quad, sig(0f))
        t.onCaptured(sig(0f))
        t.onFrame(Sighting.NONE, null, null, 700) // ein einzelnes Bild ohne Blatt
        assertEquals(-1, t.run(750, 4000, quad, sig(0f)))
    }

    @Test fun failedCaptureIsRetried() {
        val t = CaptureTrigger(holdMs = 600)
        assertTrue(t.run(0, 2000, quad, sig(0f)) > 0)
        t.onCaptureFailed()
        assertTrue(t.run(700, 4000, quad, sig(0f)) > 0)
    }

    @Test fun resetAllowsSamePageAgain() {
        val t = CaptureTrigger(holdMs = 600)
        t.run(0, 2000, quad, sig(0f))
        t.onCaptured(sig(0f))
        t.reset()
        assertTrue(t.run(700, 4000, quad, sig(0f)) > 0)
    }
}
