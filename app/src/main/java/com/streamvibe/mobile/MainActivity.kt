package com.streamvibe.mobile

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.*
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.*
import androidx.compose.ui.text.font.FontWeight
import com.streamvibe.mobile.domain.model.LiveEvent
import androidx.hilt.navigation.compose.hiltViewModel
import com.streamvibe.mobile.ui.MainViewModel
import com.streamvibe.mobile.ui.screens.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            StreamVibeTheme {
                StreamVibeApp()
            }
        }

        // Handle deep link from TikTok OAuth
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "streamvibe" && uri.host == "tiktok") {
                val code = uri.getQueryParameter("code") ?: return
                // Post to ViewModel via a shared flow / broadcast
                // In production, use a shared EventBus or SavedStateHandle
                applicationContext.sendBroadcast(Intent("com.streamvibe.TIKTOK_AUTH_CODE").apply {
                    putExtra("code", code)
                })
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// App Composable
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun StreamVibeApp() {
    val vm: MainViewModel = hiltViewModel()
    val user by vm.user.collectAsState()
    val activeAlert by vm.activeAlert.collectAsState()

    // Overlay permission state
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasOverlayPermission = remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    // TikTok auth code receiver
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                intent.getStringExtra("code")?.let { code ->
                    vm.onTikTokAuthCode(code)
                }
            }
        }
        val filter = IntentFilter("com.streamvibe.TIKTOK_AUTH_CODE")
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    if (user == null) {
        LoginScreen(onTikTokLogin = {
            val tikTokAuthUrl = buildTikTokAuthUrl()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tikTokAuthUrl))
            context.startActivity(intent)
        })
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top header
            StreamVibeHeader(vm)

            // Alert banner (shows on top of all screens when an alert fires)
            AnimatedVisibility(
                visible = activeAlert != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit  = slideOutVertically { -it } + fadeOut(),
            ) {
                activeAlert?.let { AlertBannerCompose(it) }
            }

            // Screen content
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> DashboardScreen(vm)
                    1 -> ChatScreen(vm)
                    2 -> AlertsScreen(vm)
                    3 -> GoalsScreen(vm)
                    4 -> TtsScreen(vm)
                    5 -> OverlayScreen(
                        vm = vm,
                        hasOverlayPermission = hasOverlayPermission.value,
                        onRequestOverlayPermission = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                    )
                    6 -> SettingsScreen(vm, onConnectLive = { roomId -> vm.connectToLive(roomId) })
                }
            }

            // Bottom nav
            StreamVibeBottomNav(selectedTab) { selectedTab = it }
        }
    }
}

// ─── Header ──────────────────────────────────────────────────────────────────
@Composable
fun StreamVibeHeader(vm: MainViewModel) {
    val connection by vm.connectionState.collectAsState()
    val user by vm.user.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0D0D1A))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("◈", fontSize = 22.sp, color = Cyan)
            Column {
                Text("StreamVibe", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 1.sp)
                Text("Alerts & TTS", fontSize = 9.sp, color = Cyan.copy(0.6f), letterSpacing = 2.sp)
            }
        }
        ConnectionBadge(connection)
    }
    HorizontalDivider(color = Color(0xFF1A1A2E))
}

// ─── Alert Banner (in-app) ────────────────────────────────────────────────────
@Composable
fun AlertBannerCompose(alert: LiveEvent) {
    val emoji = when (alert) {
        is LiveEvent.Gift   -> "🎁"
        is LiveEvent.Follow -> "👤"
        is LiveEvent.Share  -> "🔁"
        else -> "💬"
    }
    val accent = when (alert) {
        is LiveEvent.Gift   -> Color(android.graphics.Color.parseColor(alert.giftType.colorHex))
        is LiveEvent.Follow -> Cyan
        else -> Mint
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(10.dp)
            .background(Color(0xEE0D0D1A), RoundedCornerShape(12.dp))
            .border(BorderStroke(1.5.dp, accent.copy(0.5f)), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(emoji, fontSize = 26.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(alert.user, color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            val desc = when (alert) {
                is LiveEvent.Gift   -> "sent ${alert.giftName} ×${alert.repeatCount} — ${alert.diamondCount}💎"
                is LiveEvent.Follow -> "just followed!"
                is LiveEvent.Share  -> "shared your stream!"
                else -> ""
            }
            Text(desc, color = Color(0xFFAAAAAA), fontSize = 12.sp)
        }
    }
}

// ─── Bottom Nav ────────────────────────────────────────────────────────────────
data class NavItem(val label: String, val icon: ImageVector, val badge: Int = 0)

@Composable
fun StreamVibeBottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        NavItem("Dashboard", Icons.Default.Home),
        NavItem("Chat",      Icons.Default.Forum),
        NavItem("Alerts",    Icons.Default.Notifications),
        NavItem("Goals",     Icons.Default.TrackChanges),
        NavItem("TTS",       Icons.Default.RecordVoiceOver),
        NavItem("Overlay",   Icons.Default.Layers),
        NavItem("Settings",  Icons.Default.Settings),
    )
    NavigationBar(containerColor = Color(0xFF0D0D18), tonalElevation = 0.dp) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = {
                    BadgedBox(badge = { if (item.badge > 0) Badge { Text("${item.badge}") } }) {
                        Icon(item.icon, null, modifier = Modifier.size(20.dp))
                    }
                },
                label = { Text(item.label, fontSize = 9.sp, letterSpacing = 0.3.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Cyan,
                    selectedTextColor = Cyan,
                    indicatorColor = Cyan.copy(0.15f),
                    unselectedIconColor = Color(0xFF444466),
                    unselectedTextColor = Color(0xFF444466),
                ),
            )
        }
    }
}

// ─── Theme ────────────────────────────────────────────────────────────────────
@Composable
fun StreamVibeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan,
            background = BgDeep,
            surface = BgCard,
            onPrimary = Color.Black,
            onBackground = Color(0xFFE0E0E8),
            onSurface = Color(0xFFE0E0E8),
        ),
        content = content,
    )
}

// ─── TikTok OAuth URL Builder ─────────────────────────────────────────────────
fun buildTikTokAuthUrl(): String {
    val clientKey = BuildConfig.TIKTOK_CLIENT_KEY
    val redirectUri = Uri.encode(BuildConfig.TIKTOK_REDIRECT_URI)
    // Scopes needed: user.info.basic + live.room.info + live.room.message
    val scopes = Uri.encode("user.info.basic,live.room.info,live.room.message")
    val state = System.currentTimeMillis().toString()
    return "https://www.tiktok.com/v2/auth/authorize/?" +
        "client_key=$clientKey&response_type=code&scope=$scopes" +
        "&redirect_uri=$redirectUri&state=$state"
}


