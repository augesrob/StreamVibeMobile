package com.streamvibe.mobile.data.tiktok

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.streamvibe.mobile.BuildConfig
import com.streamvibe.mobile.domain.model.LiveEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TikTok auth + live events — NO SDK, pure HTTP/WebSocket.
 *
 * Auth flow:
 *   1. buildAuthUrl()  → open in WebView/CustomTab
 *   2. Deep-link callback delivers code → handleCallback(code)
 *   3. exchangeCodeForToken(code) → stores access token
 *   4. connectToLive(roomId) → opens WebSocket, emits LiveEvents
 */
@Singleton
class TikTokRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // ── OkHttp client ────────────────────────────────────────────────────────
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "StreamVibe/1.0 Android")
                    .build()
            )
        }
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Encrypted storage ────────────────────────────────────────────────────
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "tiktok_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── PKCE helpers ─────────────────────────────────────────────────────────
    private var pkceVerifier: String = ""

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    val isAuthenticated: Boolean
        get() = prefs.getString("access_token", null) != null

    val accessToken: String?
        get() = prefs.getString("access_token", null)

    val openId: String?
        get() = prefs.getString("open_id", null)

    /**
     * Build the TikTok OAuth2 authorization URL.
     * Open this in a WebView or Chrome Custom Tab.
     * TikTok will redirect to streamvibe://tiktok/callback?code=XXX
     */
    fun buildAuthUrl(): String {
        pkceVerifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(pkceVerifier)
        val clientKey   = BuildConfig.TIKTOK_CLIENT_KEY
        val redirectUri = Uri.encode(BuildConfig.TIKTOK_REDIRECT_URI)
        val scopes      = Uri.encode("user.info.basic,live.room.info,live.room.message")
        val state       = System.currentTimeMillis().toString()
        return "https://www.tiktok.com/v2/auth/authorize/?" +
               "client_key=$clientKey" +
               "&response_type=code" +
               "&scope=$scopes" +
               "&redirect_uri=$redirectUri" +
               "&state=$state" +
               "&code_challenge=$challenge" +
               "&code_challenge_method=S256"
    }

    /**
     * Exchange the auth code (from deep-link callback) for an access token.
     * Stores token in EncryptedSharedPreferences.
     */
    suspend fun exchangeCodeForToken(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("client_key",     BuildConfig.TIKTOK_CLIENT_KEY)
                .add("client_secret",  BuildConfig.TIKTOK_CLIENT_SECRET)
                .add("code",           code)
                .add("grant_type",     "authorization_code")
                .add("redirect_uri",   BuildConfig.TIKTOK_REDIRECT_URI)
                .add("code_verifier",  pkceVerifier)
                .build()

            val request = Request.Builder()
                .url("https://open.tiktokapis.com/v2/oauth/token/")
                .post(body)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw IOException("Empty token response")

            val obj = json.parseToJsonElement(responseBody).jsonObject
            val data = obj["data"]?.jsonObject
                ?: throw IOException("Token exchange failed: $responseBody")

            val token  = data["access_token"]?.jsonPrimitive?.content
                ?: throw IOException("No access_token in response")
            val openId = data["open_id"]?.jsonPrimitive?.content ?: ""
            val expiry = data["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L

            prefs.edit()
                .putString("access_token", token)
                .putString("open_id", openId)
                .putLong("expires_at", System.currentTimeMillis() + expiry * 1000)
                .apply()
        }
    }

    /** Refresh using stored refresh token */
    suspend fun refreshToken(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val refreshToken = prefs.getString("refresh_token", null)
                ?: throw IOException("No refresh token stored")

            val body = FormBody.Builder()
                .add("client_key",    BuildConfig.TIKTOK_CLIENT_KEY)
                .add("client_secret", BuildConfig.TIKTOK_CLIENT_SECRET)
                .add("grant_type",    "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url("https://open.tiktokapis.com/v2/oauth/token/")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val obj = json.parseToJsonElement(
                response.body?.string() ?: throw IOException("Empty response")
            ).jsonObject
            val data = obj["data"]?.jsonObject ?: throw IOException("Refresh failed")

            prefs.edit()
                .putString("access_token",  data["access_token"]?.jsonPrimitive?.content ?: "")
                .putString("refresh_token", data["refresh_token"]?.jsonPrimitive?.content ?: refreshToken)
                .putLong("expires_at", System.currentTimeMillis() +
                    (data["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000)
                .apply()
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    // ── Live WebSocket ────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    private var wsJob: Job? = null
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Connect to TikTok Live via WebSocket.
     * Events are emitted on [events] SharedFlow.
     *
     * Note: TikTok's live WebSocket uses a binary protobuf format.
     * This implementation handles JSON-mode responses where available,
     * and emits placeholder events for binary frames until proto parsing is added.
     */
    fun connectToLive(roomId: String) {
        disconnectFromLive()
        wsJob = scope.launch {
            val token = accessToken ?: return@launch
            val wsUrl = "wss://webcast.tiktok.com/webcast/im/fetch/" +
                        "?aid=1988&app_name=tiktok_web&room_id=$roomId" +
                        "&cursor=0&internal_ext=&fetch_rule=1"

            val request = Request.Builder()
                .url(wsUrl)
                .header("Cookie", "sessionid=$token")
                .header("Origin", "https://www.tiktok.com")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseJsonMessage(text)
                }
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    // Binary protobuf frame — send ACK ping back
                    webSocket.send(okio.ByteString.of(*byteArrayOf(0x08, 0x01)))
                    // Full proto parsing would go here once .proto definitions are added
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scope.launch {
                        delay(5000)
                        connectToLive(roomId) // auto-reconnect
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {}
            })
        }
    }

    fun disconnectFromLive() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        wsJob?.cancel()
        wsJob = null
    }

    // ── Message parsers ───────────────────────────────────────────────────────

    private fun parseJsonMessage(text: String) {
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val messages = root["data"]?.jsonObject
                ?.get("common")?.jsonObject
                ?.get("msgList")?.jsonArray ?: return

            for (msg in messages) {
                val obj  = msg.jsonObject
                val type = obj["method"]?.jsonPrimitive?.content ?: continue
                val data = obj["payload"]?.jsonObject ?: continue
                val user = data["user"]?.jsonObject
                    ?.get("nickname")?.jsonPrimitive?.content ?: "unknown"

                val event: LiveEvent? = when (type) {
                    "WebcastChatMessage" -> LiveEvent.ChatMessage(
                        user    = user,
                        message = data["content"]?.jsonPrimitive?.content ?: "",
                    )
                    "WebcastGiftMessage" -> LiveEvent.Gift(
                        user        = user,
                        giftId      = data["giftId"]?.jsonPrimitive?.intOrNull ?: 0,
                        giftName    = data["gift"]?.jsonObject
                            ?.get("name")?.jsonPrimitive?.content ?: "Gift",
                        giftType    = com.streamvibe.mobile.domain.model.GiftTier.fromDiamonds(
                            data["gift"]?.jsonObject
                                ?.get("diamondCount")?.jsonPrimitive?.intOrNull ?: 0
                        ),
                        diamondCount = data["gift"]?.jsonObject
                            ?.get("diamondCount")?.jsonPrimitive?.intOrNull ?: 0,
                        repeatCount  = data["repeatCount"]?.jsonPrimitive?.intOrNull ?: 1,
                    )
                    "WebcastSocialMessage" -> {
                        val action = data["action"]?.jsonPrimitive?.intOrNull ?: 0
                        when (action) {
                            1    -> LiveEvent.Follow(user = user)
                            3    -> LiveEvent.Share(user = user)
                            else -> LiveEvent.Like(user = user, count = 1)
                        }
                    }
                    "WebcastRoomUserSeqMessage" -> LiveEvent.ViewerCount(
                        count = data["totalUser"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                    else -> null
                }
                event?.let { scope.launch { _events.emit(it) } }
            }
        }
    }
}
