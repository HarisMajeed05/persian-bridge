package app.persianbridge

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** Gives ComposeView the lifecycle, saved state and view model owners it insists on inside a Service. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    init {
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun start() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

/** Takes every touch on the bubble itself, so I can tell a tap from a drag from a long press. */
private class BubbleRoot(ctx: Context, private val host: Host) : FrameLayout(ctx) {
    interface Host {
        fun begin()
        fun move(dx: Int, dy: Int)
        fun end(dragged: Boolean)
        fun tap()
        fun long()
    }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longFired = false
    private val longPress = Runnable {
        if (!dragging) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            host.long()
        }
    }

    init {
        isClickable = true
        contentDescription = "Persian Bridge. Tap to open."
    }

    override fun onInterceptTouchEvent(e: MotionEvent) = true

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false; longFired = false
                host.begin()
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (dragging) host.move(dx.toInt(), dy.toInt())
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) host.end(true)
                else if (!longFired) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                host.end(dragging)
            }
        }
        return true
    }

    // Going through performClick keeps TalkBack's double tap working too.
    override fun performClick(): Boolean {
        super.performClick()
        host.tap()
        return true
    }
}

/** The panel's root. It exists to catch the Back key, which an overlay never gets otherwise. */
private class PanelRoot(ctx: Context, private val onBack: () -> Unit) : FrameLayout(ctx) {
    init {
        isFocusableInTouchMode = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** The long press menu. A touch anywhere outside it dismisses it. */
private class MenuRoot(ctx: Context, private val onOutside: () -> Unit) : FrameLayout(ctx) {
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return true
        }
        return super.onTouchEvent(event)
    }
}

/**
 * The floating bubble and the panel. I only draw them while Telegram is the app in front, and I
 * remove them the moment it is not.
 */
class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "app.persianbridge.STOP"
        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 17

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BubbleService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BubbleService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = OverlayOwner()
    private lateinit var wm: WindowManager
    private val flashing = mutableStateOf(false)

    private val model by lazy {
        PanelModel(this, scope, onClose = { closePanel() }, onFlash = { flash() }, openApp = { openApp() })
    }

    private var bubble: BubbleRoot? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var menu: View? = null
    private var snap: ValueAnimator? = null
    private var hidden = false
    private var started = false

    // For one drag.
    private var startX = 0
    private var startY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val n = notification()
        // startForegroundService() demands this call within seconds, so it comes before any early exit.
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            stopSelf(); return
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        Bridge.init(this)
        wm = getSystemService(WindowManager::class.java)
        owner.start()
        started = true
        _running.value = true
        scope.launch { ChatReader.telegramFront.collect { onFront(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        if (started) {
            closeMenu(); closePanel(); removeBubble()
            owner.stop()
            Bridge.clearSession()
            Bridge.close()
        }
        _running.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!started) return
        closeMenu(); closePanel()
        place()
    }

    // ---------- Telegram in front or not ----------

    private fun onFront(front: Boolean) {
        if (front) {
            if (!hidden) showBubble()
        } else {
            hidden = false
            closeMenu(); closePanel(); removeBubble()
        }
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun remove(v: View?) {
        if (v == null) return
        try { wm.removeViewImmediate(v) } catch (e: IllegalArgumentException) { /* already gone */ }
    }

    private fun <V : View> V.owned(): V {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        // Ignore touches while another window sits on top of mine, so nothing can trick me into a tap.
        filterTouchesWhenObscured = true
        return this
    }

    private fun compose(content: @Composable () -> Unit) = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            val d by Store.data.collectAsState()
            PbTheme(d.dynamicColor, content)
        }
    }

    private fun params(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    )

    // ---------- the bubble ----------

    private fun screen(): Rect = wm.currentWindowMetrics.bounds

    private fun bars() = wm.currentWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(56)
        val lp = params(
            size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val root = BubbleRoot(this, object : BubbleRoot.Host {
            override fun begin() { snap?.cancel(); closeMenu(); startX = lp.x; startY = lp.y }
            override fun move(dx: Int, dy: Int) = drag(dx, dy)
            override fun end(dragged: Boolean) { if (dragged) settle() }
            override fun tap() = openPanel()
            override fun long() = showMenu()
        }).owned()

        val face = compose { BubbleFace(flashing.value) }
        face.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(face)
        bubbleLp = lp
        bubble = root
        place()
        wm.addView(root, lp)
    }

    private fun removeBubble() {
        snap?.cancel()
        remove(bubble)
        bubble = null
        bubbleLp = null
    }

    /** Puts the bubble where I left it last time. */
    private fun place() {
        val lp = bubbleLp ?: return
        val b = bubble ?: return
        val r = screen()
        val ins = bars()
        val size = lp.width
        val minY = r.top + ins.top
        val maxY = r.bottom - ins.bottom - size
        val d = Store.data.value
        lp.x = if (d.bubbleRight) r.right - size else r.left
        lp.y = (minY + d.bubbleY * (maxY - minY)).toInt()
        if (b.isAttachedToWindow) wm.updateViewLayout(b, lp)
    }

    private fun drag(dx: Int, dy: Int) {
        val lp = bubbleLp ?: return
        val b = bubble ?: return
        val r = screen()
        val ins = bars()
        lp.x = (startX + dx).coerceIn(r.left, r.right - lp.width)
        lp.y = (startY + dy).coerceIn(r.top + ins.top, r.bottom - ins.bottom - lp.height)
        wm.updateViewLayout(b, lp)
    }

    /** After a drag the bubble springs to the nearest edge and I remember the spot. */
    private fun settle() {
        val lp = bubbleLp ?: return
        val b = bubble ?: return
        val r = screen()
        val ins = bars()
        val right = lp.x + lp.width / 2 > (r.left + r.right) / 2
        val target = if (right) r.right - lp.width else r.left
        val minY = r.top + ins.top
        val maxY = r.bottom - ins.bottom - lp.height
        val frac = if (maxY > minY) ((lp.y - minY).toFloat() / (maxY - minY)).coerceIn(0.05f, 0.9f) else 0.42f
        Store.update { it.copy(bubbleRight = right, bubbleY = frac) }
        snap = ValueAnimator.ofInt(lp.x, target).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener {
                if (!b.isAttachedToWindow) return@addUpdateListener
                lp.x = it.animatedValue as Int
                wm.updateViewLayout(b, lp)
            }
            start()
        }
    }

    private fun flash() {
        flashing.value = true
        scope.launch { delay(1400); flashing.value = false }
    }

    // ---------- the long press menu ----------

    private fun showMenu() {
        if (menu != null) return
        val b = bubbleLp ?: return
        val right = Store.data.value.bubbleRight
        val lp = params(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
            x = b.width + dp(8)
            y = b.y
        }
        val root = MenuRoot(this) { closeMenu() }.owned()
        root.addView(compose {
            val c = Pb.c
            Column(
                Modifier.width(250.dp).panel(18.dp, c.surface, c.line).padding(10.dp),
            ) {
                PbButton("Hide until I reopen Telegram", {
                    hidden = true
                    closeMenu(); closePanel(); removeBubble()
                }, Modifier.fillMaxWidth(), BtnKind.Tonal)
                Spacer(Modifier.height(8.dp))
                PbButton("Stop the bubble", { stopSelf() }, Modifier.fillMaxWidth(), BtnKind.Danger)
            }
        })
        menu = root
        wm.addView(root, lp)
        scope.launch { delay(6000); closeMenu() }
    }

    private fun closeMenu() {
        remove(menu)
        menu = null
    }

    // ---------- the panel ----------

    private fun openPanel() {
        if (panel != null) return
        closeMenu()
        model.open()
        val maxH = (screen().height() * 0.72f / resources.displayMetrics.density).dp
        val lp = params(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            // FLAG_SECURE keeps screenshots and screen recordings from capturing the translation.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val root = PanelRoot(this) { closePanel() }.owned()
        root.addView(compose { PanelUi(model, maxH) })
        panel = root
        bubble?.visibility = View.INVISIBLE
        wm.addView(root, lp)
        root.requestFocus()
    }

    private fun closePanel() {
        val p = panel ?: return
        remove(p)
        panel = null
        model.wipe()
        bubble?.visibility = View.VISIBLE
    }

    private fun openApp() {
        closePanel()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- notification ----------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(ch)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_bridge), getString(R.string.notif_stop), stop).build())
            .build()
    }
}

// A tiny helper so the menu buttons fill the menu without importing a second fillMaxWidth.
private fun Modifier.fillMaxWidth(): Modifier = this.then(Modifier.width(230.dp))

@Composable
private fun BubbleFace(flashing: Boolean) {
    val c = Pb.c
    val a by animateFloatAsState(if (flashing) 1f else 0f, label = "flash")
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(c.surface).border(1.5.dp, if (flashing) c.teal else c.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_bridge), null, Modifier.requiredSize(66.dp).alpha(1f - a))
        Box(Modifier.size(56.dp).alpha(a).background(c.teal), contentAlignment = Alignment.Center) {
            Glyph(Ic.Check, c.onTeal, dp = 26.dp)
        }
    }
}
