package io.github.haigerman.lucidscanner.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.haigerman.lucidscanner.data.HistoryEntry
import io.github.haigerman.lucidscanner.data.ScanRepository
import io.github.haigerman.lucidscanner.data.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(notice: String?, onNewScan: () -> Unit, onSettings: () -> Unit) {
    val config by Settings.config.collectAsStateWithLifecycle()
    val history by ScanRepository.history.collectAsStateWithLifecycle()

    Backdrop {
        Column(Modifier.fillMaxSize()) {
            Header(
                "Lucid Scanner", "Blatt für Blatt zum PDF in deiner OpenCloud",
                Modifier.statusBarsPadding(),
            ) { SymbolButton("⚙", "Einstellungen", onClick = onSettings) }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (notice != null) {
                    item {
                        Surface(color = CardOn, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("✓  $notice", fontSize = 15.sp, color = Mist, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                if (!config.complete) {
                    item {
                        Surface(
                            color = Card, shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(onClick = onSettings),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("OpenCloud einrichten", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        "Adresse, Benutzername, App-Token und Ordner fehlen noch",
                                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                if (history.isNotEmpty()) {
                    item {
                        Text(
                            "Zuletzt abgelegt", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                        )
                    }
                    items(history) { HistoryCard(it) }
                } else {
                    item {
                        Text(
                            "Auf den Knopf tippen, das Blatt ins Bild legen und still halten – die Aufnahme " +
                                "löst von selbst aus. Dann das nächste Blatt auflegen.",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                        )
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card.copy(alpha = 0.92f))
                    .navigationBarsPadding()
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScanButton(onNewScan)
                Spacer(Modifier.height(10.dp))
                Text("Neuer Scan", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (config.complete) "Ablage: ${config.destination}" else "OpenCloud ist noch nicht eingerichtet",
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (config.complete) MaterialTheme.colorScheme.onSurfaceVariant else Coral,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

/** Großer runder Knopf: ein Blatt mit Scan-Linie. */
@Composable
private fun ScanButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(Sand)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Neuer Scan" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(w * 0.07f, cap = StrokeCap.Round)
            drawRoundRect(OnSand, Offset(w * 0.2f, h * 0.06f), androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.88f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f), style = stroke)
            drawLine(OnSand, Offset(w * 0.04f, h * 0.5f), Offset(w * 0.96f, h * 0.5f), w * 0.09f, StrokeCap.Round)
        }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry) {
    Surface(color = Card.copy(alpha = 0.7f), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) { Text("📄", fontSize = 22.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val date = SimpleDateFormat("d. MMM, HH:mm", Locale.GERMAN).format(Date(entry.time))
                Text(
                    "${pages(entry.pages)} · $date · ${entry.destination}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

fun pages(n: Int): String = if (n == 1) "1 Seite" else "$n Seiten"
