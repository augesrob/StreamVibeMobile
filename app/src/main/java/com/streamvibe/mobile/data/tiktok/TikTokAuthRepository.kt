package com.streamvibe.mobile.data.tiktok

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.streamvibe.mobile.BuildConfig
import com.streamvibe.mobile.domain.model.TikTokUser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles TikTok OAuth2 PKCE auth — NO SDK, pure HTTP.
 *
 * Flow:
 *   1. buildAuthUrl()              → open in browser via Intent(ACTION_VIEW)
 *   2. Deep link → MainActivity   → handleIntent() → onTikTokAuthCode(code)
 *   3. exchangeCodeForToken(code)  → stores access token
 */
@Singleton
class TikTokAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = OkHttpClient()
    private val json   = Json { ignoreUnknownKeys = true; isLenient = true }

    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "tiktok_auth", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private var pkceVerifier = ""

    // ── PKCE ─────────────────────────────────────────────────────────────────

    private fun generateVerifier(): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun generateChallenge(v: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(v.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    val isAuthenticated: Boolean get() = prefs.getString("access_token", null) != null

    fun getSavedUser(): TikTokUser? {
        val token = prefs.getString("access_token", null) ?: return null
        return TikTokUser(
            openId       = prefs.getString("open_id", "") ?: "",
            displayName  = prefs.getString("display_name", "Streamer") ?: "Streamer",
            avatarUrl    = prefs.getString("avatar_url", "") ?: "",
            accessToken  = token,
            refreshToken = prefs.getString("refresh_token", "") ?: "",
            expiresAt    = prefs.getLong("expires_at", 0L),
        )
    }

    fun buildAuthUrl(): String {
        pkceVerifier = generateVerifier()
        val challenge   = generateChallenge(pkceVerifier)
        val clientKey   = BuildConfig.TIKTOK_CLIENT_KEY
        val redirectUri = Uri.encode(BuildConfig.TIKTOK_REDIRECT_URI)
        val scopes      = Uri.encode("user.info.basic,live.room.info,live.room.message")
        val state       = System.currentTimeMillis().toString()
        return "https://www.tiktok.com/v2/auth/authorize/?" +
               "client_key=$clientKey&response_type=code&scope=$scopes" +
               "&redirect_uri=$redirectUri&state=$state" +
               "&code_challenge=$challenge&code_challenge_method=S256"
    }

    suspend fun exchangeCodeForToken(code: String): Result<TikTokUser> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder()
                    .add("client_key",    BuildConfig.TIKTOK_CLIENT_KEY)
                    .add("client_secret", BuildConfig.TIKTOK_CLIENT_SECRET)
                    .add("code",          code)
                    .add("grant_type",    "authorization_code")
                    .add("redirect_uri",  BuildConfig.TIKTOK_REDIRECT_URI)
                    .add("code_verifier", pkceVerifier)
                    .build()
                val resp = client.newCall(
                    Request.Builder()
                        .url("https://open.tiktokapis.com/v2/oauth/token/")
                        .post(body).build()
                ).execute()
                val data = json.parseToJsonElement(resp.body?.string()
                    ?: throw IOException("Empty response"))
                    .jsonObject["data"]?.jsonObject
                    ?: throw IOException("Bad token response")

                val token  = data["access_token"]!!.jsonPrimitive.content
                val openId = data["open_id"]?.jsonPrimitive?.content ?: ""
                val expiry = data["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L
                val refresh = data["refresh_token"]?.jsonPrimitive?.content ?: ""

                prefs.edit()
                    .putString("access_token",  token)
                    .putString("open_id",        openId)
                    .putString("refresh_token",  refresh)
                    .putLong("expires_at", System.currentTimeMillis() + expiry * 1000)
                    .apply()

                // Fetch user info
                fetchUserInfo(token, openId)
            }
        }

    private suspend fun fetchUserInfo(token: String, openId: String): TikTokUser =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://open.tiktokapis.com/v2/user/info/?fields=display_name,avatar_url")
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                val obj = json.parseToJsonElement(
                    client.newCall(req).execute().body?.string() ?: ""
                ).jsonObject["data"]?.jsonObject?.get("user")?.jsonObject

                val displayName = obj?.get("display_name")?.jsonPrimitive?.content ?: "Streamer"
                val avatarUrl   = obj?.get("avatar_url")?.jsonPrimitive?.content ?: ""

                prefs.edit()
                    .putString("display_name", displayName)
                    .putString("avatar_url",   avatarUrl)
                    .apply()

                TikTokUser(
                    openId       = openId,
                    displayName  = displayName,
                    avatarUrl    = avatarUrl,
                    accessToken  = token,
                    refreshToken = prefs.getString("refresh_token", "") ?: "",
                    expiresAt    = prefs.getLong("expires_at", 0L),
                )
            } catch (_: Exception) {
                TikTokUser(
                    openId = openId, displayName = "Streamer", avatarUrl = "",
                    accessToken = token, refreshToken = "", expiresAt = 0L,
                )
            }
        }

    fun logout() = prefs.edit().clear().apply()
}
