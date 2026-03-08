package com.streamvibe.mobile.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.streamvibe.mobile.BuildConfig
import com.streamvibe.mobile.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// TtsEngine
//
// Pipeline: raw text → Claude sanitizer → blocklist → dedupe → ElevenLabs/Android TTS
// ─────────────────────────────────────────────────────────────────────────────
@Singleton
class TtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Queue state
    private val _queue = MutableStateFlow<List<TtsQueueItem>>(emptyList())
    val queue: StateFlow<List<TtsQueueItem>> = _queue.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentItem = MutableStateFlow<TtsQueueItem?>(null)
    val currentItem: StateFlow<TtsQueueItem?> = _currentItem.asStateFlow()

    // Android TTS
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    private val speakCompletionJob = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    // Dedupe tracking: text hash → last spoken timestamp
    private val recentlySpoken = ConcurrentHashMap<Int, Long>()

    // ElevenLabs voice cache: voiceId+text hash → audio file
    private val audioCache = ConcurrentHashMap<String, File>()

    init {
        initAndroidTts()
        startQueueProcessor()
    }

    private fun initAndroidTts() {
        androidTts = TextToSpeech(context) { status ->
            androidTtsReady = (status == TextToSpeech.SUCCESS)
        }
        androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                _isSpeaking.value = true
            }
            override fun onDone(utteranceId: String) {
                speakCompletionJob[utteranceId]?.complete(Unit)
                speakCompletionJob.remove(utteranceId)
                _isSpeaking.value = false
            }
            override fun onError(utteranceId: String) {
                speakCompletionJob[utteranceId]?.complete(Unit)
                speakCompletionJob.remove(utteranceId)
                _isSpeaking.value = false
            }
        })
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    fun enqueue(item: TtsQueueItem, settings: TtsSettings) {
        scope.launch {
            // 1. Blocklist check
            if (settings.blocklist.any { blocked ->
                item.text.contains(blocked, ignoreCase = true)
            }) return@launch

            // 2. Dedupe check
            val textHash = item.text.lowercase().trim().hashCode()
            val lastSpoken = recentlySpoken[textHash] ?: 0L
            if (System.currentTimeMillis() - lastSpoken < settings.dedupeWindowMs) return@launch
            recentlySpoken[textHash] = System.currentTimeMillis()

            // 3. Claude filter (async — add sanitized version when ready)
            val itemWithFilter = if (settings.useClaudeFilter) {
                item.copy(sanitizedText = claudeFilter(item.text))
            } else {
                item
            }

            // 4. Add to queue (respect max queue size)
            _queue.update { current ->
                if (current.size >= settings.maxQueueSize) {
                    // Drop lowest priority item that isn't currently playing
                    current.drop(1) + itemWithFilter
                } else {
                    current + itemWithFilter
                }
            }
        }
    }

    fun skipCurrent() {
        androidTts?.stop()
        _isSpeaking.value = false
        _currentItem.value = null
    }

    fun clearQueue() {
        _queue.update { emptyList() }
        skipCurrent()
    }

    fun pause() {
        androidTts?.stop()
        _isSpeaking.value = false
    }

    fun getAvailableAndroidVoices(): List<android.speech.tts.Voice> {
        return androidTts?.voices?.toList() ?: emptyList()
    }

    // ─── Queue Processor ─────────────────────────────────────────────────────

    private fun startQueueProcessor() {
        scope.launch {
            while (isActive) {
                if (_isSpeaking.value || _queue.value.isEmpty()) {
                    delay(150)
                    continue
                }
                val item = _queue.value.firstOrNull() ?: continue
                _queue.update { it.drop(1) }
                _currentItem.value = item
                speakItem(item)
                delay(300) // gap between items
            }
        }
    }

    private suspend fun speakItem(item: TtsQueueItem) {
        val text = item.sanitizedText ?: item.text
        if (text.isBlank()) return

        _isSpeaking.value = true

        // If voice override is ElevenLabs voiceId, use ElevenLabs
        if (item.voiceOverride != null || BuildConfig.ELEVENLABS_API_KEY.isNotBlank()) {
            val voiceId = item.voiceOverride ?: "21m00Tcm4TlvDq8ikWAM" // default ElevenLabs voice
            speakElevenLabs(text, voiceId)
        } else {
            speakAndroid(text)
        }
        _currentItem.value = null
    }

    // ─── Android TTS ─────────────────────────────────────────────────────────

    private suspend fun speakAndroid(text: String) {
        if (!androidTtsReady) return
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        speakCompletionJob[utteranceId] = completion

        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        withTimeoutOrNull(10_000) { completion.await() }
        _isSpeaking.value = false
    }

    fun setAndroidVoice(voiceName: String, rate: Float, pitch: Float) {
        androidTts?.voices?.find { it.name == voiceName }?.let {
            androidTts?.voice = it
        }
        androidTts?.setSpeechRate(rate)
        androidTts?.setPitch(pitch)
    }

    // ─── ElevenLabs TTS ──────────────────────────────────────────────────────

    private suspend fun speakElevenLabs(text: String, voiceId: String) {
        try {
            val cacheKey = "${voiceId}_${text.hashCode()}"
            val cachedFile = audioCache[cacheKey]

            val audioBytes: ByteArray = if (cachedFile?.exists() == true) {
                cachedFile.readBytes()
            } else {
                val requestBody = buildJsonObject {
                    put("text", text)
                    put("model_id", "eleven_turbo_v2")
                    putJsonObject("voice_settings") {
                        put("stability", 0.5)
                        put("similarity_boost", 0.75)
                    }
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                    .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                    .addHeader("Accept", "audio/mpeg")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val bytes = response.body?.bytes() ?: return

                // Cache to disk
                val file = File(context.cacheDir, "tts_$cacheKey.mp3")
                file.writeBytes(bytes)
                audioCache[cacheKey] = file
                bytes
            }

            // Play MP3 bytes via MediaPlayer
            playMp3Bytes(audioBytes)

        } catch (e: Exception) {
            // Fallback to Android TTS
            speakAndroid(text)
        }
    }

    private suspend fun playMp3Bytes(bytes: ByteArray) {
        val completion = CompletableDeferred<Unit>()
        val tempFile = File.createTempFile("tts_play", ".mp3", context.cacheDir)
        tempFile.writeBytes(bytes)

        withContext(Dispatchers.Main) {
            val player = android.media.MediaPlayer()
            try {
                player.setDataSource(tempFile.absolutePath)
                player.prepare()
                player.setOnCompletionListener {
                    _isSpeaking.value = false
                    completion.complete(Unit)
                    player.release()
                    tempFile.delete()
                }
                player.start()
            } catch (e: Exception) {
                player.release()
                completion.complete(Unit)
            }
        }
        withTimeoutOrNull(30_000) { completion.await() }
    }

    // ─── ElevenLabs Voice List ────────────────────────────────────────────────

    suspend fun fetchElevenLabsVoices(): List<ElevenLabsVoice> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/voices")
                    .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                    .build()
                val response = httpClient.newCall(request).execute()
                val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
                json["voices"]?.jsonArray?.map { v ->
                    val voice = v.jsonObject
                    ElevenLabsVoice(
                        voiceId    = voice["voice_id"]!!.jsonPrimitive.content,
                        name       = voice["name"]!!.jsonPrimitive.content,
                        previewUrl = voice["preview_url"]?.jsonPrimitive?.content,
                        category   = voice["category"]?.jsonPrimitive?.content ?: "premade",
                    )
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
    }

    // ─── Claude TTS Filter ───────────────────────────────────────────────────
    //
    // Sanitizes TTS text before speaking:
    // - Removes spam / repeated characters
    // - Converts emojis to natural language
    // - Normalizes numbers in usernames (xX99Xx → "xX ninety nine Xx")
    // - Removes URLs, @mentions that would sound weird
    // - Detects and skips non-English gibberish
    //
    private suspend fun claudeFilter(rawText: String): String {
        if (BuildConfig.CLAUDE_API_KEY.isBlank()) return rawText
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """You are a TTS pre-processor for a live streaming app. 
Clean the following message so it sounds natural when spoken aloud by a text-to-speech engine.
Rules:
- Convert emojis to natural words (🔥 = "fire", ❤️ = "heart")  
- Remove URLs completely
- Convert @username numbers naturally (xX99Xx = "x x ninety nine x x")
- Remove ALL-CAPS spam or repeated characters (LLLLLLL, aaaaaaa)  
- If the message is pure spam/gibberish, return exactly: SKIP
- Keep the meaning intact — don't rewrite, just clean
- Return ONLY the cleaned text, nothing else

Message: $rawText"""

                val requestBody = buildJsonObject {
                    put("model", "claude-haiku-4-5-20251001")
                    put("max_tokens", 200)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            put("content", prompt)
                        }
                    }
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", BuildConfig.CLAUDE_API_KEY)
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val json = Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(response.body!!.string()).jsonObject

                val filtered = json["content"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content
                    ?: rawText

                if (filtered.trim().uppercase() == "SKIP") "" else filtered.trim()

            } catch (_: Exception) { rawText }
        }
    }

    fun shutdown() {
        androidTts?.shutdown()
        scope.cancel()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TtsEventMapper
// Converts LiveEvents → TtsQueueItems based on current settings
// ─────────────────────────────────────────────────────────────────────────────
object TtsEventMapper {
    fun map(event: LiveEvent, settings: TtsSettings): TtsQueueItem? {
        if (!settings.enabled) return null

        return when (event) {
            is LiveEvent.Gift -> {
                if (!settings.readGifts) return null
                if (event.diamondCount < settings.minDiamondsForTTS) return null
                val text = buildGiftText(event)
                val voiceOverride = settings.userVoiceMap[event.user]
                TtsQueueItem(
                    id            = event.id,
                    text          = text,
                    priority      = event.giftType.ordinal + 1, // Legendary = highest
                    voiceOverride = voiceOverride,
                    sourceEvent   = event,
                )
            }

            is LiveEvent.Follow -> {
                if (!settings.readFollows) return null
                TtsQueueItem(
                    id            = event.id,
                    text          = "${event.user} just followed!",
                    priority      = 1,
                    voiceOverride = settings.userVoiceMap[event.user],
                    sourceEvent   = event,
                )
            }

            is LiveEvent.ChatMessage -> {
                if (!settings.readChat) return null
                TtsQueueItem(
                    id            = event.id,
                    text          = "${event.user} says: ${event.message}",
                    priority      = 0,
                    voiceOverride = settings.userVoiceMap[event.user],
                    sourceEvent   = event,
                )
            }

            is LiveEvent.Share -> {
                if (!settings.readShares) return null
                TtsQueueItem(
                    id            = event.id,
                    text          = "${event.user} shared the stream!",
                    priority      = 1,
                    voiceOverride = settings.userVoiceMap[event.user],
                    sourceEvent   = event,
                )
            }

            else -> null
        }
    }

    private fun buildGiftText(gift: LiveEvent.Gift): String {
        val repeat = if (gift.repeatCount > 1) " times ${gift.repeatCount}" else ""
        return "${gift.user} sent ${gift.giftName}$repeat for ${gift.diamondCount} diamonds!"
    }
}
