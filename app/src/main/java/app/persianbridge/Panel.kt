package app.persianbridge

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { READ, WRITE }
enum class ReadState { IDLE, READING, OK, FAILED }

sealed interface Run {
    data object Idle : Run
    data object Working : Run
    /** What would leave the phone, waiting for my say so. msgs is null for an outgoing reply. */
    class Preview(val plan: SmartPlan, val msgs: List<ChatMsg>?) : Run
    class Err(val message: String, val retry: Boolean, val needsModels: Boolean, val needsKey: Boolean) : Run
}

/**
 * All the panel's state and actions in one place. Everything here lives in memory only and is
 * wiped when the panel closes, so no chat text outlives the moment I needed it.
 */
class PanelModel(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit,
    private val onFlash: () -> Unit,
    val openApp: () -> Unit,
) {
    var tab by mutableStateOf(Tab.READ)
    var title by mutableStateOf<String?>(null)
    var settingsOpen by mutableStateOf(false)
    var approval by mutableStateOf(false)

    var readState by mutableStateOf(ReadState.IDLE)
    var msgs by mutableStateOf<List<ChatMsg>>(emptyList())
    var count by mutableIntStateOf(3)
    var pasteOpen by mutableStateOf(false)
    var pasted by mutableStateOf("")
    var rows by mutableStateOf<List<InRow>>(emptyList())
    var inRun by mutableStateOf<Run>(Run.Idle)
    var inNote by mutableStateOf<String?>(null)

    var draft by mutableStateOf("")
    var out by mutableStateOf<OutResult?>(null)
    var outRun by mutableStateOf<Run>(Run.Idle)
    var outNote by mutableStateOf<String?>(null)
    var fillNote by mutableStateOf<Pair<NoticeKind, String>?>(null)
    var confirmReplace by mutableStateOf(false)

    private var inJob: Job? = null
    private var outJob: Job? = null

    /** Without a readable chat title I can't tell chats apart, so I never allow Smart there. */
    fun modeOf(d: AppData): Mode = if (title == null) Mode.PRIVATE else d.modeFor(title)

    fun open() {
        wipe()
        scope.launch { Bridge.refreshModels() }
        scope.launch { readChat() }
    }

    fun wipe() {
        inJob?.cancel(); outJob?.cancel()
        tab = Tab.READ; title = null; settingsOpen = false; approval = false
        readState = ReadState.IDLE; msgs = emptyList(); pasteOpen = false; pasted = ""
        rows = emptyList(); inRun = Run.Idle; inNote = null
        draft = ""; out = null; outRun = Run.Idle; outNote = null; fillNote = null; confirmReplace = false
    }

    fun close() = onClose()

    // ---------- reading ----------

    fun reread() { scope.launch { readChat() } }

    private suspend fun readChat() {
        readState = ReadState.READING
        val reader = ChatReader.instance
        val snap = if (reader == null) null else withContext(Dispatchers.Default) {
            runCatching { reader.snapshot(12) }.getOrNull()
        }
        if (snap == null) {
            title = null; msgs = emptyList(); readState = ReadState.FAILED
            return
        }
        title = snap.title
        msgs = snap.msgs
        readState = if (snap.msgs.isEmpty()) ReadState.FAILED else ReadState.OK
    }

    // ---------- errors ----------

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
        try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

    private fun toRun(e: Throwable?): Run.Err {
        if (e !is TxError) {
            return Run.Err("Something went wrong on my side. Try again, or use Private mode.", true, false, false)
        }
        val key = e is TxError.NoKey || e is TxError.Auth
        return Run.Err(e.userMessage, retry = !key && e !is TxError.ModelMissing, needsModels = e is TxError.ModelMissing, needsKey = key)
    }

    fun downloadModels() {
        scope.launch {
            Bridge.downloadTranslation()
            if (Bridge.models.value == ModelState.READY) {
                if (inRun is Run.Err) inRun = Run.Idle
                if (outRun is Run.Err) outRun = Run.Idle
            }
        }
    }

    // ---------- incoming ----------

    fun translateIn(forcePrivate: Boolean = false) {
        val picked = if (readState != ReadState.OK || pasteOpen) {
            pasted.trim().takeIf { it.isNotEmpty() }?.let { listOf(ChatMsg(it.take(1500), false)) }.orEmpty()
        } else {
            msgs.filter { it.fromMe != true }.takeLast(count)
        }
        if (picked.isEmpty()) {
            inRun = Run.Err("There is nothing to translate yet. Open a chat with a new message, or paste one.", false, false, false)
            return
        }
        inJob?.cancel()
        inJob = scope.launch {
            rows = emptyList(); inNote = null
            val d = Store.data.value
            if (forcePrivate || modeOf(d) == Mode.PRIVATE) { privateIn(picked); return@launch }
            inRun = Run.Working
            val chat = d.chatFor(title)
            val plan = attempt { withContext(Dispatchers.Default) { Bridge.planIn(picked, chat, title) } }
                .getOrElse { inRun = toRun(it); return@launch }
            if (plan.analysis.flagged && !chat.trusted) { inRun = Run.Preview(plan, picked); return@launch }
            smartIn(plan, picked)
        }
    }

    private suspend fun smartIn(plan: SmartPlan, picked: List<ChatMsg>) {
        inRun = Run.Working
        val r = attempt { Bridge.runIn(plan, picked) }
        r.getOrNull()?.let { rows = it; inRun = Run.Idle; return }
        val e = r.exceptionOrNull()
        if (e is TxError.Placeholders || e is TxError.Blocked) {
            inNote = if (e is TxError.Blocked) {
                "Gemini refused this text, so I translated it on your phone and added it to your sensitive list."
            } else {
                "A hidden value did not come back intact, so I translated on your phone instead."
            }
            privateIn(picked)
        } else {
            inRun = toRun(e)
        }
    }

    private suspend fun privateIn(picked: List<ChatMsg>) {
        inRun = Run.Working
        val r = attempt { Bridge.privateIn(picked) }
        val ok = r.getOrNull()
        if (ok != null) { rows = ok; inRun = Run.Idle } else inRun = toRun(r.exceptionOrNull())
    }

    // ---------- outgoing ----------

    fun translateOut(forcePrivate: Boolean = false) {
        val text = draft.trim()
        if (text.isEmpty()) return
        outJob?.cancel()
        outJob = scope.launch {
            out = null; outNote = null; fillNote = null; confirmReplace = false
            val d = Store.data.value
            if (forcePrivate || modeOf(d) == Mode.PRIVATE) { privateOut(text); return@launch }
            outRun = Run.Working
            readChat()
            val chat = d.chatFor(title)
            val plan = attempt { withContext(Dispatchers.Default) { Bridge.planOut(text, msgs, chat, title) } }
                .getOrElse { outRun = toRun(it); return@launch }
            if (plan.analysis.flagged && !chat.trusted) { outRun = Run.Preview(plan, null); return@launch }
            smartOut(plan, text)
        }
    }

    private suspend fun smartOut(plan: SmartPlan, text: String) {
        outRun = Run.Working
        val r = attempt { Bridge.runOut(plan) }
        r.getOrNull()?.let { out = it; outRun = Run.Idle; return }
        val e = r.exceptionOrNull()
        if (e is TxError.Placeholders || e is TxError.Blocked) {
            outNote = if (e is TxError.Blocked) {
                "Gemini refused this text, so I translated it on your phone and added it to your sensitive list."
            } else {
                "A hidden value did not come back intact, so I translated on your phone instead."
            }
            privateOut(text)
        } else {
            outRun = toRun(e)
        }
    }

    private suspend fun privateOut(text: String) {
        outRun = Run.Working
        val r = attempt { Bridge.privateOut(text) }
        val ok = r.getOrNull()
        if (ok != null) { out = ok; outRun = Run.Idle } else outRun = toRun(r.exceptionOrNull())
    }

    // ---------- preview card ----------

    fun confirmPreview(incoming: Boolean) {
        if (incoming) {
            val p = inRun as? Run.Preview ?: return
            inJob = scope.launch { smartIn(p.plan, p.msgs.orEmpty()) }
        } else {
            val p = outRun as? Run.Preview ?: return
            outJob = scope.launch { smartOut(p.plan, p.plan.rawInputs.first()) }
        }
    }

    fun privateInstead(incoming: Boolean) {
        if (incoming) translateIn(true) else translateOut(true)
    }

    fun cancelPreview(incoming: Boolean) {
        if (incoming) inRun = Run.Idle else outRun = Run.Idle
    }

    // ---------- using the result ----------

    fun copy() {
        val r = out ?: return
        Clip.copy(ctx, r.persian)
        Bridge.rememberSent(r.persian)
        fillNote = NoticeKind.Ok to "Copied. I clear it from the clipboard after a minute."
        onFlash()
    }

    /** Types into Telegram's message box. I never press Send. */
    fun fill(replace: Boolean = false) {
        val r = out ?: return
        val reader = ChatReader.instance
        if (reader == null) {
            fillNote = NoticeKind.Warn to "Accessibility is off, so I can't type into Telegram. Use Copy and paste it yourself."
            return
        }
        scope.launch {
            confirmReplace = false
            when (withContext(Dispatchers.Default) { reader.fill(r.persian, replace) }) {
                Fill.OK -> { onFlash(); onClose() }
                Fill.NEEDS_REPLACE -> confirmReplace = true
                Fill.NO_FIELD -> fillNote = NoticeKind.Warn to "I could not find Telegram's message box. Open a chat first, or use Copy."
                Fill.FAILED -> fillNote = NoticeKind.Warn to "Telegram would not take the text. Use Copy and paste it yourself."
                Fill.NOT_TELEGRAM -> fillNote = NoticeKind.Warn to "Telegram is not in front right now."
            }
        }
    }

    fun pasteFromClipboard() {
        Clip.read(ctx)?.let { pasted = it.take(1500) }
    }

    // ---------- chat settings ----------

    fun setTone(t: Tone) = Store.updateChat(title) { it.copy(tone = t) }
    fun setNotes(s: String) = Store.updateChat(title) { it.copy(notes = s.take(500)) }
    fun setTrusted(b: Boolean) = Store.updateChat(title) { it.copy(trusted = b) }

    fun smartToggle(on: Boolean) {
        if (!on) { Store.updateChat(title) { it.copy(mode = Mode.PRIVATE) }; approval = false; return }
        val d = Store.data.value
        if (!d.chatFor(title).smartApproved || !d.warningAccepted) approval = true
        else Store.updateChat(title) { it.copy(mode = Mode.SMART) }
    }

    fun allowSmart() {
        Store.update { it.copy(warningAccepted = true) }
        Store.updateChat(title) { it.copy(smartApproved = true, mode = Mode.SMART) }
        approval = false
    }
}

// ============================ UI ============================

@Composable
fun PanelUi(m: PanelModel, maxHeight: Dp) {
    val c = Pb.c
    val d by Store.data.collectAsState()
    val mode = m.modeOf(d)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    AnimatedVisibility(
        visible = shown,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 3 } + fadeIn(tween(160)),
    ) {
        Column(
            Modifier.padding(8.dp).fillMaxWidth().heightIn(max = maxHeight).panel(24.dp, c.surface, c.line)
        ) {
            Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(m.title ?: "Unknown chat", style = T.head, maxLines = 1)
                    Spacer(Modifier.size(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeBadge(mode)
                        if (m.title == null) {
                            Spacer(Modifier.width(8.dp))
                            Txt("Can't tell which chat this is", style = T.small, color = c.muted, maxLines = 1)
                        }
                    }
                }
                IconBtn(Ic.Gear, "Chat settings", m.settingsOpen) { m.settingsOpen = !m.settingsOpen }
                IconBtn(Ic.Close, "Close", false) { m.close() }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                ChoiceRow(listOf(Tab.READ to "Read their message", Tab.WRITE to "Write a reply"), m.tab, { m.tab = it })
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (m.settingsOpen) ChatSettingsBlock(m, d)
                if (mode == Mode.PRIVATE) ModelsBlock(m)
                when (m.tab) {
                    Tab.READ -> ReadTab(m)
                    Tab.WRITE -> WriteTab(m, mode)
                }
            }
        }
    }
}

@Composable
private fun IconBtn(ic: Ic, label: String, active: Boolean, onClick: () -> Unit) {
    val c = Pb.c
    Box(
        Modifier.size(48.dp).semantics { contentDescription = label }.pressable(true, onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(ic, if (active) c.teal else c.muted, dp = 22.dp) }
}

@Composable
private fun ChatSettingsBlock(m: PanelModel, d: AppData) {
    val c = Pb.c
    val chat = d.chatFor(m.title)
    Column(Modifier.fillMaxWidth().panel(18.dp, c.raised, c.line).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt("Tone for this chat", style = T.small, color = c.muted)
        ChoiceRow(Tone.entries.map { it to it.label }, chat.tone, { m.setTone(it) })
        LabeledField(
            label = "Notes about this person", value = chat.notes, onChange = { m.setNotes(it) },
            hint = "Gender, age, how you two talk", singleLine = false, minLines = 2,
        )
        val canSmart = d.apiKey.isNotBlank() && m.title != null && !d.alwaysPrivate
        SwitchRow(
            title = "Use Smart for this chat",
            desc = when {
                d.alwaysPrivate -> "Always Private is on in the app settings."
                m.title == null -> "I need to read the chat name first."
                d.apiKey.isBlank() -> "Add your Gemini key in the app first."
                else -> "Sends hidden text to Google for better Persian."
            },
            checked = chat.mode == Mode.SMART && chat.smartApproved && canSmart,
            onChange = { m.smartToggle(it) },
            accent = c.amber, enabled = canSmart,
        )
        if (m.approval) {
            Notice(
                NoticeKind.Warn,
                title = "Allow Smart here?",
                text = "Smart sends this chat's text to Google's free Gemini service. On the free tier Google may use it to improve its products and people may review it. Names, numbers and other private values are hidden first, but not everything can be caught.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PbButton("Allow Smart", { m.allowSmart() }, Modifier.weight(1f), BtnKind.Tonal)
                PbButton("Keep Private", { m.approval = false }, Modifier.weight(1f), BtnKind.Primary)
            }
        }
        SwitchRow(
            title = "Skip the preview in this chat",
            desc = "Hiding still happens. I just stop asking first.",
            checked = chat.trusted, onChange = { m.setTrusted(it) },
        )
        Txt("Smart requests today: ${Store.usageToday(d)}", style = T.small, color = c.muted)
    }
}

@Composable
private fun ModelsBlock(m: PanelModel) {
    val st by Bridge.models.collectAsState()
    when (st) {
        ModelState.MISSING, ModelState.FAILED -> Notice(
            if (st == ModelState.FAILED) NoticeKind.Error else NoticeKind.Info,
            title = "Offline Persian models needed",
            text = if (st == ModelState.FAILED) "The download failed. Check your connection and try again." else "Private mode translates on your phone. Download the models once, ideally on Wi-Fi.",
            actionLabel = if (st == ModelState.FAILED) "Try again" else "Download",
            onAction = { m.downloadModels() },
        )
        ModelState.DOWNLOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(Pb.c.teal, 18.dp)
            Spacer(Modifier.width(10.dp))
            Txt("Downloading the offline models", style = T.small, color = Pb.c.muted)
        }
        else -> Unit
    }
}

@Composable
private fun ReadTab(m: PanelModel) {
    val c = Pb.c
    val focus = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    when (m.readState) {
        ReadState.READING, ReadState.IDLE -> Skeleton(3)
        ReadState.FAILED -> {
            EmptyState(
                "Can't read this chat",
                "Telegram did not share its messages with me. Paste one below and I will translate it.",
                actionLabel = "Try again", onAction = { m.reread() },
            )
        }
        ReadState.OK -> {
            Txt("Translate the latest messages from them", style = T.small, color = c.muted)
            ChoiceRow(listOf(1 to "1 message", 3 to "3 messages", 6 to "6 messages"), m.count, { m.count = it })
        }
    }
    if (m.readState == ReadState.OK && !m.pasteOpen) {
        PbButton("Translate", { m.translateIn() }, Modifier.fillMaxWidth(), busy = m.inRun is Run.Working)
        PbButton("Paste a message instead", { m.pasteOpen = true }, Modifier.fillMaxWidth(), BtnKind.Ghost)
    }
    if (m.readState == ReadState.FAILED || m.pasteOpen) {
        LabeledField(
            label = "Their message", value = m.pasted, onChange = { m.pasted = it },
            hint = "Paste Persian or Finglish", singleLine = false, minLines = 3, rtl = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PbButton("Paste", { m.pasteFromClipboard() }, Modifier.weight(1f), BtnKind.Tonal, Ic.Copy)
            PbButton(
                "Translate", { focus.clearFocus(); kb?.hide(); m.translateIn() }, Modifier.weight(1f),
                enabled = m.pasted.isNotBlank(), busy = m.inRun is Run.Working,
            )
        }
        if (m.readState == ReadState.OK) PbButton("Back to the chat", { m.pasteOpen = false }, Modifier.fillMaxWidth(), BtnKind.Ghost)
    }

    m.inNote?.let { Notice(NoticeKind.Info, it) }
    RunBlock(m, incoming = true, run = m.inRun)

    m.rows.forEach { r ->
        Column(Modifier.fillMaxWidth().panel(18.dp, c.raised, c.line).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(r.english, style = T.bodyMed.copy(fontSize = 16.sp))
            Fa(r.original, small = true, color = c.muted)
            if (r.flag.isNotBlank()) Txt(r.flag, style = T.small, color = c.amber)
        }
    }
}

@Composable
private fun WriteTab(m: PanelModel, mode: Mode) {
    val c = Pb.c
    val focus = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    LabeledField(
        label = "Your reply in English", value = m.draft, onChange = { m.draft = it },
        hint = "Say it the way you would say it", singleLine = false, minLines = 3,
    )
    val busy = m.outRun is Run.Working
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PbButton(
            "Translate", { focus.clearFocus(); kb?.hide(); m.translateOut() }, Modifier.weight(1f),
            enabled = m.draft.isNotBlank(), busy = busy,
        )
        if (mode == Mode.SMART) {
            PbButton(
                "On my phone", { focus.clearFocus(); kb?.hide(); m.translateOut(true) }, Modifier.weight(1f),
                BtnKind.Tonal, Ic.Lock, enabled = m.draft.isNotBlank() && !busy,
            )
        }
    }
    m.outNote?.let { Notice(NoticeKind.Info, it) }
    RunBlock(m, incoming = false, run = m.outRun)

    m.out?.let { r ->
        Column(Modifier.fillMaxWidth().panel(18.dp, c.raised, c.line).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fa(r.persian)
            HLine()
            Txt("Back translation", style = T.micro, color = c.muted)
            Txt(r.back, style = T.body)
            if (r.note.isNotBlank()) Notice(NoticeKind.Info, r.note)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PbButton("Copy", { m.copy() }, Modifier.weight(1f), BtnKind.Tonal, Ic.Copy)
            PbButton("Fill chat", { m.fill(false) }, Modifier.weight(1f), BtnKind.Primary, Ic.Pen)
        }
        Txt("Fill chat only types into the message box. You press Send yourself.", style = T.micro, color = c.muted)
        if (m.confirmReplace) {
            Notice(NoticeKind.Warn, "The message box already has text. Replace it with this reply?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PbButton("Keep mine", { m.confirmReplace = false }, Modifier.weight(1f), BtnKind.Tonal)
                PbButton("Replace", { m.fill(true) }, Modifier.weight(1f), BtnKind.Danger)
            }
        }
        m.fillNote?.let { (k, t) -> Notice(k, t) }
    }
}

/** Working, preview and error states, shared by both tabs. */
@Composable
private fun RunBlock(m: PanelModel, incoming: Boolean, run: Run) {
    when (run) {
        Run.Idle -> Unit
        Run.Working -> Skeleton(3)
        is Run.Preview -> PreviewCard(m, incoming, run)
        is Run.Err -> {
            Notice(NoticeKind.Error, run.message)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (run.needsModels) PbButton("Download models", { m.downloadModels() }, Modifier.weight(1f), BtnKind.Primary)
                if (run.needsKey) PbButton("Open the app", { m.openApp() }, Modifier.weight(1f), BtnKind.Tonal)
                if (run.retry) {
                    PbButton("Try again", { if (incoming) m.translateIn() else m.translateOut() }, Modifier.weight(1f), BtnKind.Tonal)
                }
                if (!run.needsModels) {
                    PbButton("Use Private", { m.privateInstead(incoming) }, Modifier.weight(1f), BtnKind.Primary, Ic.Lock)
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(m: PanelModel, incoming: Boolean, run: Run.Preview) {
    val c = Pb.c
    val a = run.plan.analysis
    // Slot 0 is the notes (incoming) or the typed text (outgoing). The rest are chat lines.
    val sent: List<String> = if (incoming) a.redacted.drop(1) else listOf(a.redacted[0])
    val contextLines = if (incoming) 0 else (a.redacted.size - 2).coerceAtLeast(0)
    Column(
        Modifier.fillMaxWidth().panel(18.dp, c.amber.copy(alpha = 0.08f), c.amber.copy(alpha = 0.45f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Ic.Shield, c.amber, dp = 20.dp)
            Spacer(Modifier.width(8.dp))
            Txt("Check what leaves your phone", style = T.head)
        }
        a.reasons.forEach { Txt(it, style = T.small, color = c.amber) }
        if (a.map.isNotEmpty()) {
            Txt("${a.map.size} private value(s) are replaced with placeholders and put back afterwards.", style = T.small, color = c.muted)
        }
        Column(Modifier.fillMaxWidth().panel(12.dp, c.raised, c.line).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sent.forEach { Txt(it, style = T.small, maxLines = 6) }
        }
        if (contextLines > 0) {
            Txt("Plus $contextLines recent chat line(s) as context, hidden the same way.", style = T.small, color = c.muted)
        }
        PbButton("Translate on my phone", { m.privateInstead(incoming) }, Modifier.fillMaxWidth(), BtnKind.Primary, Ic.Lock)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PbButton("Send hidden version", { m.confirmPreview(incoming) }, Modifier.weight(1f), BtnKind.Tonal, Ic.Out)
            PbButton("Cancel", { m.cancelPreview(incoming) }, Modifier.weight(1f), BtnKind.Ghost)
        }
    }
}
