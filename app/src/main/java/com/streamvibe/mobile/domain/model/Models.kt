package com.streamvibe.mobile.domain.model

import kotlinx.serialization.Serializable

// ── TikTok Auth ──────────────────────────────────────────────────────────────
data class TikTokUser(
    val openId: String,
    val displayName: String,
    val avatarUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)

// ── Live Events ───────────────────────────────────────────────────────────────
sealed class LiveEvent {
    abstract val id: String
    abstract val timestamp: Long
    abstract val user: String
    abstract val avatarUrl: String?

    data class ChatMessage(
        override val id: String,
        override val timestamp: Long,
        override val user: String,
        override val avatarUrl: String? = null,
        val message: String,
        val filteredMessage: String? = null, // post Claude-filter version
    ) : LiveEvent()

    data class Gift(
        override val id: String,
        override val timestamp: Long,
        override val user: String,
        override val avatarUrl: String? = null,
        val giftName: String,
        val giftId: String,
        val giftImageUrl: String? = null,
        val diamondCount: Int,
        val repeatCount: Int = 1,
        val giftType: GiftTier = GiftTier.fromDiamonds(diamondCount),
    ) : LiveEvent()

    data class Follow(
        override val id: String,
        override val timestamp: Long,
        override val user: String,
        override val avatarUrl: String? = null,
    ) : LiveEvent()

    data class Like(
        override val id: String,
        override val timestamp: Long,
        override val user: String,
        override val avatarUrl: String? = null,
        val likeCount: Int,
    ) : LiveEvent()

    data class Share(
        override val id: String,
        override val timestamp: Long,
        override val user: String,
        override val avatarUrl: String? = null,
    ) : LiveEvent()

    data class ViewerCount(
        override val id: String,
        override val timestamp: Long,
        override val user: String = "system",
        override val avatarUrl: String? = null,
        val count: Int,
    ) : LiveEvent()
}

enum class GiftTier(val label: String, val colorHex: String) {
    COMMON("Common", "#aaaaaa"),
    RARE("Rare", "#00f2ea"),
    EPIC("Epic", "#a29bfe"),
    LEGENDARY("Legendary", "#ffd700");

    companion object {
        fun fromDiamonds(d: Int) = when {
            d >= 1000 -> LEGENDARY
            d >= 100  -> EPIC
            d >= 20   -> RARE
            else      -> COMMON
        }
    }
}

// ── Goal ──────────────────────────────────────────────────────────────────────
@Serializable
data class StreamGoal(
    val id: String,
    val type: GoalType,
    val target: Int,
    val current: Int = 0,
    val enabled: Boolean = true,
    val label: String = type.defaultLabel,
)

@Serializable
enum class GoalType(val defaultLabel: String, val icon: String, val colorHex: String) {
    LIKES("Likes", "❤️", "#ff4d6d"),
    FOLLOWS("Follows", "👤", "#00f2ea"),
    DIAMONDS("Diamonds", "💎", "#a29bfe"),
    SHARES("Shares", "🔁", "#55efc4"),
    VIEWERS("Viewers", "👁️", "#ffd700"),
    COMMENTS("Comments", "💬", "#ff9f43"),
}

// ── TTS ───────────────────────────────────────────────────────────────────────
@Serializable
data class TtsSettings(
    val enabled: Boolean = true,
    val voiceProvider: VoiceProvider = VoiceProvider.ANDROID_BUILTIN,
    val androidVoiceIndex: Int = 0,
    val elevenLabsVoiceId: String = "",
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    // What to read
    val readGifts: Boolean = true,
    val readFollows: Boolean = true,
    val readChat: Boolean = false,
    val readShares: Boolean = false,
    // Filters
    val minDiamondsForTTS: Int = 5,
    val useClaudeFilter: Boolean = true,
    val blocklist: List<String> = emptyList(),
    val dedupeWindowMs: Long = 4000,
    val maxQueueSize: Int = 10,
    // Per-user voice overrides: map of username -> elevenLabsVoiceId
    val userVoiceMap: Map<String, String> = emptyMap(),
)

@Serializable
enum class VoiceProvider { ANDROID_BUILTIN, ELEVENLABS, GOOGLE_CLOUD }

@Serializable
data class ElevenLabsVoice(
    val voiceId: String,
    val name: String,
    val previewUrl: String? = null,
    val category: String = "premade",
)

// ── TTS Queue Item ────────────────────────────────────────────────────────────
data class TtsQueueItem(
    val id: String,
    val text: String,                  // raw
    val sanitizedText: String? = null, // post-filter
    val priority: Int = 0,             // higher = spoken sooner
    val voiceOverride: String? = null, // ElevenLabs voiceId
    val sourceEvent: LiveEvent? = null,
)

// ── Overlay Widget ────────────────────────────────────────────────────────────
@Serializable
data class OverlayWidget(
    val id: String,
    val type: WidgetType,
    val enabled: Boolean = true,
    val x: Float = 0f,     // 0-1 normalized screen position
    val y: Float = 0f,
    val scale: Float = 1f,
    val opacity: Float = 1f,
    val colorAccent: String = "#00f2ea",
)

@Serializable
enum class WidgetType {
    ALERT_BANNER,
    GOAL_BAR,
    CHAT_OVERLAY,
    VIEWER_COUNT,
    COIN_COUNTER,
    RECENT_GIFTERS,
}

// ── Stream Session ─────────────────────────────────────────────────────────────
data class StreamSession(
    val startedAt: Long,
    val totalLikes: Int = 0,
    val totalFollows: Int = 0,
    val totalDiamonds: Int = 0,
    val totalShares: Int = 0,
    val peakViewers: Int = 0,
    val currentViewers: Int = 0,
    val giftCount: Int = 0,
    val chatMessages: Int = 0,
)
