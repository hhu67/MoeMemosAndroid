package me.mudkip.moememos.util

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skydoves.sandwich.ApiResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.mudkip.moememos.R
import me.mudkip.moememos.data.model.Account
import me.mudkip.moememos.data.model.MemoVisibility
import me.mudkip.moememos.data.service.AccountService
import me.mudkip.moememos.data.service.MemoService
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ui.page.voicerecording.RecordingDot
import me.mudkip.moememos.widget.WidgetUpdater
import okhttp3.MediaType.Companion.toMediaType
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val RedBallColor = Color(0xFFEA4335)

/**
 * Controller for holding the microphone button on the home screen to quickly record a voice note.
 */
class QuickVoiceRecorder(
    private val appContext: Context,
    private val memoService: MemoService,
    private val accountService: AccountService,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
) {
    var isRecording by mutableStateOf(false)
        private set
    var amplitude by mutableFloatStateOf(0f)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    var isSaving by mutableStateOf(false)
        private set

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var tickerJob: Job? = null
    private var recordStartRealtime = 0L

    fun start(): Boolean {
        if (isRecording) return false
        try {
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(Instant.now().atZone(ZoneId.systemDefault()))
            val dir = File(appContext.cacheDir, "voice").also { it.mkdirs() }
            val file = File(dir, "voice-quick-$stamp.m4a")
            audioFile = file

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
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
            recorder = rec
            recordStartRealtime = SystemClock.elapsedRealtime()
            isRecording = true
            elapsedMs = 0L
            amplitude = 0f

            tickerJob = scope.launch {
                while (isActive && isRecording) {
                    delay(40)
                    elapsedMs = SystemClock.elapsedRealtime() - recordStartRealtime
                    val peak = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    amplitude = normalizeAmplitude(peak)
                }
            }
            return true
        } catch (e: Exception) {
            Timber.e(e, "Quick record failed to start")
            release()
            return false
        }
    }

    fun stopAndSave() {
        if (!isRecording) return
        val duration = SystemClock.elapsedRealtime() - recordStartRealtime
        tickerJob?.cancel()
        isRecording = false
        amplitude = 0f

        val rec = recorder
        val file = audioFile
        runCatching { rec?.stop() }
        release()

        if (duration < 600L || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            return
        }

        isSaving = true
        scope.launch(Dispatchers.IO) {
            try {
                val account = accountService.currentAccount.first()
                val serverText = transcribeAudioFile(file, account, accountService.httpClient)
                val transcript = serverText?.takeIf { it.isNotBlank() } ?: VoiceMemoFormat.placeholder()

                val repository = memoService.getRepository()
                val resource = repository.createResource(
                    filename = file.name,
                    type = "audio/mp4".toMediaType(),
                    contentUri = Uri.fromFile(file),
                    memoIdentifier = null
                )
                if (resource is ApiResponse.Success) {
                    val visibility = account?.let(::resolveDefaultVisibility) ?: MemoVisibility.PRIVATE
                    val memo = repository.createMemo(
                        content = transcript,
                        visibility = visibility,
                        resources = listOf(resource.data)
                    )
                    if (memo is ApiResponse.Success) {
                        file.delete()
                        WidgetUpdater.updateWidgets(appContext)
                        withContext(Dispatchers.Main) {
                            onSaved()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Quick record save failed")
            } finally {
                withContext(Dispatchers.Main) {
                    isSaving = false
                }
            }
        }
    }

    fun cancel() {
        if (!isRecording) return
        tickerJob?.cancel()
        isRecording = false
        amplitude = 0f
        elapsedMs = 0L
        runCatching { recorder?.stop() }
        release()
        audioFile?.delete()
        audioFile = null
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun resolveDefaultVisibility(account: Account): MemoVisibility {
        val info = when (account) {
            is Account.MemosV0 -> account.info
            is Account.MemosV1 -> account.info
            else -> return MemoVisibility.PRIVATE
        }
        return runCatching { MemoVisibility.valueOf(info.defaultVisibility) }.getOrDefault(MemoVisibility.PRIVATE)
    }
}

/**
 * Fullscreen overlay showing the Telegram-style organic audio blob that expands in multiple
 * directions according to the speaker's loudness.
 */
@Composable
fun QuickVoiceRecordOverlay(
    isRecording: Boolean,
    amplitude: Float,
    elapsedMs: Long,
    dragOffsetX: Float,
    isCancelActive: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isRecording,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180))
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.40f))
        ) {
            // Cancel status & timer pill positioned to the left of the recording button
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 100.dp, bottom = 24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF242424))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RecordingDot(active = !isCancelActive)
                Text(
                    text = formatClock(elapsedMs),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(4.dp))
                if (isCancelActive) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = RedBallColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = R.string.release_to_cancel.string,
                        color = RedBallColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = R.string.slide_to_cancel.string,
                        color = Color(0xFFAAAAAA),
                        fontSize = 13.sp
                    )
                }
            }

            // Telegram-style dynamic morphing blob ball in the bottom-right corner
            DynamicTelegramBlob(
                amplitude = amplitude,
                dragOffsetX = dragOffsetX,
                isCancelActive = isCancelActive,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 4.dp)
            )
        }
    }
}

/**
 * Telegram-style liquid morphing blob ball with multiple harmonic lobes that grow in specific
 * directions proportional to the speaker's loudness.
 */
@Composable
private fun DynamicTelegramBlob(
    amplitude: Float,
    dragOffsetX: Float,
    isCancelActive: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "telegram_blob")
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )

    val animatedAmp by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "animatedAmp"
    )

    Box(
        modifier = modifier.size(140.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2 + (dragOffsetX.coerceAtMost(0f) * 0.35f)
            val cy = size.height / 2
            val center = Offset(cx, cy)

            val baseColor = if (isCancelActive) Color.DarkGray else RedBallColor

            // Outer layer: 4 lobes expanding in specific directions
            val outerPath = Path()
            val outerBaseR = 36.dp.toPx()
            val outerExtraR = 34.dp.toPx() * animatedAmp
            val steps = 72
            for (i in 0..steps) {
                val theta = (i * 2f * PI.toFloat()) / steps
                val lobe = 0.35f * sin(3f * theta + phase1) + 0.25f * cos(4f * theta - phase2)
                val r = outerBaseR + outerExtraR * (1f + lobe)
                val x = cx + r * cos(theta)
                val y = cy + r * sin(theta)
                if (i == 0) outerPath.moveTo(x, y) else outerPath.lineTo(x, y)
            }
            outerPath.close()
            drawPath(outerPath, color = baseColor.copy(alpha = 0.20f))

            // Middle layer: 5 lobes
            val midPath = Path()
            val midBaseR = 32.dp.toPx()
            val midExtraR = 22.dp.toPx() * animatedAmp
            for (i in 0..steps) {
                val theta = (i * 2f * PI.toFloat()) / steps
                val lobe = 0.28f * sin(4f * theta - phase2) + 0.22f * cos(5f * theta + phase1)
                val r = midBaseR + midExtraR * (1f + lobe)
                val x = cx + r * cos(theta)
                val y = cy + r * sin(theta)
                if (i == 0) midPath.moveTo(x, y) else midPath.lineTo(x, y)
            }
            midPath.close()
            drawPath(midPath, color = baseColor.copy(alpha = 0.38f))

            // Core solid ball expanding smoothly with amplitude
            val coreR = 28.dp.toPx() + (8.dp.toPx() * animatedAmp)
            drawCircle(
                color = baseColor,
                radius = coreR,
                center = center
            )
        }

        // Center icon following the ball offset
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(end = (-dragOffsetX.coerceAtMost(0f) * 0.35f).dp)
        ) {
            Icon(
                imageVector = if (isCancelActive) Icons.Filled.Delete else Icons.Filled.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
