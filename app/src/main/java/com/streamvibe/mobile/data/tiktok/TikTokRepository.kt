package com.streamvibe.mobile.data.tiktok

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.streamvibe.mobile.BuildConfig
import com.streamvibe.mobile.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// TikTokAuthRepository
// Handles OAuth 2.0 Login Kit flow + token storage
// ─────────────────────────────────────────────────────────────────────────────
@Singleton
class TikTokAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val PREF_FILE = "streamvibe_secure"
        private const val KEY_ACCESS_TOKEN = "tt_access_token"
        private const val KEY_REFRESH_TOKEN = "tt_refresh_token"
        private const val KEY_OPEN_ID = "tt_open_id"
        private const val KEY_DISPLAY_NAME = "tt_display_name"
        private const val KEY_AVATAR_URL = "tt_avatar_url"
        private const val KEY_EXPIRES_AT = "tt_expires_at"

        private const val TOKEN_URL = "https://open.tiktokapis.com/v2/oauth/token/"
        private const val USER_URL  = "https://open.tiktokapis.com/v2/user/info/"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREF_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun isLoggedIn(): Boolean {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return false
        val expires = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return token.isNotBlank() && System.currentTimeMillis() < expires
    }

    fun getSavedUser(): TikTokUser? {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        return TikTokUser(
            openId       = prefs.getString(KEY_OPEN_ID, "") ?: "",
            displayName  = prefs.getString(KEY_DISPLAY_NAME, "") ?: "",
            avatarUrl    = prefs.getString(KEY_AVATAR_URL, "") ?: "",
            accessToken  = token,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "") ?: "",
            expiresAt    = prefs.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    // Called after TikTok SDK returns the auth code
    suspend fun exchangeCodeForToken(authCode: String): Result<TikTokUser> {
        return withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("client_key",     BuildConfig.TIKTOK_CLIENT_KEY)
                    put("client_secret",  BuildConfig.TIKTOK_CLIENT_SECRET)
                    put("code",           authCode)
                    put("grant_type",     "authorization_code")
                    put("redirect_uri",   BuildConfig.TIKTOK_REDIRECT_URI)
                }.toString()

                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val json = Json.parseToJsonElement(response.body!!.string()).jsonObject

                val accessToken  = json["access_token"]!!.jsonPrimitive.content
                val refreshToken = json["refresh_token"]!!.jsonPrimitive.content
                val expiresIn    = json["expires_in"]!!.jsonPrimitive.long
                val openId       = json["open_id"]!!.jsonPrimitive.content

                val user = fetchUserInfo(accessToken, openId)

                prefs.edit().apply {
                    putString(KEY_ACCESS_TOKEN, accessToken)
                    putString(KEY_REFRESH_TOKEN, refreshToken)
                    putString(KEY_OPEN_ID, openId)
                    putString(KEY_DISPLAY_NAME, user.displayName)
                    putString(KEY_AVATAR_URL, user.avatarUrl)
                    putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000)
                    apply()
                }

                Result.success(user.copy(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = System.currentTimeMillis() + expiresIn * 1000,
                ))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun fetchUserInfo(accessToken: String, openId: String): TikTokUser {
        val request = Request.Builder()
            .url("$USER_URL?fields=open_id,display_name,avatar_url")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()
        val response = httpClient.newCall(request).execute()
        val json = Json.parseToJsonElement(response.body!!.string())
            .jsonObject["data"]!!.jsonObject["user"]!!.jsonObject
        return TikTokUser(
            openId       = openId,
            displayName  = json["display_name"]?.jsonPrimitive?.content ?: "Unknown",
            avatarUrl    = json["avatar_url"]?.jsonPrimitive?.content ?: "",
            accessToken  = accessToken,
            refreshToken = "",
            expiresAt    = 0L,
        )
    }

    fun logout() {
        prefs.edit().clear().apply()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TikTokLiveRepository
// Connects to TikTok Live Events API via WebSocket
// Official endpoint: wss://webcast.tiktok.com/webcast/im/fetch/
// ─────────────────────────────────────────────────────────────────────────────
@Singleton
class TikTokLiveRepository @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val _events = MutableSharedFlow<LiveEvent>(
        extraBufferCapacity = 200,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val roomId: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    // Connect using the TikTok Live WebSocket API
    // roomId is obtained from the user's live room URL (fetched via TikTok API)
    fun connect(accessToken: String, roomId: String) {
        _connectionState.value = ConnectionState.Connecting

        val url = "wss://webcast.tiktok.com/webcast/im/fetch/" +
            "?aid=1988&app_name=tiktok_web&browser_language=en&version_code=180800" +
            "&room_id=$roomId&cursor=&internal_ext=&fetch_interval=3000"

        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", "sessionid=$accessToken")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected(roomId)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch { parseAndEmit(text) }
            }

            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                // TikTok sends protobuf — parse binary WebcastResponse
                scope.launch { parseBinaryAndEmit(bytes.toByteArray()) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
                scheduleReconnect(accessToken, roomId)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun scheduleReconnect(accessToken: String, roomId: String) {
        scope.launch {
            delay(5000)
            if (_connectionState.value !is ConnectionState.Connected) {
                connect(accessToken, roomId)
            }
        }
    }

    // ── Parsers ──────────────────────────────────────────────────────────────
    // NOTE: TikTok uses protobuf. In production you'll need to generate proto
    // bindings from tiktok-live-connector's proto definitions.
    // These parsers handle the JSON fallback / partial text events.

    private fun parseAndEmit(text: String) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return
            val data = obj["data"]?.jsonObject ?: return

            val event = when (type) {
                "WebcastChatMessage"  -> parseChatMessage(data)
                "WebcastGiftMessage"  -> parseGiftMessage(data)
                "WebcastSocialMessage" -> parseSocialMessage(data)
                "WebcastLikeMessage"  -> parseLikeMessage(data)
                "WebcastRoomUserSeqMessage" -> parseViewerCount(data)
                else -> null
            }
            event?.let { scope.launch { _events.emit(it) } }
        } catch (_: Exception) {}
    }

    private fun parseBinaryAndEmit(bytes: ByteArray) {
        // Protobuf parsing requires generated classes from TikTok's .proto files.
        // Placeholder: in production integrate tiktok-live-connector's proto defs.
        // For now emit a placeholder — replace with actual protobuf decode.
    }

    private fun parseChatMessage(d: JsonObject): LiveEvent.ChatMessage {
        val user = d["user"]?.jsonObject
        return LiveEvent.ChatMessage(
            id        = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            user      = user?.get("nickname")?.jsonPrimitive?.content ?: "Unknown",
            avatarUrl = user?.get("profilePictureUrl")?.jsonPrimitive?.content,
            message   = d["comment"]?.jsonPrimitive?.content ?: "",
        )
    }

    private fun parseGiftMessage(d: JsonObject): LiveEvent.Gift {
        val user = d["user"]?.jsonObject
        val gift = d["gift"]?.jsonObject
        return LiveEvent.Gift(
            id           = UUID.randomUUID().toString(),
            timestamp    = System.currentTimeMillis(),
            user         = user?.get("nickname")?.jsonPrimitive?.content ?: "Unknown",
            avatarUrl    = user?.get("profilePictureUrl")?.jsonPrimitive?.content,
            giftName     = gift?.get("name")?.jsonPrimitive?.content ?: "Gift",
            giftId       = gift?.get("id")?.jsonPrimitive?.content ?: "0",
            giftImageUrl = gift?.get("image")?.jsonObject?.get("urlList")
                ?.jsonArray?.firstOrNull()?.jsonPrimitive?.content,
            diamondCount = gift?.get("diamondCount")?.jsonPrimitive?.int ?: 1,
            repeatCount  = d["repeatCount"]?.jsonPrimitive?.int ?: 1,
        )
    }

    private fun parseSocialMessage(d: JsonObject): LiveEvent {
        val user = d["user"]?.jsonObject
        val action = d["displayType"]?.jsonPrimitive?.content ?: ""
        val userId = UUID.randomUUID().toString()
        val username = user?.get("nickname")?.jsonPrimitive?.content ?: "Unknown"
        val avatar = user?.get("profilePictureUrl")?.jsonPrimitive?.content
        val ts = System.currentTimeMillis()

        return if (action.contains("share", ignoreCase = true)) {
            LiveEvent.Share(id = userId, timestamp = ts, user = username, avatarUrl = avatar)
        } else {
            LiveEvent.Follow(id = userId, timestamp = ts, user = username, avatarUrl = avatar)
        }
    }

    private fun parseLikeMessage(d: JsonObject): LiveEvent.Like {
        val user = d["user"]?.jsonObject
        return LiveEvent.Like(
            id        = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            user      = user?.get("nickname")?.jsonPrimitive?.content ?: "Unknown",
            avatarUrl = user?.get("profilePictureUrl")?.jsonPrimitive?.content,
            likeCount = d["count"]?.jsonPrimitive?.int ?: 1,
        )
    }

    private fun parseViewerCount(d: JsonObject): LiveEvent.ViewerCount {
        return LiveEvent.ViewerCount(
            id        = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            count     = d["viewerCount"]?.jsonPrimitive?.int ?: 0,
        )
    }
}
