package me.mudkip.moememos.ui.page.voicerecording

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.mudkip.moememos.MoeMemosFileProvider
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.local.entity.ResourceEntity
import me.mudkip.moememos.ext.navigateToMemoEditor
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ui.page.common.LocalRootNavController
import me.mudkip.moememos.util.VoiceMemoFormat
import me.mudkip.moememos.util.extractWaveform
import me.mudkip.moememos.util.formatClock
import me.mudkip.moememos.util.formatVoiceTitle
import me.mudkip.moememos.util.transcribeAudioFile
import me.mudkip.moememos.viewmodel.LocalMemos
import me.mudkip.moememos.viewmodel.LocalUserState
import me.mudkip.moememos.viewmodel.MemosViewModel
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import kotlin.math.abs

private const val REWIND_MS = 5_000
private const val FORWARD_MS = 10_000

/** Player for a memo with an audio attachment, styled like Google Recorder. */
@Composable
fun VoiceMemoPlayer(memo: MemoEntity, audio: ResourceEntity, onBack: () -> Unit) {
    val context = LocalContext.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val rootNavController = LocalRootNavController.current
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable(memo.identifier) { mutableStateOf(RecorderTab.WAVEFORM) }
    var file by remember(audio.identifier) { mutableStateOf<File?>(null) }
    var loadFailed by remember(audio.identifier) { mutableStateOf(false) }
    var waveform by remember(audio.identifier) { mutableStateOf<List<Float>>(emptyList()) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTranscribeDialog by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }

    var playbackAnchorRealtime by remember { mutableLongStateOf(0L) }
    var playbackAnchorPositionMs by remember { mutableLongStateOf(0L) }

    RecorderSystemBars()

    fun updatePlaybackAnchor(pos: Long) {
        playbackAnchorRealtime = SystemClock.elapsedRealtime()
        playbackAnchorPositionMs = pos
        positionMs = pos
    }

    LaunchedEffect(audio.identifier) {
        val resolved = resolveAudioFile(context, audio, userStateViewModel.okHttpClient, memosViewModel)
        if (resolved == null) loadFailed = true else file = resolved
    }

    LaunchedEffect(file) {
        file?.let { waveform = extractWaveform(it) }
    }

    DisposableEffect(file) {
        val source = file
        val mediaPlayer = source?.let {
            runCatching {
                MediaPlayer().apply {
                    setDataSource(it.path)
                    prepare()
                    setOnCompletionListener { mp ->
                        playing = false
                        positionMs = mp.duration.toLong()
                        updatePlaybackAnchor(positionMs)
                    }
                }
            }.onFailure { e -> Timber.w(e); loadFailed = true }.getOrNull()
        }
        player = mediaPlayer
        durationMs = mediaPlayer?.duration?.toLong()?.coerceAtLeast(0) ?: 0L
        onDispose {
            mediaPlayer?.release()
            player = null
            playing = false
        }
    }

    // High refresh rate smooth position updates locked to display frames
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        updatePlaybackAnchor(positionMs)
        while (playing && isActive) {
            withFrameNanos { _ ->
                if (scrubbing == null) {
                    val elapsed = SystemClock.elapsedRealtime() - playbackAnchorRealtime
                    val est = (playbackAnchorPositionMs + elapsed).coerceIn(0L, durationMs)
                    positionMs = est

                    // Sync check against MediaPlayer to correct any drift
                    val actual = player?.currentPosition?.toLong() ?: est
                    if (abs(actual - est) > 150) {
                        playbackAnchorPositionMs = actual
                        playbackAnchorRealtime = SystemClock.elapsedRealtime()
                        positionMs = actual
                    }
                    if (durationMs > 0 && est >= durationMs) {
                        playing = false
                    }
                }
            }
        }
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0, durationMs)
        player?.seekTo(target.toInt())
        updatePlaybackAnchor(target)
    }

    fun togglePlay() {
        val mp = player ?: return
        if (playing) {
            mp.pause()
            playing = false
            updatePlaybackAnchor(positionMs)
        } else {
            if (positionMs >= durationMs) seekTo(0)
            mp.start()
            updatePlaybackAnchor(positionMs)
            playing = true
        }
    }

    fun startTranscription() {
        val targetFile = file ?: return
        if (isTranscribing) return
        isTranscribing = true
        scope.launch {
            val account = userStateViewModel.currentAccount.value
            val text = transcribeAudioFile(targetFile, account, userStateViewModel.okHttpClient)
            if (text != null && text.isNotBlank()) {
                memosViewModel.editMemo(memo.identifier, text, memo.resources, memo.visibility)
                tab = RecorderTab.TRANSCRIPT
            } else {
                Toast.makeText(context, R.string.transcription_failed_saved.string, Toast.LENGTH_LONG).show()
            }
            isTranscribing = false
        }
    }

    val shownPosition = scrubbing?.let { (it * durationMs).toLong() } ?: positionMs
    val progress = if (durationMs > 0) shownPosition.toFloat() / durationMs else 0f
    val transcript = VoiceMemoFormat.transcriptOf(memo.content)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RecorderColors.Background)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp)
    ) {
        // Top bar: back, title, favorite (pin), menu.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, R.string.back.string, tint = RecorderColors.OnBackground)
            }
            Text(
                formatVoiceTitle(context, memo.date),
                color = RecorderColors.OnBackground,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                scope.launch { memosViewModel.updateMemoPinned(memo.identifier, !memo.pinned) }
            }) {
                Icon(
                    if (memo.pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (memo.pinned) R.string.unpin.string else R.string.pin.string,
                    tint = RecorderColors.OnBackground
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, R.string.more_options.string, tint = RecorderColors.OnBackground)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(if (transcript == null) R.string.transcribe_recording.string else R.string.transcribe_action.string) },
                        enabled = file != null && !isTranscribing,
                        onClick = {
                            menuExpanded = false
                            showTranscribeDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(R.string.share.string) },
                        enabled = file != null,
                        onClick = {
                            menuExpanded = false
                            file?.let { shareAudio(context, it, audio) }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(R.string.edit_transcript.string) },
                        onClick = {
                            menuExpanded = false
                            rootNavController.navigateToMemoEditor(memo.identifier)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(R.string.delete.string) },
                        onClick = {
                            menuExpanded = false
                            confirmDelete = true
                        }
                    )
                }
            }
        }

        RecorderCard(modifier = Modifier.weight(1f)) {
            when {
                tab == RecorderTab.TRANSCRIPT -> TranscriptPane(
                    transcript = transcript,
                    isTranscribing = isTranscribing,
                    onTranscribeClick = { showTranscribeDialog = true }
                )
                loadFailed -> TranscriptHint(R.string.voice_memo_audio_unavailable.string)
                file == null -> CircularProgressIndicator(
                    color = RecorderColors.OnBackground,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> PlaybackWaveform(
                    amplitudes = waveform,
                    progress = progress,
                    durationMs = durationMs,
                    onSeek = { seekTo(it) },
                    onScrubbing = { isScrubbing ->
                        if (isScrubbing) {
                            scrubbing = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                        } else {
                            scrubbing = null
                        }
                    },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        RecorderTabSwitch(
            selected = tab,
            onSelect = { selectedTab ->
                tab = selectedTab
                if (selectedTab == RecorderTab.TRANSCRIPT && transcript == null && !isTranscribing && file != null) {
                    showTranscribeDialog = true
                }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(12.dp))

        // Modern Google Recorder squiggly slider
        SquigglySlider(
            value = progress,
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                scrubbing?.let { seekTo((it * durationMs).toLong()) }
                scrubbing = null
            },
            isPlaying = playing,
            enabled = player != null && durationMs > 0,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Text(formatClock(shownPosition), color = RecorderColors.OnBackground, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("-" + formatClock((durationMs - shownPosition).coerceAtLeast(0)), color = RecorderColors.Muted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))

        // Playback controls: back 5 s, play / pause, forward 10 s.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundControl(
                onClick = { seekTo(positionMs - REWIND_MS) },
                enabled = player != null,
                label = R.string.rewind_5.string
            ) { Icon(Icons.Filled.Replay5, null, tint = RecorderColors.OnBackground, modifier = Modifier.size(28.dp)) }

            Button(
                onClick = ::togglePlay,
                enabled = player != null,
                modifier = Modifier
                    .weight(1f)
                    .height(68.dp),
                shape = RoundedCornerShape(34.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RecorderColors.LightButton,
                    contentColor = RecorderColors.OnLightButton,
                    disabledContainerColor = RecorderColors.Pill
                )
            ) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (playing) R.string.pause.string else R.string.listen.string,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            RoundControl(
                onClick = { seekTo(positionMs + FORWARD_MS) },
                enabled = player != null,
                label = R.string.forward_10.string
            ) { Icon(Icons.Filled.Forward10, null, tint = RecorderColors.OnBackground, modifier = Modifier.size(28.dp)) }
        }
    }

    if (showTranscribeDialog) {
        AlertDialog(
            onDismissRequest = { showTranscribeDialog = false },
            title = { Text(R.string.transcribe_dialog_title.string) },
            text = { Text(R.string.transcribe_dialog_message.string) },
            confirmButton = {
                TextButton(onClick = {
                    showTranscribeDialog = false
                    startTranscription()
                }) { Text(R.string.transcribe_action.string) }
            },
            dismissButton = {
                TextButton(onClick = { showTranscribeDialog = false }) { Text(R.string.cancel.string) }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(R.string.delete_this_memo.string) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        player?.pause()
                        playing = false
                        memosViewModel.deleteMemo(memo.identifier)
                        onBack()
                    }
                }) { Text(R.string.delete.string) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(R.string.cancel.string) }
            }
        )
    }
}

@Composable
private fun TranscriptPane(
    transcript: String?,
    isTranscribing: Boolean,
    onTranscribeClick: () -> Unit
) {
    when {
        isTranscribing -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = RecorderColors.OnBackground)
                Spacer(Modifier.height(16.dp))
                Text(R.string.transcribing.string, color = RecorderColors.OnBackground, fontSize = 16.sp)
            }
        }
        transcript != null -> {
            SelectionContainer {
                Text(
                    transcript,
                    color = RecorderColors.OnBackground,
                    fontSize = 22.sp,
                    lineHeight = 32.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                )
            }
        }
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    R.string.no_transcript.string,
                    color = RecorderColors.Muted,
                    fontSize = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onTranscribeClick,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RecorderColors.DarkButton,
                        contentColor = RecorderColors.OnBackground
                    )
                ) {
                    Icon(Icons.Filled.GraphicEq, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(R.string.transcribe_recording.string, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun RoundControl(onClick: () -> Unit, enabled: Boolean, label: String, icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(RecorderColors.DarkButton)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

/** Returns a local copy of the audio attachment, downloading and caching it when needed. */
private suspend fun resolveAudioFile(
    context: Context,
    audio: ResourceEntity,
    client: OkHttpClient,
    memosViewModel: MemosViewModel,
): File? {
    localFile(audio)?.let { return it }
    val remote = audio.uri.toUri()
    if (remote.scheme != "http" && remote.scheme != "https") return null
    val downloaded = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(audio.uri).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val dir = File(context.cacheDir, "image_cache").also { it.mkdirs() }
                File.createTempFile("voice_", ".m4a", dir).also { target ->
                    response.body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }.onFailure { Timber.w(it) }.getOrNull()
    } ?: return null
    // Keep a canonical copy so the next open does not download again.
    if (memosViewModel.cacheResourceFile(audio.identifier, downloaded.toUri()) is ApiResponse.Success) {
        memosViewModel.getResourceById(audio.identifier)?.let(::localFile)?.let {
            downloaded.delete()
            return it
        }
    }
    return downloaded
}

private fun localFile(resource: ResourceEntity): File? {
    val uri = (resource.localUri ?: resource.uri).toUri()
    if (uri.scheme != "file") return null
    return uri.path?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
}

private fun shareAudio(context: Context, file: File, audio: ResourceEntity) {
    runCatching {
        val uri = MoeMemosFileProvider.getFileUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = audio.mimeType ?: "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, audio.filename, uri)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure {
        Timber.w(it)
        Toast.makeText(context, R.string.failed_to_open_attachment.string, Toast.LENGTH_SHORT).show()
    }
}
