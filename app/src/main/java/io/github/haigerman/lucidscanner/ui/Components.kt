package io.github.haigerman.lucidscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Hintergrund aller Bildschirme: Verlauf von Nachtblau nach dunklem Petrol. */
@Composable
fun Backdrop(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, DeepTeal))),
    ) { content() }
}

@Composable
fun Header(title: String, subtitle: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

/** Runder Knopf mit einem Zeichen als Symbol (wie in den anderen Apps: keine Icon-Bibliothek). */
@Composable
fun SymbolButton(symbol: String, description: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 22.sp, color = color.copy(alpha = if (enabled) 1f else 0.3f))
    }
}

@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) Sand else Sand.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = OnSand, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun OutlineButton(label: String, modifier: Modifier = Modifier, color: Color = Sand, enabled: Boolean = true, onClick: () -> Unit) {
    val c = color.copy(alpha = if (enabled) 1f else 0.3f)
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, c.copy(alpha = c.alpha * 0.6f), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = c, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Sand,
    focusedLabelColor = Sand,
    cursorColor = Sand,
)
