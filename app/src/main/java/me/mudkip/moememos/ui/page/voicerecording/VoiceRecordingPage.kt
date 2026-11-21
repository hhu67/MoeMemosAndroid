package me.mudkip.moememos.ui.page.voicerecording

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.mudkip.moememos.R
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.util.formatRecordingClock
import me.mudkip.moememos.util.formatVoiceTitle
import me.mudkip.moememos.viewmodel.LocalMemos
import me.mudkip.moememos.viewmodel.RecorderPhase
import me.mudkip.moememos.viewmodel.VoiceRecordingViewModel

@Composable
fun VoiceRecordingPage(
    onFinished: (savedMemoId: String?) -> Unit,
    viewModel: VoiceRecordingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val memosViewModel = LocalMemos.current
    var tab by rememberSaveable { mutableStateOf(RecorderTab.WAVEFORM) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val title = remember { formatVoiceTitle(context, viewModel.startedAt) }
    RecorderSystemBars()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.start()
        } else {
            Toast.makeText(context, R.string.audio_permission_required.string, Toast.LENGTH_LONG).show()
            onFinished(null)
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.phase != RecorderPhase.IDLE) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.start()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Keep the screen on while recording so a long dictation is not cut by the screen lock.
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    LaunchedEffect(viewModel.phase) {
        if (viewModel.phase == RecorderPhase.DONE) {
            if (viewModel.savedMemoId != null) memosViewModel.refreshLocalSnapshot()
            onFinished(viewModel.savedMemoId)
        }
    }

    val recording = viewModel.phase == RecorderPhase.RECORDING || viewModel.phase == RecorderPhase.PAUSED
    BackHandler(enabled = recording || viewModel.phase == RecorderPhase.SAVING) {
        if (recording) confirmDiscard = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RecorderColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp)
    ) {
        // Top bar: start date and time, delete.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = RecorderColors.OnBackground,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { confirmDiscard = true }, enabled = recording) {
                Icon(Icons.Filled.Delete, contentDescription = R.string.discard_recording.string, tint = RecorderColors.OnBackground)
            }
        }

        RecorderCard(modifier = Modifier.weight(1f)) {
            when (tab) {
                RecorderTab.WAVEFORM -> LiveWaveform(
                    amplitudes = viewModel.amplitudes,
                    modifier = Modifier.align(Alignment.Center)
                )
                RecorderTab.TRANSCRIPT -> LiveTranscriptPane(
                    text = viewModel.liveTranscript,
                    available = viewModel.transcriberAvailable
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        RecorderTabSwitch(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(20.dp))

        // Timer with the recording dot.
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RecordingDot(active = viewModel.phase == RecorderPhase.RECORDING)
            Spacer(Modifier.width(14.dp))
            Text(
                formatRecordingClock(viewModel.elapsedMs),
                color = RecorderColors.OnBackground,
                fontSize = 44.sp,
                fontWeight = FontWeight.Normal
            )
        }
        Spacer(Modifier.height(16.dp))

        if (viewModel.phase == RecorderPhase.SAVING) {
            SavingRow(viewModel.savingMessage ?: R.string.saving_voice_memo.string)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val paused = viewModel.phase == RecorderPhase.PAUSED
                Button(
                    onClick = { if (paused) viewModel.resume() else viewModel.pause() },
                    enabled = recording,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RecorderColors.LightButton,
                        contentColor = RecorderColors.OnLightButton
                    )
                ) {
                    if (paused) {
                        Icon(Icons.Filled.Circle, null, modifier = Modifier.size(16.dp))
                    } else {
                        PauseGlyph()
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (paused) R.string.resume.string else R.string.pause.string,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = { viewModel.stopAndSave() },
                    enabled = recording,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RecorderColors.StopButton,
                        contentColor = RecorderColors.OnStopButton
                    )
                ) {
                    Icon(Icons.Filled.Stop, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(R.string.stop.string, fontSize = 20.sp, maxLines = 1)
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(R.string.discard_recording_title.string) },
            text = { Text(R.string.discard_recording_message.string) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discard()
                }) { Text(R.string.delete.string) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(R.string.cancel.string) }
            }
        )
    }
}

@Composable
private fun LiveTranscriptPane(text: String, available: Boolean) {
    if (text.isBlank()) {
        TranscriptHint(
            if (available) R.string.transcript_listening.string else R.string.transcript_after_stop.string
        )
        return
    }
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.animateScrollTo(scroll.maxValue) }
    SelectionContainer {
        Text(
            text,
            color = RecorderColors.OnBackground,
            fontSize = 24.sp,
            lineHeight = 34.sp,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun RecordingDot(active: Boolean) {
    val pulse = rememberInfiniteTransition(label = "recording-dot")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "recording-dot-alpha"
    )
    Box(
        modifier = Modifier
            .size(18.dp)
            .alpha(if (active) alpha else 1f)
            .clip(CircleShape)
            .background(if (active) RecorderColors.RecordingDot else RecorderColors.Muted)
    )
}

/** Two vertical bars; the extended icon set has no plain Pause in this version's filled style. */
@Composable
private fun PauseGlyph() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(2) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 16.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(RecorderColors.OnLightButton)
            )
        }
    }
}

@Composable
private fun SavingRow(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(color = RecorderColors.OnBackground, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Text(message, color = RecorderColors.OnBackground, fontSize = 18.sp)
    }
}
