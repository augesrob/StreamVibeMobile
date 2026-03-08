package com.streamvibe.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvibe.mobile.data.tiktok.*
import com.streamvibe.mobile.data.tts.*
import com.streamvibe.mobile.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    val authRepo: TikTokAuthRepository,
    val liveRepo: TikTokLiveRepository,
    val ttsEngine: TtsEngine,
) : ViewModel() {

    // ── Auth State ────────────────────────────────────────────────────────────
    private val _user = MutableStateFlow<TikTokUser?>(null)
    val user: StateFlow<TikTokUser?> = _user.asStateFlow()

    val connectionState = liveRepo.connectionState

    // ── Session ───────────────────────────────────────────────────────────────
    private val _session = MutableStateFlow<StreamSession?>(null)
    val session: StateFlow<StreamSession?> = _session.asStateFlow()

    // ── Events ────────────────────────────────────────────────────────────────
    private val _chatMessages = MutableStateFlow<List<LiveEvent.ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<LiveEvent.ChatMessage>> = _chatMessages.asStateFlow()

    private val _alertLog = MutableStateFlow<List<LiveEvent>>(emptyList())
    val alertLog: StateFlow<List<LiveEvent>> = _alertLog.asStateFlow()

    private val _activeAlert = MutableStateFlow<LiveEvent?>(null)
    val activeAlert: StateFlow<LiveEvent?> = _activeAlert.asStateFlow()

    // ── Goals ─────────────────────────────────────────────────────────────────
    private val _goals = MutableStateFlow(defaultGoals())
    val goals: StateFlow<List<StreamGoal>> = _goals.asStateFlow()

    // ── TTS ───────────────────────────────────────────────────────────────────
    private val _ttsSettings = MutableStateFlow(TtsSettings())
    val ttsSettings: StateFlow<TtsSettings> = _ttsSettings.asStateFlow()

    val ttsQueue = ttsEngine.queue
    val ttsSpeaking = ttsEngine.isSpeaking
    val ttsCurrentItem = ttsEngine.currentItem

    // ── Overlay Widgets ───────────────────────────────────────────────────────
    private val _widgets = MutableStateFlow(defaultWidgets())
    val widgets: StateFlow<List<OverlayWidget>> = _widgets.asStateFlow()

    // ── ElevenLabs Voices ─────────────────────────────────────────────────────
    private val _elevenLabsVoices = MutableStateFlow<List<ElevenLabsVoice>>(emptyList())
    val elevenLabsVoices: StateFlow<List<ElevenLabsVoice>> = _elevenLabsVoices.asStateFlow()

    // ── Android TTS Voices ────────────────────────────────────────────────────
    val androidVoices: List<android.speech.tts.Voice>
        get() = ttsEngine.getAvailableAndroidVoices()

    // ─────────────────────────────────────────────────────────────────────────

    init {
        _user.value = authRepo.getSavedUser()
        observeLiveEvents()
        viewModelScope.launch {
            _elevenLabsVoices.value = ttsEngine.fetchElevenLabsVoices()
        }
    }

    // ── TikTok Auth ───────────────────────────────────────────────────────────

    fun onTikTokAuthCode(code: String) {
        viewModelScope.launch {
            authRepo.exchangeCodeForToken(code).onSuccess { user ->
                _user.value = user
            }
        }
    }

    fun logout() {
        authRepo.logout()
        _user.value = null
        liveRepo.disconnect()
        _session.value = null
        _chatMessages.value = emptyList()
        _alertLog.value = emptyList()
    }

    // ── Live Connection ───────────────────────────────────────────────────────

    fun connectToLive(roomId: String) {
        val token = _user.value?.accessToken ?: return
        _session.value = StreamSession(startedAt = System.currentTimeMillis())
        liveRepo.connect(token, roomId)
    }

    fun disconnectFromLive() {
        liveRepo.disconnect()
        _session.value = null
    }

    // ── Event Observer ────────────────────────────────────────────────────────

    private fun observeLiveEvents() {
        viewModelScope.launch {
            liveRepo.events.collect { event ->
                processEvent(event)
            }
        }
    }

    private fun processEvent(event: LiveEvent) {
        // Update session stats
        _session.update { s ->
            s?.let { session ->
                when (event) {
                    is LiveEvent.Gift       -> session.copy(totalDiamonds = session.totalDiamonds + event.diamondCount * event.repeatCount, giftCount = session.giftCount + 1)
                    is LiveEvent.Follow     -> session.copy(totalFollows = session.totalFollows + 1)
                    is LiveEvent.Like       -> session.copy(totalLikes = session.totalLikes + event.likeCount)
                    is LiveEvent.Share      -> session.copy(totalShares = session.totalShares + 1)
                    is LiveEvent.ViewerCount -> session.copy(currentViewers = event.count, peakViewers = maxOf(session.peakViewers, event.count))
                    is LiveEvent.ChatMessage -> session.copy(chatMessages = session.chatMessages + 1)
                }
            }
        }

        // Update goal progress
        _goals.update { goals ->
            goals.map { goal ->
                when {
                    goal.type == GoalType.LIKES    && event is LiveEvent.Like    -> goal.copy(current = goal.current + event.likeCount)
                    goal.type == GoalType.FOLLOWS  && event is LiveEvent.Follow  -> goal.copy(current = goal.current + 1)
                    goal.type == GoalType.DIAMONDS && event is LiveEvent.Gift    -> goal.copy(current = goal.current + event.diamondCount)
                    goal.type == GoalType.SHARES   && event is LiveEvent.Share   -> goal.copy(current = goal.current + 1)
                    goal.type == GoalType.VIEWERS  && event is LiveEvent.ViewerCount -> goal.copy(current = event.count)
                    goal.type == GoalType.COMMENTS && event is LiveEvent.ChatMessage -> goal.copy(current = goal.current + 1)
                    else -> goal
                }
            }
        }

        // Update chat
        if (event is LiveEvent.ChatMessage) {
            _chatMessages.update { (it + event).takeLast(150) }
        }

        // Update alert log (gifts, follows, shares only)
        if (event is LiveEvent.Gift || event is LiveEvent.Follow || event is LiveEvent.Share) {
            _alertLog.update { (listOf(event) + it).take(100) }
            showActiveAlert(event)
        }

        // TTS
        TtsEventMapper.map(event, _ttsSettings.value)?.let { item ->
            ttsEngine.enqueue(item, _ttsSettings.value)
        }
    }

    private fun showActiveAlert(event: LiveEvent) {
        viewModelScope.launch {
            _activeAlert.value = event
            delay(3500)
            if (_activeAlert.value?.id == event.id) _activeAlert.value = null
        }
    }

    // ── TTS Controls ──────────────────────────────────────────────────────────

    fun updateTtsSettings(settings: TtsSettings) {
        _ttsSettings.value = settings
        ttsEngine.setAndroidVoice(
            voiceName = androidVoices.getOrNull(settings.androidVoiceIndex)?.name ?: "",
            rate      = settings.rate,
            pitch     = settings.pitch,
        )
    }

    fun skipTts()  = ttsEngine.skipCurrent()
    fun clearTts() = ttsEngine.clearQueue()
    fun pauseTts() = ttsEngine.pause()

    fun addBlocklistWord(word: String) {
        _ttsSettings.update { it.copy(blocklist = it.blocklist + word) }
    }

    fun removeBlocklistWord(word: String) {
        _ttsSettings.update { it.copy(blocklist = it.blocklist - word) }
    }

    fun setUserVoice(username: String, voiceId: String) {
        _ttsSettings.update { it.copy(userVoiceMap = it.userVoiceMap + (username to voiceId)) }
    }

    fun removeUserVoice(username: String) {
        _ttsSettings.update { it.copy(userVoiceMap = it.userVoiceMap - username) }
    }

    // ── Goal Management ───────────────────────────────────────────────────────

    fun updateGoal(goalId: String, target: Int? = null, enabled: Boolean? = null) {
        _goals.update { goals ->
            goals.map { goal ->
                if (goal.id == goalId) goal.copy(
                    target  = target  ?: goal.target,
                    enabled = enabled ?: goal.enabled,
                ) else goal
            }
        }
    }

    fun resetGoals() {
        _goals.update { goals -> goals.map { it.copy(current = 0) } }
    }

    // ── Widget Management ─────────────────────────────────────────────────────

    fun updateWidget(widgetId: String, enabled: Boolean? = null, x: Float? = null, y: Float? = null, colorAccent: String? = null) {
        _widgets.update { widgets ->
            widgets.map { w ->
                if (w.id == widgetId) w.copy(
                    enabled     = enabled     ?: w.enabled,
                    x           = x           ?: w.x,
                    y           = y           ?: w.y,
                    colorAccent = colorAccent ?: w.colorAccent,
                ) else w
            }
        }
    }

    override fun onCleared() {
        ttsEngine.shutdown()
        super.onCleared()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
private fun defaultGoals() = listOf(
    StreamGoal(id = UUID.randomUUID().toString(), type = GoalType.LIKES,    target = 500,  enabled = true),
    StreamGoal(id = UUID.randomUUID().toString(), type = GoalType.FOLLOWS,  target = 100,  enabled = true),
    StreamGoal(id = UUID.randomUUID().toString(), type = GoalType.DIAMONDS, target = 5000, enabled = false),
    StreamGoal(id = UUID.randomUUID().toString(), type = GoalType.SHARES,   target = 50,   enabled = false),
    StreamGoal(id = UUID.randomUUID().toString(), type = GoalType.VIEWERS,  target = 500,  enabled = false),
    StreamGoal(id = UUID.randomUUID().toString(), type = GoalType.COMMENTS, target = 1000, enabled = false),
)

private fun defaultWidgets() = listOf(
    OverlayWidget(id = "alert",   type = WidgetType.ALERT_BANNER,   enabled = true,  y = 0.05f, colorAccent = "#00f2ea"),
    OverlayWidget(id = "goal",    type = WidgetType.GOAL_BAR,       enabled = true,  y = 0.12f, colorAccent = "#ff4d6d"),
    OverlayWidget(id = "chat",    type = WidgetType.CHAT_OVERLAY,   enabled = false, y = 0.50f, colorAccent = "#ffffff"),
    OverlayWidget(id = "viewers", type = WidgetType.VIEWER_COUNT,   enabled = true,  x = 0.80f, y = 0.05f, colorAccent = "#ffd700"),
    OverlayWidget(id = "coins",   type = WidgetType.COIN_COUNTER,   enabled = false, x = 0.80f, y = 0.12f, colorAccent = "#a29bfe"),
    OverlayWidget(id = "gifters", type = WidgetType.RECENT_GIFTERS, enabled = false, y = 0.80f, colorAccent = "#ff9f43"),
)
