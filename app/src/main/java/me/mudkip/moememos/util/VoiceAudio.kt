package me.mudkip.moememos.util

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.local.entity.ResourceEntity
import me.mudkip.moememos.data.model.Account
import me.mudkip.moememos.ext.string
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10

/** Quietest level that still draws a bar; anything below is shown as a silence dot. */
private const val SILENCE_FLOOR_DB = -45.0

/** Server transcription is limited to 25 MiB of audio. */
const val MAX_TRANSCRIBE_BYTES = 25L * 1024 * 1024

/**
 * Maps a 16-bit peak (0..32767) to 0..1 on a decibel scale, so the bar height follows how
 * loud the sound is perceived rather than the raw sample value.
 */
fun normalizeAmplitude(peak: Int): Float {
    if (peak <= 0) return 0f
    val db = 20 * log10(peak / 32767.0)
    return ((db - SILENCE_FLOOR_DB) / -SILENCE_FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Decodes an audio file and returns one loudness value (0..1) per [samplesPerSecond] slice.
 * Returns an empty list when the file cannot be decoded.
 */
suspend fun extractWaveform(file: File, samplesPerSecond: Int = 20): List<Float> = withContext(Dispatchers.Default) {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return@withContext emptyList()
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()

        var window = sliceSize(format, samplesPerSecond)
        var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        var count = 0
        var peak = 0
        val result = ArrayList<Float>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            ensureActive()
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outFormat = decoder.outputFormat
                window = sliceSize(outFormat, samplesPerSecond)
                if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
            } else if (outIndex >= 0) {
                val buffer = decoder.getOutputBuffer(outIndex)!!
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    val samples = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    while (samples.hasRemaining()) {
                        val value = (abs(samples.get()) * 32767).toInt()
                        if (value > peak) peak = value
                        if (++count >= window) {
                            result.add(normalizeAmplitude(peak))
                            peak = 0
                            count = 0
                        }
                    }
                } else {
                    val samples = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (samples.hasRemaining()) {
                        val value = abs(samples.get().toInt())
                        if (value > peak) peak = value
                        if (++count >= window) {
                            result.add(normalizeAmplitude(peak))
                            peak = 0
                            count = 0
                        }
                    }
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }
        if (count > 0) result.add(normalizeAmplitude(peak))
        result
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        emptyList()
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        extractor.release()
    }
}

private fun sliceSize(format: MediaFormat, samplesPerSecond: Int): Int {
    val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44_100
    val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
    return (rate * channels / samplesPerSecond).coerceAtLeast(1)
}

/**
 * A voice memo is a memo with an audio attachment. Its content is the transcript; when there is
 * no transcript the content is a placeholder that starts with [MARK].
 */
object VoiceMemoFormat {
    const val MARK = "🎙️"

    fun placeholder(): String = "$MARK ${R.string.voice_memo.string}"

    fun transcriptOf(content: String): String? =
        content.trim().takeUnless { it.isEmpty() || it.startsWith(MARK) }

    fun audioOf(memo: MemoEntity): ResourceEntity? =
        memo.resources.firstOrNull { it.mimeType?.startsWith("audio/") == true }
}

/** "22 нояб. в 00:43" / "Nov 22 at 00:43" in the app's language. */
fun formatVoiceTitle(context: Context, instant: Instant): String {
    val locale = context.resources.configuration.locales[0]
    val time = instant.atZone(ZoneId.systemDefault())
    val date = DateTimeFormatter.ofPattern("d MMM", locale).format(time)
    val clock = DateTimeFormatter.ofPattern("HH:mm", locale).format(time)
    return context.getString(R.string.voice_recording_title, date, clock)
}

/** 00:06,2 — minutes, seconds and tenths, as on the recorder. */
fun formatRecordingClock(ms: Long): String {
    val tenths = (ms / 100) % 10
    return "${formatClock(ms)},$tenths"
}

/** 21:27, or 1:02:03 for recordings longer than an hour. */
fun formatClock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0)) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * Calls `memos.api.AIService/Transcribe` (Connect protocol, JSON). Returns the text, or null
 * when the server has no transcription configured, rejects the request, or is unreachable.
 */
suspend fun transcribeAudioFile(file: File, account: Account?, httpClient: OkHttpClient): String? = withContext(Dispatchers.IO) {
    val memosV1 = account as? Account.MemosV1 ?: return@withContext null
    if (file.length() > MAX_TRANSCRIBE_BYTES) return@withContext null
    val base = memosV1.info.host.trimEnd('/').toHttpUrlOrNull() ?: return@withContext null
    val url = base.newBuilder().addPathSegments("memos.api.AIService/Transcribe").build()
    val body = buildJsonObject {
        putJsonObject("audio") {
            put("content", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            put("filename", file.name)
            put("contentType", "audio/mp4")
        }
    }.toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url(url)
        .post(body)
        .header("connect-protocol-version", "1")
        .header("Accept", "application/json")
        .build()
    val client = httpClient.newBuilder()
        .readTimeout(3, TimeUnit.MINUTES)
        .callTimeout(4, TimeUnit.MINUTES)
        .build()
    try {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                Timber.w("Transcribe failed: %d %s", response.code, text.take(300))
                return@withContext null
            }
            Json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim()
        }
    } catch (e: Exception) {
        Timber.w(e, "Transcribe request failed")
        null
    }
}
