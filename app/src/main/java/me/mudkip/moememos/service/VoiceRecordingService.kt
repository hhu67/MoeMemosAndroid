package me.mudkip.moememos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import me.mudkip.moememos.MainActivity
import me.mudkip.moememos.R
import me.mudkip.moememos.util.formatRecordingClock
import timber.log.Timber

object VoiceRecordingSession {
    interface Listener {
        fun onPauseRequested()
        fun onResumeRequested()
        fun onSaveRequested()
        fun onDiscardRequested()
    }

    var listener: Listener? = null
}

class VoiceRecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var isPaused = false
    private var elapsedMs = 0L

    companion object {
        const val CHANNEL_ID = "voice_recording_channel"
        const val NOTIFICATION_ID = 2026

        const val ACTION_START = "me.mudkip.moememos.action.START_RECORDING"
        const val ACTION_UPDATE = "me.mudkip.moememos.action.UPDATE_RECORDING"
        const val ACTION_PAUSE = "me.mudkip.moememos.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "me.mudkip.moememos.action.RESUME_RECORDING"
        const val ACTION_SAVE = "me.mudkip.moememos.action.SAVE_RECORDING"
        const val ACTION_DISCARD = "me.mudkip.moememos.action.DISCARD_RECORDING"
        const val ACTION_STOP = "me.mudkip.moememos.action.STOP_RECORDING"

        const val EXTRA_IS_PAUSED = "extra_is_paused"
        const val EXTRA_ELAPSED_MS = "extra_elapsed_ms"

        fun start(context: Context) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_START
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

        fun update(context: Context, isPaused: Boolean, elapsedMs: Long) {
            val intent = Intent(context, VoiceRecordingService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_IS_PAUSED, isPaused)
                putExtra(EXTRA_ELAPSED_MS, elapsedMs)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update VoiceRecordingService")
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                isPaused = false
                elapsedMs = 0L
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

            ACTION_UPDATE -> {
                isPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, isPaused)
                elapsedMs = intent.getLongExtra(EXTRA_ELAPSED_MS, elapsedMs)
                updateNotification()
            }

            ACTION_PAUSE -> {
                isPaused = true
                VoiceRecordingSession.listener?.onPauseRequested()
                updateNotification()
            }

            ACTION_RESUME -> {
                isPaused = false
                VoiceRecordingSession.listener?.onResumeRequested()
                updateNotification()
            }

            ACTION_SAVE -> {
                VoiceRecordingSession.listener?.onSaveRequested()
                stopForegroundAndSelf()
            }

            ACTION_DISCARD -> {
                VoiceRecordingSession.listener?.onDiscardRequested()
                stopForegroundAndSelf()
            }

            ACTION_STOP -> {
                stopForegroundAndSelf()
            }
        }

        return START_STICKY
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
        val title = if (isPaused) {
            getString(R.string.recording_paused)
        } else {
            getString(R.string.recording_voice_memo)
        }
        val durationText = formatRecordingClock(elapsedMs)

        // Tapping notification brings MainActivity to the front
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Pause/Resume action
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

        // Save action
        val saveIntent = Intent(this, VoiceRecordingService::class.java).apply {
            action = ACTION_SAVE
        }
        val savePendingIntent = PendingIntent.getService(
            this,
            2,
            saveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Discard action
        val discardIntent = Intent(this, VoiceRecordingService::class.java).apply {
            action = ACTION_DISCARD
        }
        val discardPendingIntent = PendingIntent.getService(
            this,
            3,
            discardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record_mic)
            .setContentTitle(title)
            .setContentText(durationText)
            .setSubText(durationText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(pauseResumeIcon, pauseResumeTitle, pauseResumePendingIntent)
            .addAction(R.drawable.ic_record_save, getString(R.string.save), savePendingIntent)
            .addAction(R.drawable.ic_record_cancel, getString(R.string.cancel), discardPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_recording_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.voice_recording_channel_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
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

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
