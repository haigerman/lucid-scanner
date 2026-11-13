package io.github.haigerman.lucidscanner

import io.github.haigerman.lucidscanner.scan.Luma
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LumaTest {
    // 3 breit, 2 hoch:  1 2 3
    //                   4 5 6
    private val data = byteArrayOf(1, 2, 3, 4, 5, 6)

    private fun rot(deg: Int) = Luma.fromYPlane(data, 3, 1, 0, 0, 3, 2, deg, 100)

    @Test fun rotation0() = assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), rot(0).lum)

    @Test fun rotation90Clockwise() {
        val r = rot(90)
        assertEquals(2, r.width); assertEquals(3, r.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), r.lum)
    }

    @Test fun rotation180() = assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), rot(180).lum)

    @Test fun rotation270Clockwise() = assertArrayEquals(intArrayOf(3, 6, 2, 5, 1, 4), rot(270).lum)

    @Test fun cropStrideAndAveraging() {
        // 4x4-Ausschnitt ab (2,1) aus einem 8 breiten Bild mit Zeilenlänge 10, auf 2x2 gemittelt
        val stride = 10
        val buf = ByteArray(stride * 6)
        for (y in 0 until 6) for (x in 0 until 8) buf[y * stride + x] = (if (x < 4) 10 else 50).toByte()
        val r = Luma.fromYPlane(buf, stride, 1, 2, 1, 4, 4, 0, 2)
        assertEquals(2, r.width); assertEquals(2, r.height)
        assertArrayEquals(intArrayOf(10, 50, 10, 50), r.lum)
    }
}
