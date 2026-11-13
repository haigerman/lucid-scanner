package io.github.haigerman.lucidscanner.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import io.github.haigerman.lucidscanner.data.ScanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

data class CameraUi(
    val phase: Phase = Phase.SEARCHING,
    /** Erkanntes Blatt, bezogen auf das Vorschaubild (0..1). */
    val quad: Quad? = null,
    /** Fortschritt der Haltezeit bis zur Aufnahme, 0..1. */
    val progress: Float = 0f,
    /** Zahl der bisher ausgelösten Aufnahmen. */
    val shots: Int = 0,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val error: String? = null,
)

/** Ein von Hand ausgelöstes Foto, das auf den Zuschnitt durch den Nutzer wartet. */
class ManualShot(
    val jpeg: ByteArray,
    val rotation: Int,
    val crop: Rect,
    /** Verkleinertes, aufrechtes Foto zum Anzeigen. */
    val preview: Bitmap,
    /** Vorschlag für den Zuschnitt: das erkannte Blatt oder ein Rahmen mit etwas Rand. */
    val suggestion: Quad,
    val signature: FloatArray?,
)

/**
 * Die Kamera des Scanners: Vorschau, laufende Blatterkennung und automatische Aufnahme.
 *
 * Vorschau, Analyse und Foto teilen sich denselben Bildausschnitt (ViewPort). Die erkannten Ecken
 * gelten deshalb unverändert für die Vorschau und für das Foto.
 */
class ScanCamera(private val context: Context) {
    private val _ui = MutableStateFlow(CameraUi())
    val ui: StateFlow<CameraUi> = _ui

    private val _manual = MutableStateFlow<ManualShot?>(null)

    /** Gesetzt, solange ein von Hand ausgelöstes Foto zugeschnitten wird. */
    val manual: StateFlow<ManualShot?> = _manual

    /** Analyse und Auslöse-Logik laufen ausschließlich auf diesem einen Thread. */
    private val executor = Executors.newSingleThreadExecutor()
    private val trigger = CaptureTrigger()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var yPlane = ByteArray(0)
    private var lastQuad: Quad? = null
    private var lastSignature: FloatArray? = null

    suspend fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        try {
            val provider = ProcessCameraProvider.awaitInstance(context)
            this.provider = provider

            val fourToThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourToThree).build())
                .build()
            preview.surfaceProvider = previewView.surfaceProvider
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourToThree).build())
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(fourToThree)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .build()
            analysis.setAnalyzer(executor, ::analyze)

            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val viewPort = ViewPort.Builder(Rational(previewView.width, previewView.height), rotation).build()
            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(capture)
                .addUseCase(analysis)
                .build()

            provider.unbindAll()
            val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            this.camera = camera
            imageCapture = capture
            _ui.update { it.copy(torchAvailable = camera.cameraInfo.hasFlashUnit(), error = null) }
        } catch (e: Exception) {
            _ui.update { it.copy(error = "Die Kamera lässt sich nicht starten (${e.message ?: e.javaClass.simpleName}).") }
        }
    }

    fun toggleTorch() {
        val on = !_ui.value.torchOn
        camera?.cameraControl?.enableTorch(on)
        _ui.update { it.copy(torchOn = on) }
    }

    /** Aufnahme von Hand: Das Foto geht nicht gleich in den Scan, sondern erst in den Zuschnitt ([manual]). */
    fun shootManually() {
        executor.execute {
            if (trigger.beginManual()) takePicture(lastQuad, lastSignature, manual = true)
        }
    }

    /** Schließt den Zuschnitt ab: mit [quad] wird die Seite übernommen, mit null verworfen. */
    fun finishManual(quad: Quad?) {
        val shot = _manual.value ?: return
        _manual.value = null
        if (quad != null) {
            ScanRepository.addCapture(shot.jpeg, shot.rotation, shot.crop, null, fixed = quad)
            _ui.update { it.copy(shots = it.shots + 1) }
        }
        executor.execute {
            if (quad != null) trigger.onCaptured(shot.signature, adoptNext = true) else trigger.onCaptureFailed()
        }
    }

    /** Die letzte Seite wurde entfernt: Sie darf gleich noch einmal erfasst werden. */
    fun pageRemoved() {
        executor.execute { trigger.reset() }
        _ui.update { it.copy(shots = maxOf(0, it.shots - 1)) }
    }

    fun close() {
        provider?.unbindAll()
        executor.shutdown()
    }

    // ------------------------------------------------------------------

    private fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val size = buffer.remaining()
            if (yPlane.size < size) yPlane = ByteArray(size)
            buffer.get(yPlane, 0, size)
            val crop = image.cropRect
            val luma = Luma.fromYPlane(
                yPlane, plane.rowStride, plane.pixelStride,
                crop.left, crop.top, crop.width(), crop.height(),
                image.imageInfo.rotationDegrees, 320,
            )
            val detection = DocumentDetector.detect(luma.lum, luma.width, luma.height)
            val quad = detection.quad
            val signature = quad?.let { DocumentDetector.signature(luma.lum, luma.width, luma.height, it) }
            lastQuad = quad
            lastSignature = signature

            val state = trigger.onFrame(detection.sighting, quad, signature, SystemClock.elapsedRealtime())
            _ui.update { it.copy(phase = state.phase, quad = quad, progress = state.progress) }
            if (state.fire) takePicture(quad, signature)
        } catch (_: Exception) {
            // ein einzelnes unlesbares Vorschaubild ist kein Grund, den Scan abzubrechen
        } finally {
            image.close()
        }
    }

    private fun takePicture(hint: Quad?, signature: FloatArray?, manual: Boolean = false) {
        val capture = imageCapture
        if (capture == null) {
            trigger.onCaptureFailed()
            return
        }
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpeg: ByteArray
                val rotation: Int
                val crop: Rect
                try {
                    val buffer = image.planes[0].buffer
                    jpeg = ByteArray(buffer.remaining())
                    buffer.get(jpeg)
                    rotation = image.imageInfo.rotationDegrees
                    crop = Rect(image.cropRect)
                } finally {
                    image.close()
                }
                if (manual) {
                    // Die Auslöse-Logik bleibt gesperrt, bis der Zuschnitt abgeschlossen ist (finishManual)
                    try {
                        val (preview, found) = PageProcessor.preview(jpeg, rotation, crop)
                        _manual.value = ManualShot(jpeg, rotation, crop, preview, found ?: hint ?: DEFAULT_CROP, signature)
                    } catch (_: Throwable) {
                        trigger.onCaptureFailed()
                    }
                    return
                }
                ScanRepository.addCapture(jpeg, rotation, crop, hint)
                trigger.onCaptured(signature)
                _ui.update { it.copy(shots = it.shots + 1, phase = Phase.WAIT_NEXT, progress = 0f) }
            }

            override fun onError(exception: ImageCaptureException) {
                trigger.onCaptureFailed()
            }
        })
    }

    private companion object {
        /** Zuschnitt-Vorschlag, wenn kein Blatt erkannt wurde: das ganze Bild mit etwas Rand. */
        val DEFAULT_CROP = Quad(Pt(0.08f, 0.08f), Pt(0.92f, 0.08f), Pt(0.92f, 0.92f), Pt(0.08f, 0.92f))
    }
}
