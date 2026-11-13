package io.github.haigerman.lucidscanner.pdf

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schreibt ein PDF, in dem jede Seite genau ein JPEG-Bild ist. Die JPEG-Daten werden unverändert
 * eingebettet (DCTDecode) – das hält die Datei klein und kostet keine Qualität.
 */
object PdfWriter {

    private const val A4_SHORT = 595.28
    private const val A4_LONG = 841.89

    class JpegInfo(val width: Int, val height: Int, val components: Int)

    fun write(pages: List<File>, out: OutputStream, title: String, created: Date = Date()) {
        require(pages.isNotEmpty()) { "Keine Seiten" }
        val w = Counting(out)
        val offsets = ArrayList<Long>()
        val objectCount = 3 + pages.size * 3

        fun obj(body: String) {
            offsets += w.count
            w.ascii("${offsets.size} 0 obj\n$body\nendobj\n")
        }

        w.ascii("%PDF-1.4\n")
        w.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        obj("<< /Type /Catalog /Pages 2 0 R >>")
        val kids = pages.indices.joinToString(" ") { "${4 + it * 3} 0 R" }
        obj("<< /Type /Pages /Count ${pages.size} /Kids [$kids] >>")
        val date = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(created)
        obj("<< /Title ${textString(title)} /Creator ${textString("Lucid Scanner")} /Producer ${textString("Lucid Scanner")} /CreationDate (D:$date) >>")

        pages.forEachIndexed { i, file ->
            val info = readJpegInfo(file)
            val pageObj = 4 + i * 3
            // Kurze Seite = A4-Breite; ein A4-Blatt ergibt damit genau eine A4-Seite.
            val pw: Double
            val ph: Double
            if (info.width <= info.height) {
                pw = A4_SHORT
                ph = snap(A4_SHORT * info.height / info.width)
            } else {
                ph = A4_SHORT
                pw = snap(A4_SHORT * info.width / info.height)
            }
            obj(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(pw)} ${num(ph)}] " +
                    "/Resources << /XObject << /Im0 ${pageObj + 2} 0 R >> >> /Contents ${pageObj + 1} 0 R >>"
            )
            val content = "q ${num(pw)} 0 0 ${num(ph)} 0 0 cm /Im0 Do Q"
            obj("<< /Length ${content.length} >>\nstream\n$content\nendstream")

            val colorSpace = when (info.components) {
                1 -> "/DeviceGray"
                4 -> "/DeviceCMYK"
                else -> "/DeviceRGB"
            }
            offsets += w.count
            w.ascii(
                "${offsets.size} 0 obj\n<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                    "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${file.length()} >>\nstream\n"
            )
            file.inputStream().use { it.copyTo(w) }
            w.ascii("\nendstream\nendobj\n")
        }

        check(offsets.size == objectCount)
        val xref = w.count
        w.ascii("xref\n0 ${objectCount + 1}\n0000000000 65535 f \n")
        for (o in offsets) w.ascii(String.format(Locale.US, "%010d 00000 n \n", o))
        w.ascii("trailer\n<< /Size ${objectCount + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    /** Liest Breite, Höhe und Farbkanäle aus dem JPEG-Kopf. */
    fun readJpegInfo(file: File): JpegInfo {
        file.inputStream().buffered().use { s ->
            fun u8(): Int = s.read().also { if (it < 0) throw IOException("JPEG unvollständig: ${file.name}") }
            fun u16(): Int = (u8() shl 8) or u8()
            if (u8() != 0xFF || u8() != 0xD8) throw IOException("Kein JPEG: ${file.name}")
            while (true) {
                var marker = u8()
                if (marker != 0xFF) continue
                while (marker == 0xFF) marker = u8()
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) continue
                val length = u16()
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isSof) {
                    u8() // Bittiefe
                    val height = u16()
                    val width = u16()
                    val components = u8()
                    if (width <= 0 || height <= 0) throw IOException("JPEG ohne Größe: ${file.name}")
                    return JpegInfo(width, height, components)
                }
                var skip = (length - 2).toLong()
                while (skip > 0) {
                    val n = s.skip(skip)
                    if (n <= 0) { u8(); skip-- } else skip -= n
                }
            }
        }
    }

    /** Rundungsfehler ausgleichen: fast A4 wird genau A4. */
    private fun snap(long: Double): Double = if (kotlin.math.abs(long - A4_LONG) < 3.0) A4_LONG else long

    private fun num(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** PDF-Textstring als UTF-16BE in Hex – damit sind Umlaute und Klammern unkritisch. */
    private fun textString(s: String): String {
        val sb = StringBuilder("<FEFF")
        for (b in s.toByteArray(Charsets.UTF_16BE)) sb.append(String.format("%02X", b.toInt() and 0xFF))
        return sb.append('>').toString()
    }

    private class Counting(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        fun ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
    }
}
