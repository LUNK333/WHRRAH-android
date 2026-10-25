package com.lunk333.whrrah.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lunk333.whrrah.data.ScrubbableLog

/**
 * The M3 overlay canvas: represents the video frame area, with draggable
 * and resizable widgets bound to live data at [timeSeconds]. Geometry is
 * stored as fractions of the canvas (see OverlayWidget) so it doesn't
 * depend on screen size - same idea as desktop WHRRAH's layout model.
 */
@Composable
fun OverlayCanvas(
    widgets: List<OverlayWidget>,
    onWidgetsChange: (List<OverlayWidget>) -> Unit,
    log: ScrubbableLog?,
    timeSeconds: Double,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
    ) {
        val canvasWidthPx = constraints.maxWidth.toFloat()
        val canvasHeightPx = constraints.maxHeight.toFloat()

        widgets.forEach { widget ->
            key(widget.id) {
                val xDp = with(density) { (widget.xFrac * canvasWidthPx).toDp() }
                val yDp = with(density) { (widget.yFrac * canvasHeightPx).toDp() }
                val widthDp = with(density) { (widget.widthFrac * canvasWidthPx).toDp() }
                val heightDp = with(density) { (widget.heightFrac * canvasHeightPx).toDp() }

                Box(
                    modifier = Modifier
                        .offset(x = xDp, y = yDp)
                        .width(widthDp)
                        .height(heightDp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)))
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(widget.id, canvasWidthPx, canvasHeightPx) {
                                    detectDragGestures { _, dragAmount: Offset ->
                                        val dxFrac = dragAmount.x / canvasWidthPx
                                        val dyFrac = dragAmount.y / canvasHeightPx
                                        onWidgetsChange(
                                            widgets.map {
                                                if (it.id == widget.id) {
                                                    it.copy(
                                                        xFrac = (it.xFrac + dxFrac)
                                                            .coerceIn(0f, (1f - it.widthFrac).coerceAtLeast(0f)),
                                                        yFrac = (it.yFrac + dyFrac)
                                                            .coerceIn(0f, (1f - it.heightFrac).coerceAtLeast(0f))
                                                    )
                                                } else it
                                            }
                                        )
                                    }
                                }
                        ) {
                            Text(
                                text = widgetLabel(widget),
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            if (widget.type != WidgetType.LAP_TIMER) {
                                Text(
                                    text = "›",
                                    color = Color.Cyan,
                                    modifier = Modifier.padding(horizontal = 4.dp).pointerInput(widget.id) {
                                        detectTapGestures {
                                            val names = log?.channelNames.orEmpty()
                                            if (names.isNotEmpty()) {
                                                onWidgetsChange(
                                                    widgets.map {
                                                        if (it.id == widget.id) {
                                                            val currentIdx = names.indexOf(it.channel).let { i -> if (i < 0) -1 else i }
                                                            it.copy(channel = names[(currentIdx + 1) % names.size])
                                                        } else it
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                            Text(
                                text = "×",
                                color = Color.Red,
                                modifier = Modifier.pointerInput(widget.id) {
                                    detectTapGestures {
                                        onWidgetsChange(widgets.filterNot { it.id == widget.id })
                                    }
                                }
                            )
                        }

                        WidgetContent(widget = widget, log = log, timeSeconds = timeSeconds)
                    }

                    // Resize handle, bottom-end corner.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(16.dp)
                            .background(Color.White.copy(alpha = 0.8f))
                            .pointerInput(widget.id, canvasWidthPx, canvasHeightPx) {
                                detectDragGestures { _, dragAmount: Offset ->
                                    val dwFrac = dragAmount.x / canvasWidthPx
                                    val dhFrac = dragAmount.y / canvasHeightPx
                                    onWidgetsChange(
                                        widgets.map {
                                            if (it.id == widget.id) {
                                                it.copy(
                                                    widthFrac = (it.widthFrac + dwFrac)
                                                        .coerceIn(0.05f, (1f - it.xFrac).coerceAtLeast(0.05f)),
                                                    heightFrac = (it.heightFrac + dhFrac)
                                                        .coerceIn(0.05f, (1f - it.yFrac).coerceAtLeast(0.05f))
                                                )
                                            } else it
                                        }
                                    )
                                }
                            }
                    )
                }
            }
        }
    }
}

private fun widgetLabel(widget: OverlayWidget): String = when (widget.type) {
    WidgetType.LAP_TIMER -> "Lap Timer"
    WidgetType.NUMERIC_DISPLAY -> widget.channel ?: "(no channel)"
    WidgetType.BAR_GRAPH -> widget.channel ?: "(no channel)"
}

@Composable
private fun WidgetContent(widget: OverlayWidget, log: ScrubbableLog?, timeSeconds: Double) {
    when (widget.type) {
        WidgetType.LAP_TIMER -> {
            val lap = log?.lapAt(timeSeconds)
            Text(
                text = if (lap != null) {
                    "Lap ${lap.lapNum + 1}  ${formatLapTime(lap.elapsedSeconds)}"
                } else {
                    "--:--.---"
                },
                color = Color.White
            )
        }
        WidgetType.NUMERIC_DISPLAY -> {
            val channel = widget.channel
            val value = if (channel != null) log?.valueAt(channel, timeSeconds) else null
            val unit = if (channel != null) log?.unitOf(channel).orEmpty() else ""
            Text(
                text = if (value != null) "%.2f %s".format(value, unit) else "--",
                color = Color.White
            )
        }
        WidgetType.BAR_GRAPH -> {
            val channel = widget.channel
            val value = if (channel != null) log?.valueAt(channel, timeSeconds) else null
            val (min, max) = if (channel != null) log?.channelRange(channel) ?: (0.0 to 1.0) else 0.0 to 1.0
            val fraction = if (value != null && max > min) {
                ((value - min) / (max - min)).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(Color.DarkGray)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                        .background(Color.Green)
                )
            }
        }
    }
}

private fun formatLapTime(seconds: Double): String {
    val totalMs = (seconds.coerceAtLeast(0.0) * 1000).toLong()
    val m = totalMs / 60000
    val s = (totalMs % 60000) / 1000
    val ms = totalMs % 1000
    return "%d:%02d.%03d".format(m, s, ms)
}
