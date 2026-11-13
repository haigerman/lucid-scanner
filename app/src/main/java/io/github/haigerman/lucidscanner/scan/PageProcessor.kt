package io.github.haigerman.lucidscanner.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Macht aus einem Kamerafoto eine fertige Seite: Blatt finden, entzerren, aufhellen, als JPEG speichern. */
object PageProcessor {

    /** Längste Kante einer fertigen Seite in Pixeln (A4 mit gut 230 dpi). */
    private const val MAX_PAGE_SIDE = 2700
    private const val JPEG_QUALITY = 85
    private const val EDGE_INSET = 0.006f

    /**
     * @param jpeg     Foto, wie es die Kamera liefert
     * @param rotation Grad im Uhrzeigersinn, um die das Foto gedreht werden muss, damit es aufrecht steht
     * @param crop     sichtbarer Ausschnitt im ungedrehten Foto (oder null für alles)
     * @param hint     Blattecken aus der Vorschau, bezogen auf den aufrechten Ausschnitt; wird benutzt,
     *                 wenn sich das Blatt im Foto selbst nicht finden lässt. Ohne beides wird das
     *                 ganze Foto zur Seite.
     */
    fun process(jpeg: ByteArray, rotation: Int, crop: Rect?, hint: Quad?, enhance: Boolean, out: File) {
        val upright = decodeUpright(jpeg, rotation, crop)
        val quad = findSheet(upright) ?: hint ?: Quad.FULL
        val page = warp(upright, quad)
        if (page !== upright) upright.recycle()
        if (enhance) {
            val px = IntArray(page.width * page.height)
            page.getPixels(px, 0, page.width, 0, 0, page.width, page.height)
            PageEnhancer.enhance(px, page.width, page.height)
            page.setPixels(px, 0, page.width, 0, 0, page.width, page.height)
        }
        out.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        page.recycle()
    }

    private fun decodeUpright(jpeg: ByteArray, rotation: Int, crop: Rect?): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4400) sample *= 2
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Foto lässt sich nicht lesen")
        val area = Rect(0, 0, raw.width, raw.height)
        if (crop != null) {
            val c = Rect(crop.left / sample, crop.top / sample, crop.right / sample, crop.bottom / sample)
            if (c.intersect(area) && c.width() > 16 && c.height() > 16) area.set(c)
        }
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val upright = Bitmap.createBitmap(raw, area.left, area.top, area.width(), area.height(), m, true)
        if (upright !== raw) raw.recycle()
        return upright
    }

    /** Sucht das Blatt im Foto selbst – genauer als die Ecken aus der kleinen Vorschau. */
    private fun findSheet(bitmap: Bitmap): Quad? {
        val scale = 480f / max(bitmap.width, bitmap.height)
        val w = max(16, (bitmap.width * scale).roundToInt())
        val h = max(16, (bitmap.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val luma = Luma.fromArgb(px, w, h)
        return DocumentDetector.detect(luma.lum, w, h).quad
    }

    private fun warp(src: Bitmap, quad: Quad): Bitmap {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val pts = floatArrayOf(
            quad.tl.x * sw, quad.tl.y * sh, quad.tr.x * sw, quad.tr.y * sh,
            quad.br.x * sw, quad.br.y * sh, quad.bl.x * sw, quad.bl.y * sh,
        )
        // Ecken eine Spur nach innen rücken, damit am Rand kein Streifen Tisch stehen bleibt
        val mx = (pts[0] + pts[2] + pts[4] + pts[6]) / 4
        val my = (pts[1] + pts[3] + pts[5] + pts[7]) / 4
        for (i in 0 until 4) {
            pts[2 * i] += (mx - pts[2 * i]) * EDGE_INSET
            pts[2 * i + 1] += (my - pts[2 * i + 1]) * EDGE_INSET
        }

        fun dist(a: Int, b: Int) = hypot(pts[2 * a] - pts[2 * b], pts[2 * a + 1] - pts[2 * b + 1])
        // schräg fotografierte Kanten erscheinen verkürzt → jeweils die längere nehmen
        val longW = max(dist(0, 1), dist(3, 2))
        val longH = max(dist(0, 3), dist(1, 2))
        if (longW < 8 || longH < 8) return src

        val ratio = pageRatio(Perspective.aspectRatio(pts, sw, sh))
        // die Variante wählen, die keine Bildschärfe verschenkt
        val w = max(longW, longH * ratio)
        val h = w / ratio
        val scale = min(1f, MAX_PAGE_SIDE / max(w, h))
        val ow = max(1, (w * scale).roundToInt())
        val oh = max(1, (h * scale).roundToInt())

        val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(pts, 0, dst, 0, 4)) return src
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /**
     * Liegt das Seitenverhältnis (Breite : Höhe) in der Nähe von DIN (1 : √2, also A4, A5 …),
     * wird es genau darauf gesetzt – so wird aus einem A4-Blatt auch genau eine A4-Seite.
     */
    internal fun pageRatio(ratio: Float): Float {
        val din = sqrt(2f)
        return when {
            abs(ratio - 1 / din) * din < 0.08f -> 1 / din
            abs(ratio - din) / din < 0.08f -> din
            else -> ratio.coerceIn(0.2f, 5f)
        }
    }
}
