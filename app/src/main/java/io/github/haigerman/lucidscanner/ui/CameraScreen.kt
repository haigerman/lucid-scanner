package io.github.haigerman.lucidscanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.haigerman.lucidscanner.data.ScanRepository
import io.github.haigerman.lucidscanner.scan.CameraUi
import io.github.haigerman.lucidscanner.scan.Phase
import io.github.haigerman.lucidscanner.scan.ScanCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Der Scan-Bildschirm: Kamera mit Blatterkennung. Liegt ein Blatt ruhig im Bild, wird von selbst
 * ausgelöst; „Fertig“ baut aus allen Seiten das PDF.
 */
@Composable
fun CameraScreen(onFinished: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Night)) {
        if (granted) {
            Scanner(onFinished, onCancel)
        } else {
            BackHandler(onBack = onCancel)
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text("📷", fontSize = 44.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Zum Scannen braucht Lucid Scanner die Kamera.",
                    fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                if (asked) {
                    // abgelehnt: Android fragt eventuell nicht noch einmal – dann hilft nur der Weg über die App-Einstellungen
                    PrimaryButton("App-Einstellungen öffnen") {
                        context.startActivity(
                            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlineButton(if (asked) "Noch einmal fragen" else "Kamera erlauben") { permission.launch(Manifest.permission.CAMERA) }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onCancel) { Text("Zurück", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Scanner(onFinished: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val camera = remember { ScanCamera(context.applicationContext) }
    val ui by camera.ui.collectAsStateWithLifecycle()
    val scan by ScanRepository.state.collectAsStateWithLifecycle()
    val manual by camera.manual.collectAsStateWithLifecycle()
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView: lässt sich sauber mit dem Blattumriss überlagern und abrunden
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var building by remember { mutableStateOf(false) }
    var buildError by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    val total = scan.pages.size + scan.processing

    LaunchedEffect(Unit) {
        while (previewView.width == 0 || previewView.height == 0) delay(16)
        camera.bind(owner, previewView)
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            camera.close()
        }
    }
    // Rückmeldung bei jeder Aufnahme: kurzes Aufblitzen und Vibration
    LaunchedEffect(ui.shots) {
        if (ui.shots > 0) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(350))
        }
    }

    fun cancel() {
        if (total > 0) confirmCancel = true else onCancel()
    }
    BackHandler { if (!building) cancel() }

    // Die Kamera läuft unter dem Zuschnitt weiter, damit es danach ohne Wartezeit weitergeht
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- Kopfzeile
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymbolButton("✕", "Scan abbrechen", enabled = !building) { cancel() }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    statusText(ui, total), fontSize = 15.sp, textAlign = TextAlign.Center,
                    color = if (ui.phase == Phase.HOLD || ui.phase == Phase.CAPTURING) Sand else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (ScanRepository.color) "Farbscan" else "SW-Scan", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.torchAvailable) {
                SymbolButton("🔦", if (ui.torchOn) "Licht aus" else "Licht an", color = Color.White.copy(alpha = if (ui.torchOn) 1f else 0.45f)) { camera.toggleTorch() }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        // ---------------------------------------------------------------- Vorschau mit erkanntem Blatt
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            SheetOverlay(ui, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
            ui.error?.let {
                Text(it, color = Coral, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            }
        }

        // ---------------------------------------------------------------- Bedienung
        Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val note = buildError ?: if (scan.failed > 0) "${scan.failed} Aufnahme(n) ließen sich nicht verarbeiten" else null
                if (note != null) {
                    Text(note, fontSize = 13.sp, color = Coral, modifier = Modifier.padding(bottom = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        LastPage(
                            file = scan.pages.lastOrNull()?.file, count = total, working = scan.processing > 0,
                            canRemove = scan.pages.isNotEmpty() && scan.processing == 0 && !building,
                            onRemove = {
                                ScanRepository.removeLastPage()
                                camera.pageRemoved()
                            },
                        )
                    }
                    Shutter(ui, enabled = !building && ui.error == null) { camera.shootManually() }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        PrimaryButton(if (building) "PDF …" else "Fertig", enabled = total > 0 && !building) {
                            building = true
                            buildError = null
                            scope.launch {
                                try {
                                    ScanRepository.buildPdf()
                                    onFinished()
                                } catch (e: Exception) {
                                    buildError = "Das PDF ließ sich nicht erstellen."
                                    building = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        manual?.let { shot ->
            Box(Modifier.fillMaxSize().background(Night)) {
                CropEditor(shot, onAccept = { camera.finishManual(it) }, onDiscard = { camera.finishManual(null) })
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            containerColor = Card,
            title = { Text("Scan abbrechen?") },
            text = { Text("${pages(total)} gehen dabei verloren.") },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; ScanRepository.discard(); onCancel() }) { Text("Abbrechen", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Weiter scannen", color = Sand) } },
        )
    }
}

private fun statusText(ui: CameraUi, total: Int): String = when (ui.phase) {
    Phase.SEARCHING -> if (total == 0) "Blatt ins Bild legen" else "Nächstes Blatt ins Bild legen"
    Phase.PARTIAL -> "Das Blatt muss ganz im Bild sein"
    Phase.HOLD -> "Still halten …"
    Phase.CAPTURING -> "Aufnahme …"
    Phase.WAIT_NEXT -> "Seite $total erfasst – nächstes Blatt auflegen"
}

/** Zeichnet den Umriss des erkannten Blatts über die Vorschau. */
@Composable
private fun SheetOverlay(ui: CameraUi, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val q = ui.quad ?: return@Canvas
        val path = Path().apply {
            moveTo(q.tl.x * size.width, q.tl.y * size.height)
            lineTo(q.tr.x * size.width, q.tr.y * size.height)
            lineTo(q.br.x * size.width, q.br.y * size.height)
            lineTo(q.bl.x * size.width, q.bl.y * size.height)
            close()
        }
        val waiting = ui.phase == Phase.WAIT_NEXT
        val color = if (waiting) Mist else Sand
        drawPath(path, color.copy(alpha = if (waiting) 0.10f else 0.10f + 0.18f * ui.progress))
        drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (c in q.corners) drawCircle(color, 5.dp.toPx(), Offset(c.x * size.width, c.y * size.height))
    }
}

/** Auslöser: füllt sich, während das Blatt still liegt; Antippen löst von Hand aus und führt zum Zuschnitt. */
@Composable
private fun Shutter(ui: CameraUi, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Jetzt aufnehmen" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (ui.progress > 0f) {
                drawArc(Sand, -90f, 360f * ui.progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            drawCircle(Color.White.copy(alpha = if (enabled) 0.9f else 0.3f), size.minDimension / 2 - 9.dp.toPx())
        }
    }
}

/** Kleines Bild der letzten Seite mit Seitenzahl; ↶ nimmt die letzte Seite wieder heraus. */
@Composable
private fun LastPage(file: File?, count: Int, working: Boolean, canRemove: Boolean, onRemove: () -> Unit) {
    if (count == 0) return
    val thumb by produceState<Bitmap?>(null, file) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 }) }.getOrNull()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 44.dp, height = 58.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (working) CircularProgressIndicator(color = Sand, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(start = 34.dp, bottom = 46.dp).size(22.dp).clip(CircleShape).background(Sand),
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OnSand)
            }
        }
        SymbolButton("↶", "Letzte Seite entfernen", enabled = canRemove, onClick = onRemove)
    }
}
