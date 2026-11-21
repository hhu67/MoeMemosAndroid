package me.mudkip.moememos.ui.page.voicerecording

import android.media.MediaPlayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.mudkip.moememos.R
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ui.component.ActionIconButton
import kotlin.math.abs
import kotlin.random.Random

private val MediaPlayer.durationMs: Long
    get() = duration.toLong()

private val MediaPlayer.currentPositionMs: Long
    get() = currentPosition.toLong()

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceMemoPlayerPage(
    audioUri: String,
    transcription: String = "",
    recordingDate: String = "",
    onBack: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var selectedTab by remember { mutableStateOf(0) }
    var waveformAmplitudes by remember { mutableStateOf(listOf<Float>()) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isFavorited by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    DisposableEffect(audioUri) {
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(context, android.net.Uri.parse(audioUri))
                prepare()
                duration = durationMs
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Update current position and waveform
    LaunchedEffect(isPlaying) {
        while (isPlaying && mediaPlayer != null) {
            currentPosition = mediaPlayer!!.currentPositionMs

            // Generate waveform based on playback position
            waveformAmplitudes = (0..30).map { index ->
                val positionRatio = (currentPosition.toFloat() / duration.coerceAtLeast(1L))
                if (index < positionRatio * 30) {
                    Random.nextFloat() * 0.8f + 0.1f
                } else {
                    Random.nextFloat() * 0.3f + 0.05f
                }
            }

            delay(50)

            if (currentPosition >= duration) {
                isPlaying = false
                mediaPlayer?.seekTo(0)
                currentPosition = 0L
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = recordingDate.ifEmpty { "Voice Memo" },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = R.string.back.string
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isFavorited = !isFavorited }) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Favorite",
                            tint = if (isFavorited) Color.Yellow else Color.Gray
                        )
                    }
                    IconButton(onClick = { showMoreMenu = !showMoreMenu }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                }
            )
        },
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Card with waveform or transcription
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (selectedTab == 0) {
                            PlayerWaveform(
                                amplitudes = waveformAmplitudes,
                                progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
                            )
                        } else {
                            Text(
                                text = transcription.ifEmpty { "No transcription available" },
                                fontSize = 14.sp,
                                color = if (transcription.isEmpty())
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onSurface,
                                lineHeight = 20.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp, max = 300.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Tab switcher
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TabButtonPlayer(
                                modifier = Modifier.weight(1f),
                                isSelected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                label = "〰️"
                            )
                            TabButtonPlayer(
                                modifier = Modifier.weight(1f),
                                isSelected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                label = "📝"
                            )
                        }
                    }
                }

                // Timeline
                Timeline(
                    currentPosition = currentPosition,
                    duration = duration,
                    onSeek = { mediaPlayer?.seekTo(it.toInt()) }
                )

                // Playback controls
                PlaybackControls(
                    isPlaying = isPlaying,
                    onPlayPause = {
                        if (isPlaying) {
                            mediaPlayer?.pause()
                        } else {
                            mediaPlayer?.start()
                        }
                        isPlaying = !isPlaying
                    },
                    onRewind = {
                        val newPos = (currentPosition - 5000).coerceAtLeast(0)
                        mediaPlayer?.seekTo(newPos.toInt())
                    },
                    onForward = {
                        val newPos = (currentPosition + 10000).coerceAtMost(duration)
                        mediaPlayer?.seekTo(newPos.toInt())
                    }
                )
            }
        }
    )
}

@Composable
fun PlayerWaveform(
    amplitudes: List<Float>,
    progress: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            amplitudes.forEach { amplitude ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight(amplitude)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .padding(horizontal = 1.dp)
                )
            }
        }

        // Playback cursor
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(0.dp)
                )
        )
    }
}

@Composable
fun Timeline(
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Slider(
            value = currentPosition.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(currentPosition),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatTime(duration - currentPosition),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rewind button
        IconButton(
            onClick = onRewind,
            modifier = Modifier
                .size(44.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape
                )
        ) {
            Text(
                text = R.string.rewind_5.string,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }

        // Play/Pause button
        Button(
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            onClick = onPlayPause,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                text = if (isPlaying) R.string.pause.string else R.string.play.string,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Forward button
        IconButton(
            onClick = onForward,
            modifier = Modifier
                .size(44.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape
                )
        ) {
            Text(
                text = R.string.forward_10.string,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TabButtonPlayer(
    isSelected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Button(
        modifier = modifier
            .height(36.dp)
            .fillMaxWidth(),
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                Color.Transparent,
            contentColor = if (isSelected)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(label, fontSize = 16.sp)
    }
}
