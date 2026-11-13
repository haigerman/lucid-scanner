package io.github.haigerman.lucidscanner.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Zeigt das fertige PDF so, wie es abgelegt wird: Die Seiten werden aus der PDF-Datei selbst gezeichnet. */
class PdfPreview(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)

    // PdfRenderer kann immer nur eine Seite gleichzeitig öffnen
    private val lock = Mutex()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Seitenverhältnis Breite : Höhe jeder Seite. */
    val aspectRatios: List<Float> = (0 until pageCount).map { i ->
        renderer.openPage(i).use { it.width.toFloat() / max(1, it.height) }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val height = max(1, (widthPx.toFloat() * page.height / page.width).roundToInt())
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    override fun close() {
        // nicht mitten in einer Zeichnung schließen
        while (!lock.tryLock()) Thread.sleep(5)
        try {
            if (!closed) {
                closed = true
                renderer.close()
                descriptor.close()
            }
        } finally {
            lock.unlock()
        }
    }
}
