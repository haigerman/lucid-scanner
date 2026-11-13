package io.github.haigerman.lucidscanner

import io.github.haigerman.lucidscanner.pdf.PdfWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class PdfWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun jpeg(name: String): File =
        tmp.newFile().also { f -> f.writeBytes(javaClass.getResourceAsStream("/$name.jpg")!!.readBytes()) }

    @Test fun readsJpegHeader() {
        val info = PdfWriter.readJpegInfo(jpeg("portrait"))
        assertEquals(500, info.width); assertEquals(707, info.height); assertEquals(3, info.components)
        assertEquals(1, PdfWriter.readJpegInfo(jpeg("gray")).components)
    }

    @Test fun writesConsistentPdf() {
        val pages = listOf(jpeg("portrait"), jpeg("landscape"), jpeg("square"))
        val out = ByteArrayOutputStream()
        PdfWriter.write(pages, out, "Prüfung (Scan)")
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))

        // Querverweistabelle: jeder Eintrag zeigt genau auf „n 0 obj“
        val xref = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref\n0 13\n", xref))
        val entries = text.substring(xref).lines().drop(3).take(12)
        entries.forEachIndexed { i, line ->
            val offset = line.substring(0, 10).toInt()
            assertTrue("Objekt ${i + 1}", text.startsWith("${i + 1} 0 obj\n", offset))
        }
        assertTrue(text.contains("/Count 3"))
        assertTrue(text.contains("/MediaBox [0 0 595.28 841.89]"))   // Hochformat → A4
        assertTrue(text.contains("/MediaBox [0 0 841.89 595.28]"))   // Querformat → A4 quer
        assertTrue(text.contains("/MediaBox [0 0 595.28 595.28]"))

        // Für die Prüfung mit pdfinfo außerhalb des Tests
        File("build/test-output").mkdirs()
        File("build/test-output/sample.pdf").writeBytes(bytes)
    }
}
