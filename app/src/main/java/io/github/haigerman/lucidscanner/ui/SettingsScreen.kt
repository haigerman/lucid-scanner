package io.github.haigerman.lucidscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.haigerman.lucidscanner.cloud.OpenCloudClient
import io.github.haigerman.lucidscanner.data.Config
import io.github.haigerman.lucidscanner.data.Settings
import io.github.haigerman.lucidscanner.data.folderLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    // Eingaben erst lokal halten und sofort mitspeichern – so springt beim Tippen nichts
    var config by remember { mutableStateOf(Settings.config.value) }
    fun change(new: Config) {
        config = new
        Settings.update(new)
    }
    val scope = rememberCoroutineScope()
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    // Ergebnis der Verbindungsprüfung: Text und ob es geklappt hat
    var testResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    var browseFavorite by remember { mutableStateOf(false) }
    var typeFavorite by remember { mutableStateOf(false) }
    fun addFavorite(folder: String) {
        val clean = OpenCloudClient.normalizeFolder(folder)
        if (clean !in config.favorites) change(config.copy(favorites = config.favorites + clean))
    }

    BackHandler(onBack = onBack)

    Backdrop {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.statusBarsPadding().padding(start = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SymbolButton("‹", "Zurück", onClick = onBack)
                Text("Einstellungen", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Section("OpenCloud")
                Surface(color = Card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = config.serverUrl,
                            onValueChange = { change(config.copy(serverUrl = it.trim())); testResult = null },
                            label = { Text("Adresse") },
                            placeholder = { Text("cloud.example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = config.user,
                            onValueChange = { change(config.copy(user = it.trim())); testResult = null },
                            label = { Text("Benutzername") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
                                autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None,
                            ),
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = config.token,
                            onValueChange = { change(config.copy(token = it.trim())); testResult = null },
                            label = { Text("App-Token") },
                            singleLine = true,
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                            trailingIcon = {
                                SymbolButton(if (showToken) "🙈" else "👁", if (showToken) "Token verbergen" else "Token anzeigen") { showToken = !showToken }
                            },
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Hint("Den Token erzeugst du in OpenCloud unter Einstellungen → App-Tokens. Er steht zusammen mit dem Benutzernamen für die Anmeldung und wird auf dem Gerät verschlüsselt gespeichert.")
                        Spacer(Modifier.height(14.dp))
                        OutlineButton(
                            if (testing) "Prüfe …" else "Verbindung prüfen",
                            Modifier.fillMaxWidth(),
                            enabled = config.complete && !testing,
                        ) {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = try {
                                    withContext(Dispatchers.IO) {
                                        OpenCloudClient(config.serverUrl, config.user, config.token).testConnection()
                                    } to true
                                } catch (e: Exception) {
                                    (e.message ?: "Verbindung fehlgeschlagen.") to false
                                }
                                testing = false
                            }
                        }
                        testResult?.let { (text, ok) ->
                            Text(
                                (if (ok) "✓  " else "") + text, fontSize = 14.sp, color = if (ok) Mist else Coral,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }

                Section("Ordner-Favoriten")
                Surface(color = Card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Beim Ablegen eines Scans wählst du einen dieser Ordner. Die Pfade gelten im persönlichen Bereich; fehlende Ordner werden angelegt.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (folder in config.favorites) {
                            FolderRow("⭐", folderLabel(folder), trailing = {
                                SymbolButton("✕", "Favorit entfernen") { change(config.copy(favorites = config.favorites - folder)) }
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlineButton("＋ Ordner wählen", Modifier.weight(1f), enabled = config.complete) { browseFavorite = true }
                            Spacer(Modifier.width(10.dp))
                            OutlineButton("Pfad eingeben", Modifier.weight(1f)) { typeFavorite = true }
                        }
                    }
                }

                Section("Scan")
                Surface(color = Card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Seiten aufhellen", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Gleicht Schatten aus und macht das Papier weiß. Für Fotos und farbige Vorlagen besser ausschalten.",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = config.enhance,
                            onCheckedChange = { change(config.copy(enhance = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = OnSand, checkedTrackColor = Sand),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (browseFavorite) {
        FolderBrowser(
            "Favorit hinzufügen", "Als Favorit merken",
            onPick = { addFavorite(it); browseFavorite = false },
            onDismiss = { browseFavorite = false },
        )
    }
    if (typeFavorite) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typeFavorite = false },
            containerColor = Card,
            title = { Text("Favorit hinzufügen") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("Ordnerpfad") },
                    placeholder = { Text("Dokumente/Scans") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    colors = fieldColors(),
                )
            },
            confirmButton = {
                TextButton(onClick = { addFavorite(text); typeFavorite = false }) { Text("Hinzufügen", color = Sand) }
            },
            dismissButton = {
                TextButton(onClick = { typeFavorite = false }) { Text("Abbrechen", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}
