package io.github.haigerman.lucidscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.haigerman.lucidscanner.scan.ManualShot
import io.github.haigerman.lucidscanner.scan.Pt
import io.github.haigerman.lucidscanner.scan.Quad

/**
 * Zuschnitt von Hand nach einer selbst ausgelösten Aufnahme: Die vier Ecken lassen sich auf die
 * Ecken des Blatts ziehen. „Übernehmen“ macht daraus die Seite, „Verwerfen“ wirft das Foto weg.
 */
@Composable
fun CropEditor(shot: ManualShot, onAccept: (Quad) -> Unit, onDiscard: () -> Unit) {
    // Ecken in Bildkoordinaten 0..1: oben links, oben rechts, unten rechts, unten links
    var corners by remember(shot) { mutableStateOf(shot.suggestion.corners.map { Offset(it.x, it.y) }) }
    var active by remember { mutableIntStateOf(-1) }
    val image = remember(shot) { shot.preview.asImageBitmap() }

    BackHandler(onBack = onDiscard)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)) {
            Text("Zuschnitt", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("Zieh die Ecken auf die Ecken des Blatts", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(shot.preview.width.toFloat() / shot.preview.height)) {
                Image(image, contentDescription = "Aufgenommenes Foto", modifier = Modifier.fillMaxSize())
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(shot) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    // die Ecke greifen, die dem Finger am nächsten liegt
                                    active = corners.indices.minByOrNull { i ->
                                        (Offset(corners[i].x * size.width, corners[i].y * size.height) - start).getDistance()
                                    } ?: -1
                                },
                                onDragEnd = { active = -1 },
                                onDragCancel = { active = -1 },
                            ) { change, drag ->
                                change.consume()
                                val i = active
                                if (i >= 0) {
                                    corners = corners.toMutableList().also {
                                        it[i] = Offset(
                                            (it[i].x + drag.x / size.width).coerceIn(0f, 1f),
                                            (it[i].y + drag.y / size.height).coerceIn(0f, 1f),
                                        )
                                    }
                                }
                            }
                        },
                ) {
                    val pts = corners.map { Offset(it.x * size.width, it.y * size.height) }
                    val sheet = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                        close()
                    }
                    // alles außerhalb des Zuschnitts abdunkeln
                    val outside = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                        addPath(sheet)
                    }
                    drawPath(outside, Color.Black.copy(alpha = 0.55f))
                    drawPath(sheet, Sand, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
                    pts.forEachIndexed { i, p ->
                        val r = (if (i == active) 16 else 11).dp.toPx()
                        drawCircle(Sand.copy(alpha = 0.35f), r * 1.7f, p)
                        drawCircle(Sand, r, p)
                        drawCircle(OnSand, r * 0.4f, p)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlineButton("Verwerfen", color = Coral, onClick = onDiscard)
            Spacer(Modifier.width(12.dp))
            PrimaryButton("Übernehmen", Modifier.weight(1f)) {
                onAccept(Quad(pt(corners[0]), pt(corners[1]), pt(corners[2]), pt(corners[3])))
            }
        }
    }
}

private fun pt(o: Offset) = Pt(o.x, o.y)
