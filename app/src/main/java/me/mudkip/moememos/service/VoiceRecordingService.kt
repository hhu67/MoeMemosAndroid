package me.mudkip.moememos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.mudkip.moememos.MainActivity
import me.mudkip.moememos.R
import me.mudkip.moememos.util.formatRecordingClock
import me.mudkip.moememos.util.normalizeAmplitude
import me.mudkip.moememos.viewmodel.RecorderPhase
import timber.log.Timber
import java.io.File

class VoiceRecordingService : Service() {

    interface Callback {
        fun onPhaseChanged(phase: RecorderPhase)
        fun onAmplitude(amplitude: Float)
        fun onElapsed(elapsedMs: Long)
        fun onSaveRequested()
        fun onDiscardRequested()
        fun onError(message: String)
    }

    companion object {
        const val CHANNEL_ID = "voice_recording_channel_v4"
        const val NOTIFICATION_ID = 2026

        const val ACTION_START = "me.mudkip.moememos.action.START_RECORDING"
        const val ACTION_PAUSE = "me.mudkip.moememos.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "me.mudkip.moememos.action.RESUME_RECORDING"
        const val ACTION_SAVE = "me.mudkip.moememos.action.SAVE_RECORDING"
        const val ACTION_DISCARD = "me.mudkip.moememos.action.DISCARD_RECORDING"
        const val ACTION_STOP = "me.mudkip.moememos.action.STOP_RECORDING"

        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_ACCUMULATED_MS = "extra_accumulated_ms"

        private const val SAMPLE_INTERVAL_MS = 50L

        var callback: Callback? = null
        var instance: VoiceRecordingService? = null
            private set
        var currentAudioFile: File? = null
            private set
        var currentPhase: RecorderPhase = RecorderPhase.IDLE
            private set
        var currentElapsedMs: Long = 0L
            private set

        fun startRecording(context: Context, outputFile: File, accumulatedMs: Long = 0L) {
            currentAudioFile = outputFile
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FILE_PATH, outputFile.absolutePath)
                putExtra(EXTRA_ACCUMULATED_MS, accumulatedMs)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start VoiceRecordingService")
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_PAUSE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to pause VoiceRecordingService")
            }
        }

        fun resume(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_RESUME
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to resume VoiceRecordingService")
            }
        }

        fun save(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_SAVE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save VoiceRecordingService")
            }
        }

        fun discard(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_DISCARD
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to discard VoiceRecordingService")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop VoiceRecordingService")
            }
        }
    }

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null

    private var accumulatedMs = 0L
    private var segmentStart = 0L
    private var lastNotifUpdateMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                accumulatedMs = intent.getLongExtra(EXTRA_ACCUMULATED_MS, 0L)
                if (filePath != null) {
                    audioFile = File(filePath)
                }
                currentElapsedMs = accumulatedMs
                currentPhase = RecorderPhase.RECORDING
                callback?.onPhaseChanged(RecorderPhase.RECORDING)

                startForegroundWithNotification()
                startMediaRecorder()
            }

            ACTION_PAUSE -> {
                pauseRecording()
            }

            ACTION_RESUME -> {
                resumeRecording()
            }

            ACTION_SAVE -> {
                stopAndReleaseRecorder()
                currentPhase = RecorderPhase.SAVING
                val cb = callback
                cb?.onPhaseChanged(RecorderPhase.SAVING)
                cb?.onSaveRequested()
                stopForegroundAndSelf()
            }

            ACTION_DISCARD -> {
                stopAndReleaseRecorder()
                audioFile?.delete()
                audioFile = null
                currentAudioFile = null
                currentPhase = RecorderPhase.DONE
                val cb = callback
                cb?.onPhaseChanged(RecorderPhase.DONE)
                cb?.onDiscardRequested()
                stopForegroundAndSelf()
            }

            ACTION_STOP -> {
                stopAndReleaseRecorder()
                currentPhase = RecorderPhase.DONE
                callback?.onPhaseChanged(RecorderPhase.DONE)
                stopForegroundAndSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startMediaRecorder() {
        val file = audioFile ?: return
        try {
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
            startTicker()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start MediaRecorder")
            callback?.onError(getString(R.string.recording_failed))
            stopForegroundAndSelf()
        }
    }

    private fun pauseRecording() {
        if (currentPhase != RecorderPhase.RECORDING) return
        runCatching { recorder?.pause() }.onFailure { Timber.e(it) }
        accumulatedMs += System.currentTimeMillis() - segmentStart
        currentElapsedMs = accumulatedMs
        currentPhase = RecorderPhase.PAUSED
        callback?.onPhaseChanged(RecorderPhase.PAUSED)
        callback?.onElapsed(currentElapsedMs)
        updateNotification()
    }

    private fun resumeRecording() {
        if (currentPhase != RecorderPhase.PAUSED) return
        runCatching { recorder?.resume() }.onFailure { Timber.e(it) }
        segmentStart = System.currentTimeMillis()
        currentPhase = RecorderPhase.RECORDING
        callback?.onPhaseChanged(RecorderPhase.RECORDING)
        updateNotification()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        lastNotifUpdateMs = 0L
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                if (currentPhase != RecorderPhase.RECORDING) continue
                currentElapsedMs = accumulatedMs + (System.currentTimeMillis() - segmentStart)
                val peak = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val amp = normalizeAmplitude(peak)
                callback?.onAmplitude(amp)
                callback?.onElapsed(currentElapsedMs)

                if (currentElapsedMs - lastNotifUpdateMs >= 1000L) {
                    lastNotifUpdateMs = currentElapsedMs
                    updateNotification()
                }
            }
        }
    }

    fun stopAndReleaseRecorder() {
        tickerJob?.cancel()
        if (currentPhase == RecorderPhase.RECORDING) {
            accumulatedMs += System.currentTimeMillis() - segmentStart
            currentElapsedMs = accumulatedMs
        }
        runCatching { recorder?.stop() }.onFailure { Timber.e(it, "Failed to stop MediaRecorder") }
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundAndSelf() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPaused = currentPhase == RecorderPhase.PAUSED
        val title = if (isPaused) {
            getString(R.string.recording_paused)
        } else {
            getString(R.string.recording_voice_memo)
        }
        val durationText = formatRecordingClock(currentElapsedMs)
        val baseTime = System.currentTimeMillis() - currentElapsedMs

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = Intent(this, VoiceRecordingService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseResumeIcon = if (isPaused) R.drawable.ic_record_play else R.drawable.ic_record_pause
        val pauseResumeTitle = if (isPaused) getString(R.string.resume) else getString(R.string.pause)

        val saveIntent = Intent(this, VoiceRecordingService::class.java).apply {
            action = ACTION_SAVE
        }
        val savePendingIntent = PendingIntent.getService(
            this,
            2,
            saveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val discardIntent = Intent(this, VoiceRecordingService::class.java).apply {
            action = ACTION_DISCARD
        }
        val discardPendingIntent = PendingIntent.getService(
            this,
            3,
            discardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record_mic)
            .setContentTitle(title)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(pauseResumeIcon, pauseResumeTitle, pauseResumePendingIntent)
            .addAction(R.drawable.ic_record_save, getString(R.string.save), savePendingIntent)
            .addAction(R.drawable.ic_record_cancel, getString(R.string.cancel), discardPendingIntent)

        if (isPaused) {
            builder.setUsesChronometer(false)
            builder.setShowWhen(false)
            builder.setContentText(durationText)
            builder.setSubText(durationText)
        } else {
            builder.setUsesChronometer(true)
            builder.setWhen(baseTime)
            builder.setShowWhen(true)
            builder.setContentText(getString(R.string.recording_voice_memo))
        }

        val notification = builder.build()
        builder.setPublicVersion(notification)
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.deleteNotificationChannel("voice_recording_channel")
            notificationManager?.deleteNotificationChannel("voice_recording_channel_v2")
            notificationManager?.deleteNotificationChannel("voice_recording_channel_v3")

            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_recording_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.voice_recording_channel_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "moememos:voicerecording"
            )?.apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L) // 3 hours timeout
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    override fun onDestroy() {
        stopAndReleaseRecorder()
        releaseWakeLock()
        serviceScope.cancel()
        if (instance === this) instance = null
        currentAudioFile = null
        super.onDestroy()
    }
}
