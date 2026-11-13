package io.github.haigerman.lucidscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.haigerman.lucidscanner.cloud.OpenCloudClient
import io.github.haigerman.lucidscanner.data.Settings
import io.github.haigerman.lucidscanner.data.folderLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ordnerbrowser für den persönlichen Bereich der OpenCloud: durch die Ordner blättern, einen
 * neuen Ordner anlegen und den gerade geöffneten Ordner mit [confirmLabel] auswählen.
 */
@Composable
fun FolderBrowser(title: String, confirmLabel: String, start: String = "", onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember {
        val c = Settings.config.value
        runCatching { OpenCloudClient(c.serverUrl, c.user, c.token) }.getOrNull()
    }
    // Pfad des geöffneten Ordners als Liste seiner Teile; leer = oberste Ebene
    var path by remember { mutableStateOf(OpenCloudClient.splitFolder(start)) }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(path, reload) {
        folders = null
        error = null
        try {
            val c = client ?: throw IllegalStateException("OpenCloud ist noch nicht eingerichtet.")
            folders = withContext(Dispatchers.IO) { c.listFolders(path.joinToString("/")) }
        } catch (e: Exception) {
            error = e.message ?: "Die Ordner ließen sich nicht laden."
        }
    }

    fun up() {
        if (path.isEmpty()) onDismiss() else path = path.dropLast(1)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { up() }
        Backdrop {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SymbolButton("‹", if (path.isEmpty()) "Schließen" else "Eine Ebene höher") { up() }
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            path.lastOrNull() ?: "Persönlicher Bereich", fontSize = 22.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    SymbolButton("✕", "Schließen", onClick = onDismiss)
                }
                if (path.size > 1) {
                    Text(
                        folderLabel(path.dropLast(1).joinToString("/")), fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.StartEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val list = folders
                    when {
                        error != null -> Column(
                            Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(error.orEmpty(), fontSize = 15.sp, color = Coral, textAlign = TextAlign.Center)
                            Spacer(Modifier.size(16.dp))
                            OutlineButton("Erneut versuchen") { reload++ }
                        }
                        list == null -> CircularProgressIndicator(color = Sand, modifier = Modifier.align(Alignment.Center))
                        else -> LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(list) { name -> FolderRow("📁", name) { path = path + name } }
                            if (list.isEmpty()) {
                                item {
                                    Text(
                                        "Hier gibt es noch keine Unterordner.", fontSize = 14.sp, textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Card.copy(alpha = 0.92f))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlineButton("＋ Neuer Ordner", enabled = folders != null && !creating) { newFolder = true }
                    Spacer(Modifier.width(12.dp))
                    PrimaryButton(confirmLabel, Modifier.weight(1f), enabled = folders != null && !creating) {
                        onPick(path.joinToString("/"))
                    }
                }
            }
        }
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        val clean = OpenCloudClient.splitFolder(name.replace('/', '-')).firstOrNull().orEmpty()
        AlertDialog(
            onDismissRequest = { newFolder = false },
            containerColor = Card,
            title = { Text("Neuer Ordner") },
            text = {
                Column {
                    Text("in ${folderLabel(path.joinToString("/"))}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(10.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        colors = fieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = clean.isNotEmpty(),
                    onClick = {
                        newFolder = false
                        creating = true
                        val target = path + clean
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { checkNotNull(client).createFolder(target.joinToString("/")) }
                                path = target // gleich hineinwechseln
                            } catch (e: Exception) {
                                error = e.message ?: "Der Ordner ließ sich nicht anlegen."
                            }
                            creating = false
                        }
                    },
                ) { Text("Anlegen", color = if (clean.isNotEmpty()) Sand else Sand.copy(alpha = 0.3f)) }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Abbrechen", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}

/** Eine Zeile mit Symbol und Text – für Ordner und Favoriten. */
@Composable
fun FolderRow(symbol: String, label: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Surface(
        color = Card.copy(alpha = 0.7f), shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) { Text(symbol, fontSize = 20.sp) }
            Spacer(Modifier.width(14.dp))
            Text(
                label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
            )
            if (trailing != null) trailing() else if (onClick != null) {
                Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}
