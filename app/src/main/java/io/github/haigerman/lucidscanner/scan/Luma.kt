package io.github.haigerman.lucidscanner.scan

/** Kleines, aufrecht gedrehtes Helligkeitsbild. */
class LumaImage(val lum: IntArray, val width: Int, val height: Int)

object Luma {

    /**
     * Verkleinert den Ausschnitt [cropLeft, cropTop, cropW, cropH] einer Y-Ebene (Kamerabild) auf
     * höchstens [maxSide] Pixel Kantenlänge und dreht ihn dabei um [rotation] Grad im Uhrzeigersinn,
     * sodass das Ergebnis aufrecht steht.
     */
    fun fromYPlane(
        data: ByteArray, rowStride: Int, pixelStride: Int,
        cropLeft: Int, cropTop: Int, cropW: Int, cropH: Int,
        rotation: Int, maxSide: Int,
    ): LumaImage {
        val f = maxOf(1, (maxOf(cropW, cropH) + maxSide - 1) / maxSide)
        val sw = cropW / f
        val sh = cropH / f
        val swap = rotation == 90 || rotation == 270
        val ow = if (swap) sh else sw
        val oh = if (swap) sw else sh
        val out = IntArray(ow * oh)
        val area = f * f
        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                var acc = 0
                for (dy in 0 until f) {
                    var p = (cropTop + sy * f + dy) * rowStride + (cropLeft + sx * f) * pixelStride
                    for (dx in 0 until f) {
                        acc += data[p].toInt() and 0xFF
                        p += pixelStride
                    }
                }
                val ux: Int
                val uy: Int
                when (rotation) {
                    90 -> { ux = sh - 1 - sy; uy = sx }
                    180 -> { ux = sw - 1 - sx; uy = sh - 1 - sy }
                    270 -> { ux = sy; uy = sw - 1 - sx }
                    else -> { ux = sx; uy = sy }
                }
                out[uy * ow + ux] = acc / area
            }
        }
        return LumaImage(out, ow, oh)
    }

    /** Helligkeit aus ARGB-Pixeln. */
    fun fromArgb(pixels: IntArray, width: Int, height: Int): LumaImage {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val c = pixels[i]
            out[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return LumaImage(out, width, height)
    }
}
