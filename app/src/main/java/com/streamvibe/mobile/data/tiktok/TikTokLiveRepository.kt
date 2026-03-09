package com.streamvibe.mobile.data.tiktok

import com.streamvibe.mobile.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a WebSocket connection to TikTok Live.
 * Emits [LiveEvent]s on [events] SharedFlow.
 * No SDK — pure OkHttp WebSocket.
 */
@Singleton
class TikTokLiveRepository @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Connection state ──────────────────────────────────────────────────────
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting   : ConnectionState()
        data class Connected(val roomId: String) : ConnectionState()
        data class Error(val message: String)    : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var currentRoomId: String = ""
    private var currentToken: String = ""


    // ── Public API ────────────────────────────────────────────────────────────

    fun connect(token: String, roomId: String) {
        currentToken  = token
        currentRoomId = roomId
        _connectionState.value = ConnectionState.Connecting
        openWebSocket()
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun openWebSocket() {
        val url = "wss://webcast.tiktok.com/webcast/im/fetch/" +
                  "?aid=1988&app_name=tiktok_web" +
                  "&room_id=$currentRoomId&cursor=0&fetch_rule=1"

        val request = Request.Builder()
            .url(url)
            .header("Cookie",  "sessionid=$currentToken")
            .header("Origin",  "https://www.tiktok.com")
            .header("User-Agent", "Mozilla/5.0 StreamVibe/1.0")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected(currentRoomId)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Binary protobuf frame — ACK it to keep the stream alive
                ws.send(ByteString.of(0x08, 0x01))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
                // Auto-reconnect after 5 s
                scope.launch {
                    delay(5_000)
                    if (_connectionState.value is ConnectionState.Error) {
                        openWebSocket()
                    }
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
            }
        })
    }


    // ── Message parsing ───────────────────────────────────────────────────────

    private fun parseMessage(text: String) {
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val msgs = root["data"]?.jsonObject
                ?.get("common")?.jsonObject
                ?.get("msgList")?.jsonArray ?: return

            for (msg in msgs) {
                val obj    = msg.jsonObject
                val type   = obj["method"]?.jsonPrimitive?.content ?: continue
                val data   = obj["payload"]?.jsonObject ?: continue
                val user   = data["user"]?.jsonObject
                               ?.get("nickname")?.jsonPrimitive?.content ?: "unknown"
                val avatar = data["user"]?.jsonObject
                               ?.get("avatarThumb")?.jsonObject
                               ?.get("urlList")?.jsonArray
                               ?.firstOrNull()?.jsonPrimitive?.content

                val event: LiveEvent? = when (type) {
                    "WebcastChatMessage" -> LiveEvent.ChatMessage(
                        id        = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        user      = user,
                        avatarUrl = avatar,
                        message   = data["content"]?.jsonPrimitive?.content ?: "",
                    )
                    "WebcastGiftMessage" -> {
                        val diamonds = data["gift"]?.jsonObject
                            ?.get("diamondCount")?.jsonPrimitive?.intOrNull ?: 0
                        LiveEvent.Gift(
                            id           = UUID.randomUUID().toString(),
                            timestamp    = System.currentTimeMillis(),
                            user         = user,
                            avatarUrl    = avatar,
                            giftName     = data["gift"]?.jsonObject
                                             ?.get("name")?.jsonPrimitive?.content ?: "Gift",
                            giftId       = data["giftId"]?.jsonPrimitive?.content ?: "",
                            diamondCount = diamonds,
                            repeatCount  = data["repeatCount"]?.jsonPrimitive?.intOrNull ?: 1,
                            giftType     = GiftTier.fromDiamonds(diamonds),
                        )
                    }
                    "WebcastSocialMessage" -> {
                        when (data["action"]?.jsonPrimitive?.intOrNull ?: 0) {
                            1    -> LiveEvent.Follow(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                user = user, avatarUrl = avatar,
                            )
                            3    -> LiveEvent.Share(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                user = user, avatarUrl = avatar,
                            )
                            else -> LiveEvent.Like(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                user = user, avatarUrl = avatar, likeCount = 1,
                            )
                        }
                    }
                    "WebcastRoomUserSeqMessage" -> LiveEvent.ViewerCount(
                        id        = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        count     = data["totalUser"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                    else -> null
                }
                event?.let { scope.launch { _events.emit(it) } }
            }
        }
    }
}
