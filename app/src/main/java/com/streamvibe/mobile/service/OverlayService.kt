package com.streamvibe.mobile.service

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.streamvibe.mobile.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

// ─────────────────────────────────────────────────────────────────────────────
// OverlayService
// Draws floating Compose widgets over ANY app using TYPE_APPLICATION_OVERLAY.
// Each widget is a separate WindowManager view so they can be repositioned.
// ─────────────────────────────────────────────────────────────────────────────
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val overlayViews = mutableMapOf<String, View>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State exposed to overlay composables
    var activeAlert: LiveEvent? = null
        set(value) { field = value; refreshAlertOverlay() }

    var streamSession: StreamSession? = null
        set(value) { field = value; refreshGoalOverlay() }

    var recentMessages: List<LiveEvent.ChatMessage> = emptyList()
        set(value) { field = value; refreshChatOverlay() }

    var widgets: List<OverlayWidget> = emptyList()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder = OverlayBinder()

    inner class OverlayBinder : Binder() {
        fun getService(): OverlayService = this@OverlayService
    }

    // ── Show / Hide individual widget types ──────────────────────────────────

    fun showWidget(widget: OverlayWidget) {
        when (widget.type) {
            WidgetType.ALERT_BANNER   -> addAlertBannerOverlay(widget)
            WidgetType.GOAL_BAR       -> addGoalBarOverlay(widget)
            WidgetType.CHAT_OVERLAY   -> addChatOverlay(widget)
            WidgetType.VIEWER_COUNT   -> addViewerCountOverlay(widget)
            WidgetType.COIN_COUNTER   -> addCoinCounterOverlay(widget)
            WidgetType.RECENT_GIFTERS -> addRecentGiftersOverlay(widget)
        }
    }

    fun hideWidget(widgetId: String) {
        overlayViews[widgetId]?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
            overlayViews.remove(widgetId)
        }
    }

    fun hideAll() {
        overlayViews.keys.toList().forEach { hideWidget(it) }
    }

    // ── Alert Banner ──────────────────────────────────────────────────────────

    private fun addAlertBannerOverlay(widget: OverlayWidget) {
        val view = createComposeView { AlertBannerOverlay(activeAlert, widget) }
        addOverlayView(view, widget, height = WindowManager.LayoutParams.WRAP_CONTENT)
        overlayViews[widget.id] = view
    }

    private fun refreshAlertOverlay() {
        overlayViews.values.firstOrNull()?.let {
            (it as? ComposeView)?.invalidate()
        }
    }

    // ── Goal Bar ──────────────────────────────────────────────────────────────

    private fun addGoalBarOverlay(widget: OverlayWidget) {
        val view = createComposeView { GoalBarOverlay(streamSession, widget) }
        addOverlayView(view, widget, height = 80)
        overlayViews[widget.id] = view
    }

    private fun refreshGoalOverlay() { /* trigger recomposition via state */ }

    // ── Chat Overlay ──────────────────────────────────────────────────────────

    private fun addChatOverlay(widget: OverlayWidget) {
        val view = createComposeView { ChatOverlay(recentMessages, widget) }
        addOverlayView(view, widget, height = 300)
        overlayViews[widget.id] = view
    }

    private fun refreshChatOverlay() { /* trigger recomposition via state */ }

    // ── Viewer Count ──────────────────────────────────────────────────────────

    private fun addViewerCountOverlay(widget: OverlayWidget) {
        val view = createComposeView {
            ViewerCountOverlay(streamSession?.currentViewers ?: 0, widget)
        }
        addOverlayView(view, widget, height = WindowManager.LayoutParams.WRAP_CONTENT)
        overlayViews[widget.id] = view
    }

    // ── Coin Counter ──────────────────────────────────────────────────────────

    private fun addCoinCounterOverlay(widget: OverlayWidget) {
        val view = createComposeView {
            CoinCounterOverlay(streamSession?.totalDiamonds ?: 0, widget)
        }
        addOverlayView(view, widget, height = WindowManager.LayoutParams.WRAP_CONTENT)
        overlayViews[widget.id] = view
    }

    // ── Recent Gifters ────────────────────────────────────────────────────────

    private fun addRecentGiftersOverlay(widget: OverlayWidget) {
        val view = createComposeView { RecentGiftersOverlay(widget) }
        addOverlayView(view, widget, height = WindowManager.LayoutParams.WRAP_CONTENT)
        overlayViews[widget.id] = view
    }

    // ── WindowManager helpers ─────────────────────────────────────────────────

    private fun addOverlayView(view: View, widget: OverlayWidget, height: Int) {
        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (widget.x * screenW).toInt()
            y = (widget.y * screenH).toInt()
        }

        // Touch-to-drag repositioning
        view.setOnTouchListener(object : View.OnTouchListener {
            private var lastX = 0f; private var lastY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        params.x += (event.rawX - lastX).toInt()
                        params.y += (event.rawY - lastY).toInt()
                        lastX = event.rawX; lastY = event.rawY
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                }
                return false
            }
        })

        try { windowManager.addView(view, params) } catch (_: Exception) {}
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView {
        // ComposeView needs a lifecycle owner — we use a simple one
        val lifecycleOwner = ServiceLifecycleOwner()
        lifecycleOwner.start()

        return ComposeView(this).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent { content() }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Stream Overlay", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamVibe Overlay Active")
            .setContentText("Widgets are showing on screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onDestroy() {
        hideAll()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1002
        private const val CHANNEL_ID = "overlay_service"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Overlay Composables
// These render on top of any app — must be lightweight & transparent background
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AlertBannerOverlay(alert: LiveEvent?, widget: OverlayWidget) {
    if (alert == null) return
    val accentColor = parseHexColor(widget.colorAccent)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xDD0D0D1A), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val emoji = when (alert) {
                is LiveEvent.Gift   -> "🎁"
                is LiveEvent.Follow -> "👤"
                is LiveEvent.Share  -> "🔁"
                else -> "💬"
            }
            Text(emoji, fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(alert.user, color = accentColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val desc = when (alert) {
                    is LiveEvent.Gift   -> "sent ${alert.giftName} × ${alert.repeatCount} — ${alert.diamondCount}💎"
                    is LiveEvent.Follow -> "just followed!"
                    is LiveEvent.Share  -> "shared the stream!"
                    is LiveEvent.ChatMessage -> alert.message
                    else -> ""
                }
                Text(desc, color = Color(0xFFAAAAAA), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun GoalBarOverlay(session: StreamSession?, widget: OverlayWidget) {
    val accentColor = parseHexColor(widget.colorAccent)
    val likes = session?.totalLikes ?: 0
    val target = 500 // TODO: configurable
    val pct = (likes.toFloat() / target).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(Color(0xCC0D0D1A), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("❤️ Likes Goal", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("$likes / $target", color = accentColor, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Color(0x33FFFFFF), RoundedCornerShape(3.dp))) {
            Box(modifier = Modifier.fillMaxWidth(pct).fillMaxHeight().background(accentColor, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
fun ChatOverlay(messages: List<LiveEvent.ChatMessage>, widget: OverlayWidget) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(Color(0xBB0A0A0F), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        messages.takeLast(6).forEach { msg ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(msg.user, color = parseHexColor(widget.colorAccent), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(msg.message, color = Color(0xFFDDDDDD), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ViewerCountOverlay(count: Int, widget: OverlayWidget) {
    Row(
        modifier = Modifier
            .background(Color(0xCC0D0D1A), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("👁️", fontSize = 16.sp)
        Text(count.toString(), color = parseHexColor(widget.colorAccent), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CoinCounterOverlay(total: Int, widget: OverlayWidget) {
    Row(
        modifier = Modifier
            .background(Color(0xCC0D0D1A), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("💎", fontSize = 16.sp)
        Text(total.toString(), color = parseHexColor(widget.colorAccent), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RecentGiftersOverlay(widget: OverlayWidget) {
    // TODO: Hook up to recent gift events
    Row(
        modifier = Modifier
            .background(Color(0xCC0D0D1A), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Text("🎁 Top Gifters", color = Color.White, fontSize = 12.sp)
    }
}

fun parseHexColor(hex: String): Color {
    return try { Color(android.graphics.Color.parseColor(hex)) }
    catch (_: Exception) { Color(0xFF00F2EA) }
}

// ─────────────────────────────────────────────────────────────────────────────
// ServiceLifecycleOwner
// Minimal LifecycleOwner for ComposeView inside a Service (no Activity context)
// ─────────────────────────────────────────────────────────────────────────────
class ServiceLifecycleOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
