package io.github.haigerman.lucidscanner

import android.graphics.BitmapFactory
import android.graphics.Color
import io.github.haigerman.lucidscanner.scan.PageProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Der ganze Weg vom Foto zur Seite mit echter Android-Grafik: Das Testfoto zeigt ein schräg
 * fotografiertes Blatt mit Schatten auf einem Holztisch, gespeichert quer wie vom Kamerasensor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageProcessorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun photo() = javaClass.getResourceAsStream("/photo-rot90.jpg")!!.readBytes()

    @Test fun photoBecomesCleanUprightPage() {
        val out = tmp.newFile("page.jpg")
        PageProcessor.process(photo(), 90, null, null, null, true, true, out)
        val page = BitmapFactory.decodeFile(out.path)
        File("build/test-output").mkdirs()
        out.copyTo(File("build/test-output/page.jpg"), overwrite = true)

        // Hochformat im DIN-Verhältnis
        assertEquals(1.4142f, page.height.toFloat() / page.width, 0.01f)
        assertTrue("Seite zu klein: ${page.width}", page.width > 600)

        fun at(u: Float, v: Float) = page.getPixel((u * page.width).toInt(), (v * page.height).toInt())
        fun brightness(c: Int) = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3

        // Ränder und Ecken: weißes Papier, kein Tisch, kein Schatten mehr
        for ((u, v) in listOf(0.02f to 0.02f, 0.98f to 0.02f, 0.98f to 0.98f, 0.02f to 0.98f, 0.5f to 0.03f, 0.03f to 0.5f, 0.6f to 0.5f)) {
            assertTrue("($u, $v) ist nicht weiß: ${brightness(at(u, v))}", brightness(at(u, v)) > 235)
        }
        // Überschrift oben links ist dunkel geblieben, das rote Feld unten rechts rot
        assertTrue(brightness(at(0.30f, 0.085f)) < 90)
        val red = at(0.77f, 0.877f)
        assertTrue(Color.red(red) > 140 && Color.green(red) < 90)
    }

    @Test fun withoutEnhancementColoursStayAsPhotographed() {
        val out = tmp.newFile("plain.jpg")
        PageProcessor.process(photo(), 90, null, null, null, true, false, out)
        val page = BitmapFactory.decodeFile(out.path)
        val c = page.getPixel(page.width / 2, (page.height * 0.03f).toInt())
        val v = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
        assertTrue("Papier unverändert erwartet: $v", v in 120..240)
    }

    @Test fun blackAndWhiteScanHasNoColourAndStrongContrast() {
        val out = tmp.newFile("bw.jpg")
        // „Aufhellen“ ist aus – SW-Seiten werden trotzdem aufgehellt
        PageProcessor.process(photo(), 90, null, null, null, false, false, out)
        val page = BitmapFactory.decodeFile(out.path)
        out.copyTo(File("build/test-output/page-bw.jpg"), overwrite = true)
        fun at(u: Float, v: Float) = page.getPixel((u * page.width).toInt(), (v * page.height).toInt())
        // das rote Feld ist grau geworden, Papier weiß, Überschrift schwarz
        val red = at(0.77f, 0.877f)
        assertTrue(kotlin.math.abs(Color.red(red) - Color.green(red)) < 12 && kotlin.math.abs(Color.red(red) - Color.blue(red)) < 12)
        assertTrue(Color.red(at(0.5f, 0.03f)) > 240)
        assertTrue(Color.red(at(0.30f, 0.085f)) < 40)
    }

    @Test fun manualCropIsUsedInsteadOfDetection() {
        val out = tmp.newFile("crop.jpg")
        // Zuschnitt von Hand: nur die Überschrift oben links (als breiter Streifen)
        val quad = io.github.haigerman.lucidscanner.scan.Quad(
            io.github.haigerman.lucidscanner.scan.Pt(0.30f, 0.26f), io.github.haigerman.lucidscanner.scan.Pt(0.62f, 0.24f),
            io.github.haigerman.lucidscanner.scan.Pt(0.62f, 0.31f), io.github.haigerman.lucidscanner.scan.Pt(0.30f, 0.33f),
        )
        PageProcessor.process(photo(), 90, null, null, quad, true, false, out)
        val page = BitmapFactory.decodeFile(out.path)
        assertTrue("Querformat erwartet: ${page.width} x ${page.height}", page.width > 2 * page.height)
    }

    @Test fun previewForManualCropFindsTheSheet() {
        val (bitmap, quad) = PageProcessor.preview(photo(), 90, null)
        assertTrue(bitmap.height > bitmap.width) // aufrecht
        // Ecken des Blatts im Testfoto (siehe Erzeugung): oben links bei etwa (0,27 | 0,27)
        assertEquals(0.27f, quad!!.tl.x, 0.02f)
        assertEquals(0.27f, quad.tl.y, 0.02f)
    }

    @Test fun dinRatioIsSnapped() {
        assertEquals(0.7071f, PageProcessor.pageRatio(0.74f), 0.001f)
        assertEquals(1.4142f, PageProcessor.pageRatio(1.38f), 0.001f)
        // ein quadratischer Zettel bleibt quadratisch, ein Kassenzettel schmal
        assertEquals(0.98f, PageProcessor.pageRatio(0.98f), 0.001f)
        assertEquals(0.32f, PageProcessor.pageRatio(0.32f), 0.001f)
    }
}
