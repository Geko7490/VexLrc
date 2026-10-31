package com.vexlrc.generator.core.waveform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

data class WaveformMarker(val id: Int, val timeMs: Long)

@Composable
fun WaveformEditor(
    peaks: List<Float>,
    durationMs: Long,
    playheadMs: Long,
    markers: List<WaveformMarker>,
    onSeek: (Long) -> Unit,
    onMoveMarker: (Int, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val latestMarkers by rememberUpdatedState(markers)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnMoveMarker by rememberUpdatedState(onMoveMarker)
    val duration = durationMs.coerceAtLeast(1L)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Waveform", color = Color.White)
            Spacer(Modifier.weight(1f))
            Text("${zoom.toInt()}×", color = Color(0xFFB9A6CE))
        }
        Canvas(
            Modifier.fillMaxWidth().height(116.dp).pointerInput(duration, peaks.size, zoom) {
                var selectedMarker: Int? = null
                detectDragGestures(
                    onDragStart = { point ->
                        val visibleDuration = (duration / zoom).toLong().coerceAtLeast(1L)
                        val windowStart = (playheadMs - visibleDuration / 2L).coerceIn(0L, (duration - visibleDuration).coerceAtLeast(0L))
                        val touchedTime = windowStart + (point.x / size.width.coerceAtLeast(1f) * visibleDuration).toLong()
                        val closest = latestMarkers.value.minByOrNull { abs(it.timeMs - touchedTime) }
                        selectedMarker = closest?.takeIf {
                            abs(it.timeMs - touchedTime) <= (visibleDuration * 18f / size.width.coerceAtLeast(1f)).toLong().coerceAtLeast(150L)
                        }?.id
                    },
                    onDrag = { change, _ ->
                        val visibleDuration = (duration / zoom).toLong().coerceAtLeast(1L)
                        val windowStart = (playheadMs - visibleDuration / 2L).coerceIn(0L, (duration - visibleDuration).coerceAtLeast(0L))
                        val ratio = (change.position.x / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val time = (windowStart + ratio * visibleDuration).toLong().coerceIn(0L, duration)
                        val markerId = selectedMarker
                        if (markerId != null) latestOnMoveMarker(markerId, time) else latestOnSeek(time)
                        change.consume()
                    },
                    onDragEnd = { selectedMarker = null },
                    onDragCancel = { selectedMarker = null }
                )
            }
        ) {
            val centerY = size.height / 2f
            val visibleDuration = (duration / zoom).toLong().coerceAtLeast(1L)
            val windowStart = (playheadMs - visibleDuration / 2L).coerceIn(0L, (duration - visibleDuration).coerceAtLeast(0L))
            val windowEnd = windowStart + visibleDuration
            drawLine(Color(0xFF51475E), Offset(0f, centerY), Offset(size.width, centerY), 1.dp.toPx())

            if (peaks.isNotEmpty()) {
                val first = ((windowStart.toDouble() / duration) * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
                val last = ((windowEnd.toDouble() / duration) * peaks.size).toInt().coerceIn(first + 1, peaks.size)
                val visible = peaks.subList(first, last)
                val step = size.width / visible.size.coerceAtLeast(1)
                visible.forEachIndexed { index, peak ->
                    val barHeight = max(2.dp.toPx(), peak.coerceIn(0f, 1f) * size.height * 0.88f)
                    val x = (index + 0.5f) * step
                    drawLine(Color(0xFFB77AF5), Offset(x, centerY - barHeight / 2f), Offset(x, centerY + barHeight / 2f), strokeWidth = max(1.dp.toPx(), step * 0.62f), cap = StrokeCap.Round)
                }
            } else {
                drawLine(Color(0xFF806A96), Offset(0f, centerY), Offset(size.width, centerY), 2.dp.toPx())
            }

            latestMarkers.value.forEach { marker ->
                if (marker.timeMs in windowStart..windowEnd) {
                    val x = ((marker.timeMs - windowStart).toFloat() / visibleDuration) * size.width
                    drawLine(Color(0xFFFFB45C), Offset(x, 4.dp.toPx()), Offset(x, size.height - 4.dp.toPx()), 1.dp.toPx())
                    drawCircle(Color(0xFFFFB45C), 4.dp.toPx(), Offset(x, 7.dp.toPx()))
                }
            }
            val playX = ((playheadMs.coerceIn(windowStart, windowEnd) - windowStart).toFloat() / visibleDuration) * size.width
            drawLine(Color.White, Offset(playX, 0f), Offset(playX, size.height), 2.dp.toPx())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zoom", color = Color(0xFFB9A6CE))
            Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..6f)
        }
        Text("Sarı çizgiler söz başlangıçlarıdır. Çizgiyi sürükleyerek zamanını değiştir; boş alana sürükleyerek oynatma konumunu taşı.", color = Color(0xFFAAA5B4))
    }
}
