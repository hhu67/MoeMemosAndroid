package me.mudkip.moememos.viewmodel

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.model.Account
import me.mudkip.moememos.data.model.MemoVisibility
import me.mudkip.moememos.data.service.AccountService
import me.mudkip.moememos.data.service.MemoService
import me.mudkip.moememos.ext.getErrorMessage
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.util.LiveTranscriber
import me.mudkip.moememos.util.VoiceMemoFormat
import me.mudkip.moememos.util.extractWaveform
import me.mudkip.moememos.util.mergeAudioFiles
import me.mudkip.moememos.util.normalizeAmplitude
import me.mudkip.moememos.util.resolveAudioFile
import me.mudkip.moememos.util.transcribeAudioFile
import me.mudkip.moememos.widget.WidgetUpdater
import okhttp3.MediaType.Companion.toMediaType
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class RecorderPhase { IDLE, RECORDING, PAUSED, SAVING, DONE }

@HiltViewModel
class VoiceRecordingViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val memoService: MemoService,
    private val accountService: AccountService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val resumeMemoId: String? = savedStateHandle.get<String>("resumeMemoId")
    var targetFolder: String? = savedStateHandle.get<String>("folder")

    var phase by mutableStateOf(RecorderPhase.IDLE)
        private set
    var elapsedMs by mutableStateOf(0L)
        private set
    var savingMessage by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var savedMemoId by mutableStateOf<String?>(null)
        private set

    var existingMemoDate by mutableStateOf<Instant?>(null)
        private set
    var existingTranscript by mutableStateOf("")
        private set

    /** One loudness value (0..1) every [SAMPLE_INTERVAL_MS], oldest first. */
    val amplitudes = mutableStateListOf<Float>()

    val startedAt: Instant = Instant.now()

    private val transcriber = LiveTranscriber(appContext)
    val liveTranscript: String get() = transcriber.text
    val transcriberAvailable: Boolean get() = transcriber.isAvailable

    val displayTranscript: String get() {
        val live = liveTranscript.trim()
        return when {
            existingTranscript.isNotBlank() && live.isNotBlank() -> "$existingTranscript\n\n$live"
            existingTranscript.isNotBlank() -> existingTranscript
            else -> live
        }
    }

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var existingMemo: MemoEntity? = null
    private var existingAudioFile: File? = null
    private var resumeInitJob: Deferred<Unit>? = null
    private var tickerJob: Job? = null
    private var accumulatedMs = 0L
    private var segmentStart = 0L

    init {
        if (resumeMemoId != null) {
            resumeInitJob = viewModelScope.async {
                val memo = memoService.getRepository().getMemo(resumeMemoId)
                if (memo != null) {
                    existingMemo = memo
                    existingMemoDate = memo.date
                    existingTranscript = VoiceMemoFormat.transcriptOf(memo.content) ?: ""
                    val audioRes = VoiceMemoFormat.audioOf(memo)
                    if (audioRes != null) {
                        val resolved = resolveAudioFile(appContext, audioRes, accountService.httpClient) { id, uri ->
                            memoService.getRepository().cacheResourceFile(id, uri)
                        }
                        if (resolved != null) {
                            existingAudioFile = resolved
                            val wf = extractWaveform(resolved)
                            if (wf.isNotEmpty()) {
                                amplitudes.addAll(wf)
                                accumulatedMs = wf.size * SAMPLE_INTERVAL_MS
                                elapsedMs = accumulatedMs
                            }
                        }
                    }
                }
            }
        }
    }

    fun start() {
        if (phase != RecorderPhase.IDLE) return
        viewModelScope.launch {
            resumeInitJob?.await()
            if (phase != RecorderPhase.IDLE) return@launch
            try {
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .format(Instant.now().atZone(ZoneId.systemDefault()))
                val dir = File(appContext.cacheDir, "voice").also { it.mkdirs() }
                val prefix = if (resumeMemoId != null) "voice-segment" else "voice-note"
                val file = File(dir, "$prefix-$stamp.m4a")
                audioFile = file
                recorder = createRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    setAudioSamplingRate(44_100)
                    setAudioEncodingBitRate(96_000)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                segmentStart = System.currentTimeMillis()
                phase = RecorderPhase.RECORDING
                transcriber.start()
                startTicker()
            } catch (e: Exception) {
                Timber.e(e)
                releaseRecorder()
                errorMessage = R.string.recording_failed.string
            }
        }
    }

    fun pause() {
        if (phase != RecorderPhase.RECORDING) return
        runCatching { recorder?.pause() }.onFailure { Timber.e(it) }
        accumulatedMs += System.currentTimeMillis() - segmentStart
        elapsedMs = accumulatedMs
        phase = RecorderPhase.PAUSED
        transcriber.pause()
    }

    fun resume() {
        if (phase != RecorderPhase.PAUSED) return
        runCatching { recorder?.resume() }.onFailure { Timber.e(it) }
        segmentStart = System.currentTimeMillis()
        phase = RecorderPhase.RECORDING
        transcriber.resume()
    }

    /** Throws the recording away. */
    fun discard() {
        tickerJob?.cancel()
        transcriber.stop()
        releaseRecorder()
        audioFile?.delete()
        audioFile = null
        phase = RecorderPhase.DONE
    }

    /**
     * Stops the recording and saves it: the audio becomes an attachment of a new memo and the
     * transcript becomes the memo text. The memo is saved even when transcription fails.
     */
    fun stopAndSave() {
        if (phase != RecorderPhase.RECORDING && phase != RecorderPhase.PAUSED) return
        if (phase == RecorderPhase.RECORDING) {
            accumulatedMs += System.currentTimeMillis() - segmentStart
        }
        elapsedMs = accumulatedMs
        tickerJob?.cancel()
        phase = RecorderPhase.SAVING
        val stopped = runCatching { recorder?.stop() }.isSuccess
        releaseRecorder()
        transcriber.stop()
        val file = audioFile
        if (!stopped || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            errorMessage = R.string.recording_failed.string
            phase = RecorderPhase.DONE
            return
        }

        viewModelScope.launch {
            val existing = existingMemo
            val existingFile = existingAudioFile
            val fileToUpload: File

            if (existing != null && existingFile != null && existingFile.exists()) {
                savingMessage = R.string.saving_voice_memo.string
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .format(Instant.now().atZone(ZoneId.systemDefault()))
                val dir = File(appContext.cacheDir, "voice").also { it.mkdirs() }
                val mergedFile = File(dir, "voice-note-merged-$stamp.m4a")
                val success = mergeAudioFiles(existingFile, file, mergedFile)
                fileToUpload = if (success) mergedFile else file
            } else {
                fileToUpload = file
            }

            savingMessage = R.string.transcribing.string
            // Transcribe the new segment
            val serverText = transcribeOnServer(file)
            val newSegmentText = serverText?.takeIf { it.isNotBlank() }
                ?: transcriber.text.takeIf { it.isNotBlank() }

            val fullTranscript = when {
                existingTranscript.isNotBlank() && !newSegmentText.isNullOrBlank() -> "$existingTranscript\n\n$newSegmentText"
                existingTranscript.isNotBlank() -> existingTranscript
                !newSegmentText.isNullOrBlank() -> newSegmentText
                else -> null
            }

            if (serverText == null && newSegmentText == null) {
                errorMessage = R.string.transcription_failed_saved.string
            }

            savingMessage = R.string.saving_voice_memo.string
            val repository = memoService.getRepository()
            val resource = repository.createResource(
                filename = fileToUpload.name,
                type = "audio/mp4".toMediaType(),
                contentUri = Uri.fromFile(fileToUpload),
                memoIdentifier = existing?.identifier
            )
            if (resource !is ApiResponse.Success) {
                errorMessage = resource.getErrorMessage().ifBlank { R.string.recording_failed.string }
                savingMessage = null
                phase = RecorderPhase.DONE
                return@launch
            }

            if (existing != null) {
                val updateRes = repository.updateMemo(
                    identifier = existing.identifier,
                    content = fullTranscript ?: VoiceMemoFormat.placeholder(),
                    resources = listOf(resource.data),
                    visibility = existing.visibility
                )
                if (updateRes is ApiResponse.Success) {
                    file.delete()
                    if (fileToUpload != file) fileToUpload.delete()
                    val oldAudio = VoiceMemoFormat.audioOf(existing)
                    if (oldAudio != null && oldAudio.identifier != resource.data.identifier) {
                        repository.deleteResource(oldAudio.identifier)
                    }
                    savedMemoId = existing.identifier
                    WidgetUpdater.updateWidgets(appContext)
                } else {
                    errorMessage = updateRes.getErrorMessage().ifBlank { R.string.recording_failed.string }
                }
            } else {
                val visibility = accountService.currentAccount.first()?.let(::defaultVisibility)
                    ?: MemoVisibility.PRIVATE
                val memo = repository.createMemo(
                    content = fullTranscript ?: VoiceMemoFormat.placeholder(),
                    visibility = visibility,
                    resources = listOf(resource.data),
                    folder = targetFolder
                )
                if (memo is ApiResponse.Success) {
                    file.delete()
                    savedMemoId = memo.data.identifier
                    WidgetUpdater.updateWidgets(appContext)
                } else {
                    errorMessage = memo.getErrorMessage().ifBlank { R.string.recording_failed.string }
                }
            }
            savingMessage = null
            phase = RecorderPhase.DONE
        }
    }

    private fun defaultVisibility(account: Account): MemoVisibility {
        val info = when (account) {
            is Account.MemosV0 -> account.info
            is Account.MemosV1 -> account.info
            else -> return MemoVisibility.PRIVATE
        }
        return runCatching { MemoVisibility.valueOf(info.defaultVisibility) }.getOrDefault(MemoVisibility.PRIVATE)
    }

    private suspend fun transcribeOnServer(file: File): String? =
        transcribeAudioFile(file, accountService.currentAccount.first(), accountService.httpClient)

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                if (phase != RecorderPhase.RECORDING) continue
                elapsedMs = accumulatedMs + (System.currentTimeMillis() - segmentStart)
                // Peak amplitude since the previous call: this is what makes bars follow loudness.
                val peak = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                amplitudes.add(normalizeAmplitude(peak))
            }
        }
    }

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun releaseRecorder() {
        runCatching { recorder?.release() }
        recorder = null
    }

    override fun onCleared() {
        tickerJob?.cancel()
        transcriber.destroy()
        if (phase == RecorderPhase.RECORDING || phase == RecorderPhase.PAUSED) {
            releaseRecorder()
            audioFile?.delete()
        }
        super.onCleared()
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 50L
    }
}
