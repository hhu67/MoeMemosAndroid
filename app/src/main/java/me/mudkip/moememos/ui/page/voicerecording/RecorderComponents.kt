package me.mudkip.moememos.ui.page.voicerecording

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.mudkip.moememos.R
import me.mudkip.moememos.ext.string
import kotlin.math.floor

/** Colors of the recorder screens. They follow the dark Google Recorder look in both themes. */
object RecorderColors {
    val Background = Color(0xFF131313)
    val Card = Color(0xFF242424)
    val Pill = Color(0xFF353535)
    val PillSelected = Color(0xFFC6C6C6)
    val Bar = Color(0xFFFFFFFF)
    val BarPlayed = Color(0xFFFFFFFF)
    val BarUpcoming = Color(0xFFBDBDBD)
    val OnBackground = Color(0xFFE6E1E5)
    val Muted = Color(0xFF9E9E9E)
    val LightButton = Color(0xFFFFFFFF)
    val OnLightButton = Color(0xFF1C1B1F)
    val StopButton = Color(0xFFF2B8B5)
    val OnStopButton = Color(0xFF601410)
    val DarkButton = Color(0xFF2B2B2B)
    val RecordingDot = Color(0xFFF2B8B5)
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

private val BarWidth = 4.dp
private val BarGap = 5.dp
private val DotSize = 4.dp

/**
 * Live recording waveform. The newest sample is at the right edge and older ones scroll left.
 * Each bar's height is the loudness of that slice; near-silence is drawn as a dot.
 */
@Composable
fun LiveWaveform(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
        val step = (BarWidth + BarGap).toPx()
        val barWidth = BarWidth.toPx()
        val maxBar = size.height * 0.9f
        val centerY = size.height / 2
        val visible = floor(size.width / step).toInt()
        val start = (amplitudes.size - visible).coerceAtLeast(0)
        // Right-align so a new sample appears at the edge and pushes the others left.
        var x = size.width - (amplitudes.size - start) * step + step / 2
        for (i in start until amplitudes.size) {
            drawBar(x, centerY, amplitudes[i], maxBar, barWidth, RecorderColors.Bar)
            x += step
        }
    }
}

/**
 * Playback waveform: the whole recording, with a fixed playhead in the middle and the waveform
 * scrolling under it. Bars left of the playhead (already played) are brighter.
 */
@Composable
fun PlaybackWaveform(amplitudes: List<Float>, progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(260.dp)) {
        val step = (BarWidth + BarGap).toPx()
        val barWidth = BarWidth.toPx()
        val maxBar = size.height * 0.62f
        val centerY = size.height / 2
        val playheadX = size.width * 0.45f
        val offset = progress.coerceIn(0f, 1f) * amplitudes.size * step
        amplitudes.forEachIndexed { index, value ->
            val x = playheadX + index * step + step / 2 - offset
            if (x < -step || x > size.width + step) return@forEachIndexed
            val color = if (x < playheadX) RecorderColors.BarPlayed else RecorderColors.BarUpcoming
            drawBar(x, centerY, value, maxBar, barWidth, color)
        }
        drawLine(
            color = RecorderColors.Bar,
            start = Offset(playheadX, size.height * 0.05f),
            end = Offset(playheadX, size.height * 0.95f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(
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
