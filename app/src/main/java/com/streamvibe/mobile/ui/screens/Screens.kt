@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.streamvibe.mobile.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.streamvibe.mobile.domain.model.*
import com.streamvibe.mobile.ui.MainViewModel

// ─── Colors ───────────────────────────────────────────────────────────────────
val BgDeep     = Color(0xFF0A0A0F)
val BgCard     = Color(0xFF0D0D1A)
val BgBorder   = Color(0xFF1A1A2E)
val Cyan       = Color(0xFF00F2EA)
val Red        = Color(0xFFFF4D6D)
val Gold       = Color(0xFFFFD700)
val Purple     = Color(0xFFA29BFE)
val Mint       = Color(0xFF55EFC4)
val Orange     = Color(0xFFFF9F43)
val TextPrim   = Color(0xFFE0E0E8)
val TextMuted  = Color(0xFF666688)

// ─────────────────────────────────────────────────────────────────────────────
// LOGIN SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun LoginScreen(onTikTokLogin: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(BgDeep), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            // Logo
            Text("◈", fontSize = 64.sp, color = Cyan,
                modifier = Modifier.graphicsLayer { shadowElevation = 20f })
            Text("StreamVibe Mobile", fontSize = 26.sp, fontWeight = FontWeight.Black,
                color = TextPrim, letterSpacing = 1.sp)
            Text("Alerts & TTS", fontSize = 13.sp, color = Cyan.copy(alpha = 0.6f),
                letterSpacing = 3.sp)

            Spacer(Modifier.height(16.dp))

            Text(
                "Connect your TikTok account to receive live events, alerts, and TTS on stream.",
                fontSize = 14.sp, color = TextMuted, textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onTikTokLogin,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF010101)),
                border = BorderStroke(1.5.dp, Cyan.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text("🎵", fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Text("Connect with TikTok", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrim)
            }

            Text("Uses TikTok Login Kit OAuth 2.0 — no password stored",
                fontSize = 11.sp, color = TextMuted, textAlign = TextAlign.Center)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DashboardScreen(vm: MainViewModel) {
    val session by vm.session.collectAsState()
    val connection by vm.connectionState.collectAsState()
    val goals by vm.goals.collectAsState()
    val alertLog by vm.alertLog.collectAsState()
    val user by vm.user.collectAsState()

    val isConnected = connection is com.streamvibe.mobile.data.tiktok.TikTokLiveRepository.ConnectionState.Connected

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // User Header
        item {
            user?.let { u ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                            .background(Cyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) { Text("🎵", fontSize = 20.sp) }
                    Column {
                        Text(u.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrim)
                        Text("@TikTok", fontSize = 12.sp, color = TextMuted)
                    }
                    Spacer(Modifier.weight(1f))
                    ConnectionBadge(connection)
                }
            }
        }

        // Stats Grid
        if (isConnected && session != null) {
            item {
                SectionTitle("Session Stats")
                val s = session!!
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("👁️", s.currentViewers.toString(), "Viewers", Mint, Modifier.weight(1f))
                    StatCard("❤️", s.totalLikes.toString(), "Likes", Red, Modifier.weight(1f))
                    StatCard("👤", s.totalFollows.toString(), "Follows", Cyan, Modifier.weight(1f))
                    StatCard("💎", s.totalDiamonds.toString(), "Diamonds", Purple, Modifier.weight(1f))
                }
            }

            // Goal Bars
            item {
                SectionTitle("Goals")
                goals.filter { it.enabled }.forEach { goal ->
                    GoalBar(goal)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // Not connected
        if (!isConnected) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("📡", fontSize = 48.sp)
                        Text("Not connected", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                        Text("Enter your Room ID in Settings to connect", fontSize = 13.sp, color = TextMuted.copy(alpha = 0.6f))
                    }
                }
            }
        }

        // Recent alerts preview
        if (alertLog.isNotEmpty()) {
            item { SectionTitle("Recent Alerts") }
            items(alertLog.take(5)) { alert ->
                MiniAlertRow(alert)
            }
        }
    }
}

@Composable
fun ConnectionBadge(state: com.streamvibe.mobile.data.tiktok.TikTokLiveRepository.ConnectionState) {
    val (text, color) = when (state) {
        is com.streamvibe.mobile.data.tiktok.TikTokLiveRepository.ConnectionState.Connected    -> "● LIVE"    to Red
        is com.streamvibe.mobile.data.tiktok.TikTokLiveRepository.ConnectionState.Connecting   -> "○ CONNECTING" to Orange
        is com.streamvibe.mobile.data.tiktok.TikTokLiveRepository.ConnectionState.Error        -> "⚠ ERROR"   to Orange
        is com.streamvibe.mobile.data.tiktok.TikTokLiveRepository.ConnectionState.Disconnected -> "○ OFFLINE" to TextMuted
    }
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 1.sp)
}

@Composable
fun StatCard(icon: String, value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(BgCard, RoundedCornerShape(12.dp))
            .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(icon, fontSize = 18.sp)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Black, color = color)
        Text(label, fontSize = 10.sp, color = TextMuted, letterSpacing = 0.5.sp)
    }
}

@Composable
fun GoalBar(goal: StreamGoal) {
    val pct = (goal.current.toFloat() / goal.target).coerceIn(0f, 1f)
    val color = Color(android.graphics.Color.parseColor(goal.type.colorHex))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${goal.type.icon} ${goal.type.defaultLabel}", fontSize = 13.sp, color = TextPrim)
            Text("${goal.current} / ${goal.target}", fontSize = 13.sp, color = color)
        }
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(BgBorder, RoundedCornerShape(3.dp))) {
            Box(modifier = Modifier.fillMaxWidth(pct).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
fun MiniAlertRow(event: LiveEvent) {
    val (emoji, desc, color) = when (event) {
        is LiveEvent.Gift   -> Triple("🎁", "${event.giftName} ×${event.repeatCount} — ${event.diamondCount}💎", Purple)
        is LiveEvent.Follow -> Triple("👤", "New follower", Cyan)
        is LiveEvent.Share  -> Triple("🔁", "Shared the stream", Mint)
        else -> Triple("💬", "", TextMuted)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 18.sp)
        Text(event.user, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrim, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(desc, fontSize = 12.sp, color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CHAT SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ChatScreen(vm: MainViewModel) {
    val messages by vm.chatMessages.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val userColors = remember { mutableMapOf<String, Color>() }
    val palette = listOf(Cyan, Red, Gold, Purple, Mint, Orange)

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp, 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("Live Chat")
            Text("${messages.size} messages", fontSize = 12.sp, color = TextMuted)
        }
        HorizontalDivider(color = BgBorder)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            items(messages) { msg ->
                val color = userColors.getOrPut(msg.user) { palette[msg.user.hashCode().and(0x7FFFFFFF) % palette.size] }
                Row(modifier = Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(msg.user, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.widthIn(max = 120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(msg.message, color = TextPrim.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ALERTS SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AlertsScreen(vm: MainViewModel) {
    val alerts by vm.alertLog.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp, 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Alert Log")
            Text("${alerts.size} events", fontSize = 12.sp, color = TextMuted)
        }
        HorizontalDivider(color = BgBorder)
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(alerts) { alert ->
                AlertLogCard(alert)
            }
            if (alerts.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp), Alignment.Center) {
                        Text("No alerts yet — start a live stream", color = TextMuted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun AlertLogCard(event: LiveEvent) {
    val (emoji, desc, accentColor) = when (event) {
        is LiveEvent.Gift   -> Triple("🎁", "Sent ${event.giftName} ×${event.repeatCount} — ${event.diamondCount} diamonds", Color(android.graphics.Color.parseColor(event.giftType.colorHex)))
        is LiveEvent.Follow -> Triple("👤", "New follower!", Cyan)
        is LiveEvent.Share  -> Triple("🔁", "Shared the stream!", Mint)
        else -> Triple("💬", "", TextMuted)
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(BgCard, RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, BgBorder), RoundedCornerShape(10.dp))
            .drawBehind { drawRect(accentColor, size = androidx.compose.ui.geometry.Size(3f, size.height)) }
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(emoji, fontSize = 22.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(event.user, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrim)
            Text(desc, fontSize = 12.sp, color = TextMuted)
        }
        if (event is LiveEvent.Gift) {
            Box(
                modifier = Modifier.background(accentColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .border(1.dp, accentColor.copy(0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("${event.diamondCount}💎", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accentColor)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GOALS SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun GoalsScreen(vm: MainViewModel) {
    val goals by vm.goals.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("Stream Goals") }
        items(goals) { goal ->
            GoalCard(goal, onUpdate = { target, enabled ->
                vm.updateGoal(goal.id, target = target, enabled = enabled)
            })
        }
        item {
            OutlinedButton(
                onClick = { vm.resetGoals() },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, BgBorder),
            ) { Text("Reset All Progress", color = TextMuted) }
        }
    }
}

@Composable
fun GoalCard(goal: StreamGoal, onUpdate: (Int?, Boolean?) -> Unit) {
    val color = Color(android.graphics.Color.parseColor(goal.type.colorHex))
    val pct = (goal.current.toFloat() / goal.target).coerceIn(0f, 1f)
    var targetText by remember(goal.target) { mutableStateOf(goal.target.toString()) }

    Column(
        modifier = Modifier.fillMaxWidth().background(BgCard, RoundedCornerShape(12.dp))
            .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(goal.type.icon, fontSize = 20.sp)
            Text(goal.type.defaultLabel, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrim, modifier = Modifier.weight(1f))
            Switch(
                checked = goal.enabled,
                onCheckedChange = { onUpdate(null, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = color, checkedTrackColor = color.copy(0.4f)),
            )
        }
        if (goal.enabled) {
            Box(modifier = Modifier.fillMaxWidth().height(8.dp).background(BgBorder, RoundedCornerShape(4.dp))) {
                Box(modifier = Modifier.fillMaxWidth(pct).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(color.copy(0.7f), color)), RoundedCornerShape(4.dp)))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("${goal.current} / ${goal.target}", fontSize = 12.sp, color = color)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Target:", fontSize = 12.sp, color = TextMuted)
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = {
                            targetText = it
                            it.toIntOrNull()?.let { v -> if (v > 0) onUpdate(v, null) }
                        },
                        modifier = Modifier.width(80.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Cyan, textAlign = TextAlign.End),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder,
                        ),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TTS SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TtsScreen(vm: MainViewModel) {
    val settings by vm.ttsSettings.collectAsState()
    val queue by vm.ttsQueue.collectAsState()
    val isSpeaking by vm.ttsSpeaking.collectAsState()
    val currentItem by vm.ttsCurrentItem.collectAsState()
    val elevenVoices by vm.elevenLabsVoices.collectAsState()
    var newBlockWord by remember { mutableStateOf("") }
    var newUserVoiceUser by remember { mutableStateOf("") }
    var newUserVoiceId by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Queue status
        item {
            SectionTitle("TTS Queue")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f).background(BgCard, RoundedCornerShape(10.dp))
                    .border(1.dp, if (isSpeaking) Cyan.copy(0.5f) else BgBorder, RoundedCornerShape(10.dp))
                    .padding(12.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (isSpeaking) "🔊" else "🔇", fontSize = 24.sp)
                        Text(if (isSpeaking) "Speaking" else "Idle", fontSize = 12.sp, color = if (isSpeaking) Cyan else TextMuted)
                    }
                }
                Box(modifier = Modifier.weight(1f).background(BgCard, RoundedCornerShape(10.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(10.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${queue.size}", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Gold)
                        Text("In queue", fontSize = 12.sp, color = TextMuted)
                    }
                }
            }
            if (currentItem != null) {
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().background(BgCard, RoundedCornerShape(10.dp))
                    .border(1.dp, Cyan.copy(0.3f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("Now: ${currentItem!!.sanitizedText ?: currentItem!!.text}", fontSize = 13.sp, color = Cyan, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.skipTts() }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, BgBorder)) {
                    Text("⏭ Skip", color = TextMuted)
                }
                OutlinedButton(onClick = { vm.clearTts() }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, BgBorder)) {
                    Text("🗑 Clear", color = TextMuted)
                }
                OutlinedButton(onClick = { vm.pauseTts() }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, BgBorder)) {
                    Text("⏸ Pause", color = TextMuted)
                }
            }
        }

        // Enable toggle
        item {
            SettingsCard {
                SettingRow("Enable TTS", settings.enabled) { vm.updateTtsSettings(settings.copy(enabled = it)) }
            }
        }

        // Voice provider
        item {
            SectionTitle("Voice")
            SettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Provider", fontSize = 13.sp, color = TextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VoiceProvider.values().forEach { provider ->
                            FilterChip(
                                selected = settings.voiceProvider == provider,
                                onClick  = { vm.updateTtsSettings(settings.copy(voiceProvider = provider)) },
                                label    = { Text(provider.name.replace("_", " "), fontSize = 12.sp) },
                                colors   = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(0.2f), selectedLabelColor = Cyan),
                            )
                        }
                    }

                    if (settings.voiceProvider == VoiceProvider.ELEVENLABS && elevenVoices.isNotEmpty()) {
                        Text("ElevenLabs Voice", fontSize = 13.sp, color = TextMuted)
                        var expanded by remember { mutableStateOf(false) }
                        val selectedVoice = elevenVoices.find { it.voiceId == settings.elevenLabsVoiceId }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(
                                value = selectedVoice?.name ?: "Select voice",
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder, focusedTextColor = Cyan),
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                elevenVoices.forEach { voice ->
                                    DropdownMenuItem(
                                        text = { Text(voice.name) },
                                        onClick = {
                                            vm.updateTtsSettings(settings.copy(elevenLabsVoiceId = voice.voiceId))
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    SliderRow("Speed", settings.rate, 0.5f, 2f, 0.1f, "×") { vm.updateTtsSettings(settings.copy(rate = it)) }
                    SliderRow("Pitch", settings.pitch, 0.5f, 2f, 0.1f, "×") { vm.updateTtsSettings(settings.copy(pitch = it)) }
                }
            }
        }

        // What to read
        item {
            SectionTitle("Read Events")
            SettingsCard {
                SettingRow("Gifts", settings.readGifts)     { vm.updateTtsSettings(settings.copy(readGifts = it)) }
                SettingRow("Follows", settings.readFollows) { vm.updateTtsSettings(settings.copy(readFollows = it)) }
                SettingRow("Chat", settings.readChat)       { vm.updateTtsSettings(settings.copy(readChat = it)) }
                SettingRow("Shares", settings.readShares)   { vm.updateTtsSettings(settings.copy(readShares = it)) }
                if (settings.readGifts) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Min diamonds for TTS", fontSize = 14.sp, color = TextPrim)
                        OutlinedTextField(
                            value = settings.minDiamondsForTTS.toString(),
                            onValueChange = { it.toIntOrNull()?.let { v -> vm.updateTtsSettings(settings.copy(minDiamondsForTTS = v)) } },
                            modifier = Modifier.width(80.dp), singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Cyan, textAlign = TextAlign.End),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder),
                        )
                    }
                }
            }
        }

        // Claude filter
        item {
            SectionTitle("Filter Pipeline")
            SettingsCard {
                SettingRow("Claude AI Sanitizer", settings.useClaudeFilter) { vm.updateTtsSettings(settings.copy(useClaudeFilter = it)) }
                if (settings.useClaudeFilter) {
                    Text("Removes spam, normalizes emojis, skips gibberish before speaking", fontSize = 12.sp, color = TextMuted, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Max queue size", fontSize = 14.sp, color = TextPrim)
                    OutlinedTextField(
                        value = settings.maxQueueSize.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> vm.updateTtsSettings(settings.copy(maxQueueSize = v)) } },
                        modifier = Modifier.width(70.dp), singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Cyan, textAlign = TextAlign.End),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder),
                    )
                }
            }
        }

        // Blocklist
        item {
            SectionTitle("Word Blocklist")
            SettingsCard {
                settings.blocklist.forEach { word ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(word, fontSize = 14.sp, color = Red)
                        IconButton(onClick = { vm.removeBlocklistWord(word) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newBlockWord,
                        onValueChange = { newBlockWord = it },
                        placeholder = { Text("Add word to block...", fontSize = 13.sp, color = TextMuted) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim),
                    )
                    IconButton(onClick = {
                        if (newBlockWord.isNotBlank()) { vm.addBlocklistWord(newBlockWord.trim()); newBlockWord = "" }
                    }) { Icon(Icons.Default.Add, null, tint = Cyan) }
                }
            }
        }

        // Per-user voices
        item {
            SectionTitle("Per-User Voices")
            SettingsCard {
                Text("Assign a specific ElevenLabs voice to a username", fontSize = 12.sp, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                settings.userVoiceMap.forEach { (user, voiceId) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(user, fontSize = 13.sp, color = Cyan, modifier = Modifier.weight(1f))
                        Text(voiceId.take(20), fontSize = 11.sp, color = TextMuted, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.removeUserVoice(user) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                OutlinedTextField(value = newUserVoiceUser, onValueChange = { newUserVoiceUser = it },
                    placeholder = { Text("Username", fontSize = 12.sp, color = TextMuted) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim))
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(value = newUserVoiceId, onValueChange = { newUserVoiceId = it },
                    placeholder = { Text("ElevenLabs Voice ID", fontSize = 12.sp, color = TextMuted) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim))
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    if (newUserVoiceUser.isNotBlank() && newUserVoiceId.isNotBlank()) {
                        vm.setUserVoice(newUserVoiceUser.trim(), newUserVoiceId.trim())
                        newUserVoiceUser = ""; newUserVoiceId = ""
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(0.2f))) {
                    Text("Assign Voice", color = Cyan)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// OVERLAY SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun OverlayScreen(vm: MainViewModel, onRequestOverlayPermission: () -> Unit, hasOverlayPermission: Boolean) {
    val widgets by vm.widgets.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Overlay Widgets")
            if (!hasOverlayPermission) {
                Box(
                    modifier = Modifier.fillMaxWidth().background(Orange.copy(0.15f), RoundedCornerShape(10.dp))
                        .border(1.dp, Orange.copy(0.4f), RoundedCornerShape(10.dp)).padding(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⚠️ Overlay Permission Required", fontWeight = FontWeight.Bold, color = Orange)
                        Text("To show widgets over TikTok or games, grant the 'Display over other apps' permission.", fontSize = 13.sp, color = TextMuted, lineHeight = 18.sp)
                        Button(onClick = onRequestOverlayPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = Orange.copy(0.3f))) {
                            Text("Grant Permission", color = Orange)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        items(widgets) { widget ->
            WidgetCard(widget, onToggle = { vm.updateWidget(widget.id, enabled = it) },
                onColorChange = { vm.updateWidget(widget.id, colorAccent = it) })
        }

        item {
            Box(modifier = Modifier.fillMaxWidth().background(BgCard, RoundedCornerShape(10.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(10.dp)).padding(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("💡 How overlays work", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrim)
                    Text("• Floating Window: Widgets appear over TikTok app", fontSize = 13.sp, color = TextMuted)
                    Text("• Camera Mode: Widgets drawn over your camera preview in-app", fontSize = 13.sp, color = TextMuted)
                    Text("• Game Stream: Screen capture composites widgets into the stream", fontSize = 13.sp, color = TextMuted)
                    Text("• Drag widgets on screen to reposition them", fontSize = 13.sp, color = TextMuted)
                }
            }
        }
    }
}

@Composable
fun WidgetCard(widget: OverlayWidget, onToggle: (Boolean) -> Unit, onColorChange: (String) -> Unit) {
    val accentColor = try { Color(android.graphics.Color.parseColor(widget.colorAccent)) } catch (_: Exception) { Cyan }
    val icon = when (widget.type) {
        WidgetType.ALERT_BANNER   -> "🔔"
        WidgetType.GOAL_BAR       -> "🎯"
        WidgetType.CHAT_OVERLAY   -> "💬"
        WidgetType.VIEWER_COUNT   -> "👁️"
        WidgetType.COIN_COUNTER   -> "💎"
        WidgetType.RECENT_GIFTERS -> "🎁"
    }
    val label = widget.type.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }

    Row(
        modifier = Modifier.fillMaxWidth().background(BgCard, RoundedCornerShape(12.dp))
            .border(1.dp, if (widget.enabled) accentColor.copy(0.4f) else BgBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(icon, fontSize = 22.sp)
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrim, modifier = Modifier.weight(1f))
        Switch(
            checked = widget.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = accentColor, checkedTrackColor = accentColor.copy(0.4f)),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SETTINGS SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsScreen(vm: MainViewModel, onConnectLive: (String) -> Unit) {
    val user by vm.user.collectAsState()
    var roomId by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle("Account")
            SettingsCard {
                user?.let { u ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Cyan.copy(0.2f)), Alignment.Center) { Text("🎵") }
                        Column(Modifier.weight(1f)) {
                            Text(u.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrim)
                            Text("Connected via TikTok Login Kit", fontSize = 11.sp, color = TextMuted)
                        }
                        TextButton(onClick = { vm.logout() }) { Text("Logout", color = Red, fontSize = 13.sp) }
                    }
                }
            }
        }

        item {
            SectionTitle("Live Connection")
            SettingsCard {
                Text("Enter your TikTok Live Room ID to receive events", fontSize = 13.sp, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = roomId,
                    onValueChange = { roomId = it },
                    label = { Text("Room ID") },
                    placeholder = { Text("e.g. 7234567890123456789") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, focusedLabelColor = Cyan),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { if (roomId.isNotBlank()) onConnectLive(roomId.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(0.2f)),
                ) { Text("Connect to Live Room", color = Cyan) }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { vm.disconnectFromLive() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect", color = Red.copy(0.8f), fontSize = 13.sp)
                }
            }
        }

        item {
            SectionTitle("TikTok Developer Credentials")
            SettingsCard {
                Text("Enter your TikTok Developer App credentials. Get them from developers.tiktok.com", fontSize = 12.sp, color = TextMuted, lineHeight = 16.sp)
                Spacer(Modifier.height(8.dp))
                var clientKey by remember { mutableStateOf(vm.authRepo.getClientKey()) }
                var clientSecret by remember { mutableStateOf(vm.authRepo.getClientSecret()) }
                var saved by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = clientKey,
                    onValueChange = { clientKey = it; saved = false },
                    label = { Text("Client Key") },
                    placeholder = { Text("e.g. aw1234abc56789...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, focusedLabelColor = Cyan),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it; saved = false },
                    label = { Text("Client Secret") },
                    placeholder = { Text("e.g. abc123...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, unfocusedBorderColor = BgBorder, focusedTextColor = TextPrim, unfocusedTextColor = TextPrim, focusedLabelColor = Cyan),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.authRepo.saveClientCredentials(clientKey, clientSecret)
                        saved = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (saved) Mint.copy(0.2f) else Cyan.copy(0.2f)),
                ) { Text(if (saved) "✓ Saved" else "Save Credentials", color = if (saved) Mint else Cyan) }
            }
        }

        item {
            SectionTitle("About")
            SettingsCard {
                SettingInfoRow("App", "StreamVibe Mobile — Alerts & TTS")
                SettingInfoRow("Version", "1.0.0")
                SettingInfoRow("Project", "drvn-empire / StreamVibe")
                SettingInfoRow("Auth", "OAuth 2.0 PKCE (no SDK)")
                SettingInfoRow("TTS Voices", "Android + ElevenLabs")
                SettingInfoRow("Filter", "Claude AI Pipeline")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared Components
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
        color = Cyan.copy(0.6f), letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(BgCard, RoundedCornerShape(12.dp))
            .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
fun SettingRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = TextPrim)
        Switch(checked = checked, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Cyan, checkedTrackColor = Cyan.copy(0.4f)))
    }
}

@Composable
fun SettingInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = TextMuted)
        Text(value, fontSize = 13.sp, color = TextPrim.copy(0.8f))
    }
}

@Composable
fun SliderRow(label: String, value: Float, min: Float, max: Float, step: Float, unit: String, onChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = TextPrim)
            Text("${"%.1f".format(value)}$unit", fontSize = 13.sp, color = Cyan, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max, steps = ((max - min) / step).toInt() - 1,
            colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan))
    }
}
