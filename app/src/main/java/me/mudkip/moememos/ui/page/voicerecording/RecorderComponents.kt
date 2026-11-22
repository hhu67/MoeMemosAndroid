package me.mudkip.moememos.ui.page.voicerecording

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.mudkip.moememos.R
import me.mudkip.moememos.ext.string
import kotlin.math.PI
import kotlin.math.sin

/** Colors of the recorder screens. They follow the dark Google Recorder look in both themes. */
object RecorderColors {
    val Background = Color(0xFF131313)
    val Card = Color(0xFF1E1E1E)
    val Pill = Color(0xFF2E2E2E)
    val PillSelected = Color(0xFFC6C6C6)
    val Bar = Color(0xFFFFFFFF)
    val BarPlayed = Color(0xFFFFFFFF)
    val BarUpcoming = Color(0xFF757575)
    val TrackInactive = Color(0xFF424242)
    val OnBackground = Color(0xFFE6E1E5)
    val Muted = Color(0xFF9E9E9E)
    val LightButton = Color(0xFFFFFFFF)
    val OnLightButton = Color(0xFF1C1B1F)
    val StopButton = Color(0xFFF2B8B5)
    val OnStopButton = Color(0xFF601410)
    val DarkButton = Color(0xFF262626)
    val RecordingDot = Color(0xFFE25555)
}

enum class RecorderTab { WAVEFORM, TRANSCRIPT }

/** Recorder screens are always dark, so system bar icons must be light while they are shown. */
@Composable
fun RecorderSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            lightStatus?.let { controller.isAppearanceLightStatusBars = it }
            lightNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

/** The big rounded card with the "Speech" label at the top. */
@Composable
fun RecorderCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(RecorderColors.Card)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ChatBubble,
                contentDescription = null,
                tint = RecorderColors.OnBackground,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(R.string.speech.string, color = RecorderColors.OnBackground, fontSize = 16.sp)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 72.dp, bottom = 16.dp),
            content = content
        )
    }
}

/** Two-segment pill: waveform icon on the left, transcript icon on the right. */
@Composable
fun RecorderTabSwitch(selected: RecorderTab, onSelect: (RecorderTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(RecorderColors.Pill)
            .padding(2.dp)
    ) {
        TabSegment(
            selected = selected == RecorderTab.WAVEFORM,
            label = R.string.waveform.string,
            onClick = { onSelect(RecorderTab.WAVEFORM) }
        ) { tint -> Icon(Icons.Filled.GraphicEq, null, tint = tint, modifier = Modifier.size(22.dp)) }
        TabSegment(
            selected = selected == RecorderTab.TRANSCRIPT,
            label = R.string.transcription.string,
            onClick = { onSelect(RecorderTab.TRANSCRIPT) }
        ) { tint -> Icon(Icons.AutoMirrored.Filled.Notes, null, tint = tint, modifier = Modifier.size(22.dp)) }
    }
}

@Composable
private fun TabSegment(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 58.dp, height = 52.dp)
            .clip(CircleShape)
            .background(if (selected) RecorderColors.PillSelected else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) {
        icon(if (selected) RecorderColors.OnLightButton else RecorderColors.OnBackground)
    }
}

private val BarWidth = 3.5.dp
private val BarGap = 4.5.dp
private val DotSize = 3.5.dp

/**
 * Live recording waveform. The newest sample is near the right edge and scrolls smoothly left.
 * Continuous silence dots baseline spans across the card with liquid 60/120fps motion.
 */
@Composable
fun LiveWaveform(
    amplitudes: List<Float>,
    isRecording: Boolean = true,
    modifier: Modifier = Modifier
) {
    var lastSampleTime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(amplitudes.size) {
        lastSampleTime = SystemClock.elapsedRealtime()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "live_smooth")
    val ticker by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ticker"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(240.dp)) {
        // Ticker read forces Compose to redraw on every frame
        @Suppress("UNUSED_VARIABLE")
        val unused = ticker

        val step = (BarWidth + BarGap).toPx()
        val barWidth = BarWidth.toPx()
        val maxBar = size.height * 0.75f
        val centerY = size.height / 2

        // Smooth pixel-by-pixel shift between discrete sample updates
        val now = SystemClock.elapsedRealtime()
        val subSample = if (isRecording) {
            ((now - lastSampleTime).toFloat() / 50f).coerceIn(0f, 1f)
        } else {
            0f
        }
        val smoothShift = subSample * step

        val rightEdgeX = size.width - 24.dp.toPx() - smoothShift

        // 1. Draw silence dots baseline across the screen
        var baseDotX = (rightEdgeX % step + step) % step
        while (baseDotX < size.width) {
            drawCircle(RecorderColors.BarUpcoming, radius = DotSize.toPx() / 2, center = Offset(baseDotX, centerY))
            baseDotX += step
        }

        // 2. Draw audio bars from right to left
        val lastIdx = amplitudes.lastIndex
        if (lastIdx >= 0) {
            for (i in lastIdx downTo 0) {
                val x = rightEdgeX - (lastIdx - i) * step
                if (x < -step) break
                val amp = amplitudes[i]
                drawBar(x, centerY, amp, maxBar, barWidth, RecorderColors.Bar)
            }
        }
    }
}

/**
 * Playback waveform: the whole recording, with a fixed playhead in the middle and the waveform
 * scrolling under it. Bars left of the playhead (already played) are brighter.
 * Direct horizontal drag gestures allow scrolling / rewinding smoothly across time.
 */
@Composable
fun PlaybackWaveform(
    amplitudes: List<Float>,
    progress: Float,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val step = BarWidth + BarGap
    val barWidth = BarWidth

    var accumulatedDeltaMs by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .pointerInput(amplitudes, durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        onScrubbing(true)
                        accumulatedDeltaMs = progress * durationMs
                    },
                    onDragEnd = {
                        onScrubbing(false)
                    },
                    onDragCancel = {
                        onScrubbing(false)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (durationMs > 0 && amplitudes.isNotEmpty()) {
                            val stepPx = step.toPx()
                            val sliceMs = durationMs.toFloat() / amplitudes.size
                            val pxPerMs = stepPx / sliceMs
                            accumulatedDeltaMs = (accumulatedDeltaMs - dragAmount / pxPerMs)
                                .coerceIn(0f, durationMs.toFloat())
                            onSeek(accumulatedDeltaMs.toLong())
                        }
                    }
                )
            }
    ) {
        val stepPx = step.toPx()
        val barWidthPx = barWidth.toPx()
        val maxBar = size.height * 0.70f
        val centerY = size.height / 2
        val playheadX = size.width * 0.5f

        val totalSamples = amplitudes.size
        val currentMs = progress.coerceIn(0f, 1f) * durationMs
        val sliceMs = if (totalSamples > 0 && durationMs > 0) durationMs.toFloat() / totalSamples else 50f
        val pxPerMs = stepPx / sliceMs

        if (totalSamples == 0) {
            var dotX = 16.dp.toPx()
            while (dotX < size.width - 16.dp.toPx()) {
                drawCircle(RecorderColors.BarUpcoming, radius = DotSize.toPx() / 2, center = Offset(dotX, centerY))
                dotX += stepPx
            }
        } else {
            for (i in 0 until totalSamples) {
                val sampleMs = i * sliceMs
                val x = playheadX + (sampleMs - currentMs) * pxPerMs
                if (x < -stepPx || x > size.width + stepPx) continue
                val isPlayed = x <= playheadX
                val color = if (isPlayed) RecorderColors.BarPlayed else RecorderColors.BarUpcoming
                drawBar(x, centerY, amplitudes[i], maxBar, barWidthPx, color)
            }
        }

        // Draw fixed center playhead cursor
        drawLine(
            color = RecorderColors.Bar,
            start = Offset(playheadX, size.height * 0.08f),
            end = Offset(playheadX, size.height * 0.92f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

/**
 * Modern Google Recorder / Material 3 squiggly slider.
 * Shows an animated sine wave on the played portion during playback and a vertical tick thumb.
 */
@Composable
fun SquigglySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "squiggly")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        val pad = 16.dp.toPx()
                        val trackW = (size.width - 2 * pad).coerceAtLeast(1f)
                        val progress = ((offset.x - pad) / trackW).coerceIn(0f, 1f)
                        onValueChange(progress)
                        val released = tryAwaitRelease()
                        if (released) {
                            onValueChangeFinished()
                        }
                    }
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val pad = 16.dp.toPx()
                        val trackW = (size.width - 2 * pad).coerceAtLeast(1f)
                        val progress = ((offset.x - pad) / trackW).coerceIn(0f, 1f)
                        onValueChange(progress)
                    },
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { onValueChangeFinished() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val pad = 16.dp.toPx()
                        val trackW = (size.width - 2 * pad).coerceAtLeast(1f)
                        val progress = ((change.position.x - pad) / trackW).coerceIn(0f, 1f)
                        onValueChange(progress)
                    }
                )
            }
    ) {
        val pad = 16.dp.toPx()
        val trackStart = pad
        val trackEnd = size.width - pad
        val trackW = trackEnd - trackStart
        val centerY = size.height / 2
        val thumbX = (trackStart + value.coerceIn(0f, 1f) * trackW).coerceIn(trackStart, trackEnd)

        val trackStrokePx = 3.dp.toPx()
        val thumbHalfHPx = 9.dp.toPx()

        // 1. Inactive track (ahead of thumb)
        if (thumbX < trackEnd) {
            drawLine(
                color = RecorderColors.TrackInactive,
                start = Offset(thumbX, centerY),
                end = Offset(trackEnd, centerY),
                strokeWidth = trackStrokePx,
                cap = StrokeCap.Round
            )
        }

        // 2. Active track (behind thumb)
        if (thumbX > trackStart) {
            if (!isPlaying) {
                drawLine(
                    color = RecorderColors.BarPlayed,
                    start = Offset(trackStart, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = trackStrokePx,
                    cap = StrokeCap.Round
                )
            } else {
                val wavelength = 30.dp.toPx()
                val maxAmp = 3.5.dp.toPx()
                val taperDist = 16.dp.toPx()

                val path = Path().apply {
                    moveTo(trackStart, centerY)
                    var x = trackStart
                    val stepPx = 2.dp.toPx()
                    while (x < thumbX) {
                        x = (x + stepPx).coerceAtMost(thumbX)
                        val distToThumb = thumbX - x
                        val taper = (distToThumb / taperDist).coerceIn(0f, 1f)
                        val amp = maxAmp * taper
                        val angle = (2f * PI.toFloat() * (x - trackStart) / wavelength) - wavePhase
                        val y = centerY + amp * sin(angle)
                        lineTo(x, y)
                    }
                }
                drawPath(
                    path = path,
                    color = RecorderColors.BarPlayed,
                    style = Stroke(width = trackStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        // 3. Thumb handle (vertical tick line)
        drawLine(
            color = RecorderColors.BarPlayed,
            start = Offset(thumbX, centerY - thumbHalfHPx),
            end = Offset(thumbX, centerY + thumbHalfHPx),
            strokeWidth = trackStrokePx,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawBar(
    x: Float,
    centerY: Float,
    amplitude: Float,
    maxBar: Float,
    barWidth: Float,
    color: Color
) {
    val dot = DotSize.toPx()
    val height = (amplitude * maxBar).coerceAtLeast(dot)
    if (height <= dot) {
        drawCircle(color, radius = dot / 2, center = Offset(x, centerY))
    } else {
        drawLine(
            color = color,
            start = Offset(x, centerY - height / 2 + barWidth / 2),
            end = Offset(x, centerY + height / 2 - barWidth / 2),
            strokeWidth = barWidth,
            cap = StrokeCap.Round
        )
    }
}

/** Placeholder text shown in the transcript tab. */
@Composable
fun TranscriptHint(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text,
            color = RecorderColors.Muted,
            fontSize = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
