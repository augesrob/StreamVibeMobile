package com.streamvibe.mobile.service

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.streamvibe.mobile.MainActivity
import com.streamvibe.mobile.data.tiktok.TikTokLiveRepository
import com.streamvibe.mobile.data.tts.TtsEngine
import com.streamvibe.mobile.data.tts.TtsEventMapper
import com.streamvibe.mobile.domain.model.TtsSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// StreamService
// Foreground service that:
// 1. Keeps the TikTok WebSocket connection alive in the background
// 2. Processes TTS queue while TikTok or a game is in the foreground
// 3. Shows persistent notification with live stats + Skip/Clear controls
//
// Start this when the user connects to a live room.
// Stop this when they disconnect.
// ─────────────────────────────────────────────────────────────────────────────
@AndroidEntryPoint
class StreamService : Service() {

    @Inject lateinit var liveRepo: TikTokLiveRepository
    @Inject lateinit var ttsEngine: TtsEngine

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ttsSettings = TtsSettings()

    private var currentViewers = 0
    private var totalDiamonds = 0

    companion object {
        const val CHANNEL_ID = "stream_service"
        const val NOTIF_ID = 1001
        const val ACTION_SKIP_TTS = "com.streamvibe.SKIP_TTS"
        const val ACTION_CLEAR_TTS = "com.streamvibe.CLEAR_TTS"
        const val ACTION_DISCONNECT = "com.streamvibe.DISCONNECT"
        const val EXTRA_TTS_SETTINGS = "tts_settings"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        observeEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SKIP_TTS   -> ttsEngine.skipCurrent()
            ACTION_CLEAR_TTS  -> ttsEngine.clearQueue()
            ACTION_DISCONNECT -> {
                liveRepo.disconnect()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = StreamBinder()

    inner class StreamBinder : Binder() {
        fun getService(): StreamService = this@StreamService
        fun updateTtsSettings(settings: TtsSettings) { ttsSettings = settings }
    }

    private fun observeEvents() {
        scope.launch {
            liveRepo.events.collect { event ->
                // TTS
                TtsEventMapper.map(event, ttsSettings)?.let { item ->
                    ttsEngine.enqueue(item, ttsSettings)
                }
                // Update notification periodically
                updateNotification()
            }
        }

        // Connection state changes
        scope.launch {
            liveRepo.connectionState.collect { state ->
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val connectionState = liveRepo.connectionState.value
        val stateText = when (connectionState) {
            is TikTokLiveRepository.ConnectionState.Connected    -> "● LIVE — ${currentViewers} viewers"
            is TikTokLiveRepository.ConnectionState.Connecting   -> "Connecting..."
            is TikTokLiveRepository.ConnectionState.Error        -> "⚠ Connection error"
            is TikTokLiveRepository.ConnectionState.Disconnected -> "Disconnected"
        }

        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val skipIntent = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).apply { action = ACTION_SKIP_TTS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val clearIntent = PendingIntent.getService(
            this, 2, Intent(this, StreamService::class.java).apply { action = ACTION_CLEAR_TTS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = PendingIntent.getService(
            this, 3, Intent(this, StreamService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamVibe Mobile")
            .setContentText(stateText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_next, "Skip TTS", skipIntent)
            .addAction(android.R.drawable.ic_delete,     "Clear TTS", clearIntent)
            .addAction(android.R.drawable.ic_media_pause,"Disconnect", disconnectIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stream Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps TTS and alerts running during streams"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
