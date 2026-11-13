package io.github.haigerman.lucidscanner.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.haigerman.lucidscanner.data.PdfResult
import io.github.haigerman.lucidscanner.data.ScanRepository
import io.github.haigerman.lucidscanner.data.Settings
import io.github.haigerman.lucidscanner.data.folderLabel
import kotlinx.coroutines.launch
import java.util.Locale

/** Kontrollansicht des fertigen PDFs: verwerfen oder in OpenCloud ablegen. */
@Composable
fun ReviewScreen(result: PdfResult, onUploaded: (String) -> Unit, onDiscarded: () -> Unit, onSettings: () -> Unit) {
    val config by Settings.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(result.defaultName) { mutableStateOf(result.defaultName) }
    var uploading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Zielauswahl: erst die Favoriten, von dort weiter in den Ordnerbrowser
    var chooseTarget by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }

    fun upload(folder: String) {
        chooseTarget = false
        browse = false
        target = folder
        uploading = true
        progress = 0f
        error = null
        scope.launch {
            try {
                onUploaded(ScanRepository.upload(name, folder) { progress = it })
            } catch (e: Exception) {
                error = e.message ?: "Ablegen fehlgeschlagen."
                uploading = false
            }
        }
    }

    val preview = remember(result.file) { runCatching { PdfPreview(result.file) }.getOrNull() }
    DisposableEffect(preview) { onDispose { preview?.close() } }

    BackHandler { if (!uploading) confirmDiscard = true }

    Backdrop {
        Column(Modifier.fillMaxSize().imePadding()) {
            Header(
                "Kontrolle",
                "${pages(result.pageCount)} · ${fileSize(result.file.length())}",
                Modifier.statusBarsPadding(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("Name der Datei") },
                suffix = { Text(".pdf") },
                singleLine = true,
                enabled = !uploading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (preview == null) {
                    item { Text("Die Vorschau lässt sich nicht anzeigen.", color = Coral, fontSize = 14.sp) }
                } else {
                    items(preview.pageCount) { index -> PdfPage(preview, index) }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card.copy(alpha = 0.92f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                when {
                    uploading -> {
                        Text("Wird abgelegt in ${folderLabel(target)} …", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            color = Sand, trackColor = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    error != null -> Text(error.orEmpty(), fontSize = 13.sp, color = Coral)
                    config.complete -> Text(
                        "Ablage: ${config.host} – den Ordner wählst du gleich", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text("OpenCloud ist noch nicht eingerichtet.", fontSize = 13.sp, color = Coral)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlineButton("Verwerfen", color = Coral, enabled = !uploading) { confirmDiscard = true }
                    Spacer(Modifier.width(12.dp))
                    if (config.complete) {
                        PrimaryButton(
                            if (error != null) "Erneut versuchen" else "In OpenCloud ablegen",
                            Modifier.weight(1f), enabled = !uploading,
                        ) { chooseTarget = true }
                    } else {
                        PrimaryButton("OpenCloud einrichten", Modifier.weight(1f), onClick = onSettings)
                    }
                }
            }
        }
    }

    if (chooseTarget) {
        TargetSheet(config.favorites, onPick = ::upload, onBrowse = { chooseTarget = false; browse = true }, onDismiss = { chooseTarget = false })
    }
    if (browse) {
        FolderBrowser("Ablegen in …", "Hier ablegen", onPick = ::upload, onDismiss = { browse = false; chooseTarget = true })
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = Card,
            title = { Text("Scan verwerfen?") },
            text = { Text("${pages(result.pageCount)} – das PDF wird gelöscht und nicht abgelegt.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; ScanRepository.discard(); onDiscarded() }) { Text("Verwerfen", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Behalten", color = Sand) } },
        )
    }
}

@Composable
private fun PdfPage(preview: PdfPreview, index: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(preview.aspectRatios[index])
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White),
        ) {
            val widthPx = constraints.maxWidth.coerceIn(64, 1440)
            val bitmap by produceState<Bitmap?>(null, preview, index, widthPx) {
                value = runCatching { preview.render(index, widthPx) }.getOrNull()
            }
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = "Seite ${index + 1}", modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            "Seite ${index + 1} von ${preview.pageCount}", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun fileSize(bytes: Long): String =
    if (bytes < 1_000_000) "${(bytes + 500) / 1000} kB" else String.format(Locale.GERMAN, "%.1f MB", bytes / 1_000_000.0)

/** Fragt nach dem Zielordner: die Favoriten aus den Einstellungen oder „Anderer Ordner …“. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSheet(favorites: List<String>, onPick: (String) -> Unit, onBrowse: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Card) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Wohin ablegen?", fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            for (folder in favorites) {
                FolderRow("⭐", folderLabel(folder)) { onPick(folder) }
            }
            if (favorites.isEmpty()) {
                Text(
                    "Noch keine Favoriten – du kannst sie in den Einstellungen anlegen.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            FolderRow("📁", "Anderer Ordner …", onClick = onBrowse)
        }
    }
}
