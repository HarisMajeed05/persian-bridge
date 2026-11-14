package app.persianbridge

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Fill { OK, NEEDS_REPLACE, NO_FIELD, FAILED, NOT_TELEGRAM }

/** What I could read from the open chat. title and directions are best effort. */
class Snap(val title: String?, val msgs: List<ChatMsg>, val hasInput: Boolean)

/**
 * My only window into Telegram. I read the chat on demand when the panel asks, I put text into the
 * message box when asked, and that is all. There is no code path here that clicks, taps or sends.
 */
class ChatReader : AccessibilityService() {

    companion object {
        const val TELEGRAM = "org.telegram.messenger"

        @Volatile
        var instance: ChatReader? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _front = MutableStateFlow(false)
        val telegramFront: StateFlow<Boolean> = _front.asStateFlow()

        /** Reads the system setting so the onboarding screen can show the real state. */
        fun isEnabled(ctx: Context): Boolean {
            val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(ctx, ChatReader::class.java)
            return raw.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override fun onServiceConnected() {
        instance = this
        _connected.value = true
        val pm = getSystemService(PowerManager::class.java)
        scope.launch {
            while (isActive) {
                val front = pm.isInteractive && telegramRoot() != null
                if (_front.value != front) _front.value = front
                // I poll slowly when Telegram is away and a bit faster while it is in front.
                withTimeoutOrNull(if (front) 600L else 1200L) { wake.receive() }
            }
        }
    }

    // Events only arrive from Telegram (see accessibility_config.xml). I never look at their content,
    // I just use them to recheck sooner.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        wake.trySend(Unit)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        _connected.value = false
        _front.value = false
        scope.cancel()
    }

    /**
     * The root of the top most app window, but only if it belongs to Telegram. For any other app I look
     * at the package name and nothing else, then drop it.
     */
    private fun telegramRoot(): AccessibilityNodeInfo? {
        val all = try { windows } catch (e: Exception) { return null }
        val top = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
            ?: return null
        val root = top.root ?: return null
        return if (root.packageName?.toString() == TELEGRAM) root else null
    }

    private class Budget(var left: Int)

    private fun walk(n: AccessibilityNodeInfo, b: Budget, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (b.left-- <= 0 || depth > 32) return
        visit(n)
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, b, depth + 1, visit)
        }
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    private fun cellText(cell: AccessibilityNodeInfo): String {
        val own = label(cell)
        if (own.isNotEmpty()) return own
        val parts = ArrayList<String>()
        walk(cell, Budget(40), 0) { n -> if (n !== cell) label(n).takeIf { it.isNotEmpty() }?.let(parts::add) }
        return parts.joinToString(" ")
    }

    private fun findInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (it.isEditable && it.packageName?.toString() == TELEGRAM) return it
        }
        var found: AccessibilityNodeInfo? = null
        walk(root, Budget(900), 0) { n -> if (found == null && n.isEditable) found = n }
        return found
    }

    /**
     * Reads the newest messages on screen, oldest first. Telegram does not promise a stable tree,
     * so direction and title come from layout guesses and may be wrong. I treat them as hints.
     */
    fun snapshot(max: Int): Snap? {
        val root = telegramRoot() ?: return null
        var list: AccessibilityNodeInfo? = null
        var listArea = 0L
        var input: AccessibilityNodeInfo? = null
        val labeled = ArrayList<Pair<Rect, AccessibilityNodeInfo>>()

        walk(root, Budget(900), 0) { n ->
            if (n.isEditable) {
                if (input == null) input = n
                return@walk
            }
            if (n.isScrollable) {
                val r = Rect().also(n::getBoundsInScreen)
                val area = r.width().toLong() * r.height()
                if (area > listArea) { list = n; listArea = area }
            }
            if (label(n).isNotEmpty() && n.isVisibleToUser) {
                labeled += Rect().also(n::getBoundsInScreen) to n
            }
        }

        val listNode = list
        val listRect = Rect()
        listNode?.getBoundsInScreen(listRect)

        val rows = ArrayList<Triple<Int, String, Boolean?>>()
        if (listNode != null) {
            val margin = (listRect.width() * 0.08f).toInt()
            for (i in 0 until listNode.childCount) {
                val cell = listNode.getChild(i) ?: continue
                if (!cell.isVisibleToUser) continue
                val text = cellText(cell)
                if (text.none { it.isLetter() }) continue
                val r = Rect().also(cell::getBoundsInScreen)
                val gl = r.left - listRect.left
                val gr = listRect.right - r.right
                var mine: Boolean? = when {
                    gl - gr > margin -> true
                    gr - gl > margin -> false
                    else -> null
                }
                if (Bridge.isMine(text)) mine = true
                rows += Triple(r.top, text.take(1200), mine)
            }
        }
        val msgs = rows.sortedBy { it.first }.takeLast(max).map { ChatMsg(it.second, it.third) }

        return Snap(guessTitle(labeled, listRect), msgs, input != null)
    }

    // The chat title is the label above the message list. A TextView with real text wins,
    // otherwise I take the widest description there, since icon buttons are narrow.
    private fun guessTitle(labeled: List<Pair<Rect, AccessibilityNodeInfo>>, listRect: Rect): String? {
        if (listRect.isEmpty) return null
        val above = labeled.filter { (r, n) -> r.bottom <= listRect.top + 8 && !n.isEditable }
        val byText = above.filter { (_, n) ->
            !n.text.isNullOrBlank() && n.className?.toString()?.contains("TextView") == true
        }.maxWithOrNull(compareBy<Pair<Rect, AccessibilityNodeInfo>>({ it.first.height() }, { it.first.width() }))
        if (byText != null) return byText.second.text.toString().trim().take(80)
        val widest = above.maxByOrNull { it.first.width() } ?: return null
        return label(widest.second).substringBefore(',').trim().take(80).ifEmpty { null }
    }

    /** Puts text into Telegram's message box. It never presses Send and never taps anything. */
    fun fill(text: String, replace: Boolean): Fill {
        val root = telegramRoot() ?: return Fill.NOT_TELEGRAM
        val input = findInput(root) ?: return Fill.NO_FIELD
        val existing = if (input.isShowingHintText) "" else input.text?.toString().orEmpty()
        if (existing.isNotBlank() && !replace) return Fill.NEEDS_REPLACE
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) Bridge.rememberSent(text)
        return if (ok) Fill.OK else Fill.FAILED
    }
}
