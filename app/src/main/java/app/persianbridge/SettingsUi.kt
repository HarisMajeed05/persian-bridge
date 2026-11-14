package app.persianbridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Opens a system screen or a web page. If the phone has nothing to handle it I just do nothing. */
fun Context.open(intent: Intent) {
    try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: ActivityNotFoundException) { /* nothing to open it with */ }
}

fun Context.openUrl(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

@Composable
fun ScreenBase(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Pb.c.bg).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
        content = content,
    )
}

@Composable
fun TranslationRow() {
    val st by Bridge.models.collectAsState()
    val scope = rememberCoroutineScope()
    StatusRow(
        "Offline Persian translation",
        "Private mode translates on your phone with these models. One download, then it works without internet.",
        state = when (st) { ModelState.READY -> true; ModelState.DOWNLOADING -> null; else -> false },
        actionLabel = if (st == ModelState.FAILED) "Try again" else "Download",
        onAction = { scope.launch { Bridge.downloadTranslation() } },
    )
}

@Composable
fun EntityRow() {
    val st by Bridge.entities.collectAsState()
    val scope = rememberCoroutineScope()
    StatusRow(
        "Offline value finder",
        "Spots addresses, phone numbers and cards in English and Arabic text. My own rules cover Persian.",
        state = when (st) { ModelState.READY -> true; ModelState.DOWNLOADING -> null; else -> false },
        actionLabel = if (st == ModelState.FAILED) "Try again" else "Download",
        onAction = { scope.launch { Bridge.downloadEntities() } },
    )
}

/** The name Google gave the model for my key, or the raw id if I have no list yet. */
private fun modelLabel(d: AppData, id: String) = d.availableModels.firstOrNull { it.id == id }?.label ?: id

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = Pb.c
    val d by Store.data.collectAsState()
    ScreenBase {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).semantics { contentDescription = "Back" }.pressable(true, onBack),
                contentAlignment = Alignment.Center,
            ) { Glyph(Ic.Chev, c.text, Modifier.graphicsLayer { rotationZ = 180f }, 22.dp) }
            Txt("Settings", style = T.title)
        }

        SectionLabel("Smart mode with Gemini")
        KeyBlock(d)

        SectionLabel("Privacy")
        Column(Modifier.fillMaxWidth().panel(18.dp, c.surface, c.line).padding(horizontal = 16.dp, vertical = 8.dp)) {
            SwitchRow(
                "Always Private", "Never use Smart anywhere. Everything is translated on this phone.",
                d.alwaysPrivate, { v -> Store.update { it.copy(alwaysPrivate = v) } },
            )
        }
        Txt(
            "New chats always start in Private. I only use Smart in a chat after you allow it there.",
            Modifier.padding(top = 10.dp), style = T.small, color = c.muted,
        )

        SectionLabel("Words I should treat as private")
        WordEditor(
            label = "Add a word or phrase", hint = "A name, a place, a project",
            items = d.wordlist,
            onAdd = { w -> Store.update { s -> if (w in s.wordlist) s else s.copy(wordlist = (s.wordlist + w).takeLast(200)) } },
            onRemove = { w -> Store.update { s -> s.copy(wordlist = s.wordlist - w) } },
        )
        Txt(
            "A message with one of these gets flagged, so I check with you before anything is sent.",
            Modifier.padding(top = 8.dp), style = T.small, color = c.muted,
        )
        LearnedBlock(d)

        SectionLabel("Glossary")
        GlossaryBlock(d)

        SectionLabel("Chats I know")
        ChatsBlock(d)

        SectionLabel("Replies")
        Column(Modifier.fillMaxWidth().panel(18.dp, c.surface, c.line).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt("Default tone for new chats", style = T.small, color = c.muted)
            ChoiceRow(Tone.entries.map { it to it.label }, d.defaultTone, { t -> Store.update { it.copy(defaultTone = t) } })
            Txt("Recent chat lines used as context", style = T.small, color = c.muted)
            ChoiceRow(listOf(5 to "5 lines", 8 to "8 lines", 10 to "10 lines"), d.contextSize, { n -> Store.update { it.copy(contextSize = n) } })
        }

        SectionLabel("Offline models")
        Column(Modifier.fillMaxWidth().panel(18.dp, c.surface, c.line).padding(horizontal = 16.dp, vertical = 6.dp)) {
            TranslationRow()
            EntityRow()
        }

        SectionLabel("Look")
        Column(Modifier.fillMaxWidth().panel(18.dp, c.surface, c.line).padding(horizontal = 16.dp, vertical = 8.dp)) {
            SwitchRow(
                "Use my wallpaper colors", "Swaps my teal for your system accent. The amber warning color stays.",
                d.dynamicColor, { v -> Store.update { it.copy(dynamicColor = v) } },
            )
        }

        SectionLabel("Erase")
        EraseBlock()
    }
}

// ---------- Gemini key ----------

private sealed interface KeyTest {
    data object Idle : KeyTest
    data object Testing : KeyTest
    data object Ok : KeyTest
    class Err(val text: String) : KeyTest
}

private sealed interface Listing {
    data object Idle : Listing
    data object Loading : Listing
    class Done(val count: Int) : Listing
    class Err(val text: String) : Listing
}

@Composable
private fun KeyBlock(d: AppData) {
    val c = Pb.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hap = rememberHaptic()
    // I never put the saved key back into the field, so it can't be read off the screen.
    var key by remember { mutableStateOf("") }
    var test by remember { mutableStateOf<KeyTest>(KeyTest.Idle) }
    var listing by remember { mutableStateOf<Listing>(Listing.Idle) }

    // Asks Google what this key can use, then keeps my pick if it is still there, else moves to the best match.
    fun load(k: String) {
        scope.launch {
            listing = Listing.Loading
            listing = try {
                val found = Bridge.listModels(k)
                // If I saved a different key while this was running, the answer belongs to the old one.
                if (Store.data.value.apiKey.trim() == k.trim()) {
                    Store.update { s ->
                        val keep = found.any { it.id == s.model }
                        s.copy(availableModels = found, model = if (keep) s.model else Models.pick(found.map { it.id }) ?: s.model)
                    }
                }
                Listing.Done(found.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TxError) {
                Listing.Err(e.userMessage)
            } catch (e: Exception) {
                Listing.Err("I could not reach Google.")
            }
        }
    }

    // A key saved earlier but no list yet (first run after an update): fetch it once when this screen opens.
    LaunchedEffect(Unit) { if (d.apiKey.isNotBlank() && d.availableModels.isEmpty()) load(d.apiKey) }

    Column(Modifier.fillMaxWidth().panel(18.dp, c.surface, c.line).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledField(
            label = "Gemini API key", value = key, onChange = { key = it; test = KeyTest.Idle },
            hint = if (d.apiKey.isBlank()) "Paste your key" else "A key is saved. Paste a new one to replace it.",
            secret = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PbButton(
                "Save key", {
                    val k = key.trim()
                    Store.update { it.copy(apiKey = k, availableModels = emptyList()) }
                    key = ""
                    hap.confirm()
                    load(k)
                }, Modifier.weight(1f), BtnKind.Primary, enabled = key.isNotBlank(),
            )
            PbButton(
                "Test", {
                    scope.launch {
                        test = KeyTest.Testing
                        test = try {
                            Bridge.testKey(key.ifBlank { d.apiKey }, d.model)
                            KeyTest.Ok
                        } catch (e: TxError) {
                            KeyTest.Err(e.userMessage)
                        } catch (e: Exception) {
                            KeyTest.Err("I could not reach Google.")
                        }
                    }
                }, Modifier.weight(1f), BtnKind.Tonal,
                enabled = (key.isNotBlank() || d.apiKey.isNotBlank()) && test != KeyTest.Testing,
                busy = test == KeyTest.Testing,
            )
        }
        when (val t = test) {
            KeyTest.Ok -> Notice(NoticeKind.Ok, "The key works with ${modelLabel(d, d.model)}.")
            is KeyTest.Err -> Notice(NoticeKind.Error, t.text)
            else -> Unit
        }
        PbButton("Get a free key", { ctx.openUrl("https://aistudio.google.com/apikey") }, kind = BtnKind.Ghost)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Models this key can use", Modifier.weight(1f), style = T.small, color = c.muted)
            PbButton(
                "Refresh", { load(d.apiKey) }, kind = BtnKind.Ghost,
                enabled = d.apiKey.isNotBlank() && listing != Listing.Loading, busy = listing == Listing.Loading,
            )
        }
        when (val l = listing) {
            is Listing.Err -> Notice(NoticeKind.Error, l.text)
            is Listing.Done -> Txt("${l.count} models found. Pick one.", style = T.small, color = c.muted)
            else -> Unit
        }
        if (d.availableModels.isEmpty()) {
            Txt(
                if (d.apiKey.isBlank()) "Save your key and I will list every model it can use."
                else "Selected: ${d.model}. Tap Refresh to load the list.",
                style = T.small, color = c.muted,
            )
        } else {
            ModelList(d.availableModels, d.model) { id -> Store.update { it.copy(model = id) } }
        }
        SwitchRow(
            "Double check", "Asks Gemini to review its own Persian. Better wording, but it costs a second request.",
            d.doubleCheck, { v -> Store.update { it.copy(doubleCheck = v) } },
        )
        Txt("Smart requests today: ${Store.usageToday(d)}", style = T.bodyMed)
        Txt("Google sets the free limits per model and changes them. Your real limits are in AI Studio.", style = T.small, color = c.muted)
    }
    Spacer(Modifier.size(10.dp))
    Notice(
        NoticeKind.Warn,
        title = "About the free tier",
        text = "Text sent to Gemini on the free tier can be used by Google to improve its products, and people may read it. I hide names, numbers and similar values first, but nothing catches everything. Use a key from a separate Google account, and keep sensitive chats on Private.",
    )
}

@Composable
private fun ModelList(models: List<ModelInfo>, selected: String, onPick: (String) -> Unit) {
    val c = Pb.c
    Column(
        Modifier.fillMaxWidth().heightIn(max = 288.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        models.forEach { m ->
            val on = m.id == selected
            val tint = if (on) c.teal else c.line
            Row(
                Modifier.fillMaxWidth().semantics { this.selected = on }
                    .pressable(true) { onPick(m.id) }
                    .panel(14.dp, if (on) c.teal.copy(alpha = 0.10f) else c.raised, tint)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt(m.label, style = T.bodyMed, maxLines = 1)
                    if (m.label != m.id) Txt(m.id, style = T.small, color = c.muted, maxLines = 1)
                }
                if (on) Glyph(Ic.Check, c.teal, dp = 20.dp)
            }
        }
    }
}

// ---------- lists ----------

@Composable
private fun ItemRow(text: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = Pb.c
    Row(modifier.fillMaxWidth().panel(14.dp, c.raised, c.line).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Modifier.weight(1f).padding(vertical = 10.dp), style = T.body, maxLines = 2)
        Box(
            Modifier.size(48.dp).semantics { contentDescription = "Remove $text" }.pressable(true, onRemove),
            contentAlignment = Alignment.Center,
        ) { Glyph(Ic.Trash, c.muted, dp = 20.dp) }
    }
}

@Composable
private fun WordEditor(
    label: String, hint: String, items: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            LabeledField(label, text, { text = it }, Modifier.weight(1f), hint = hint)
            PbButton("Add", { onAdd(text.trim().take(80)); text = "" }, enabled = text.isNotBlank())
        }
        items.forEach { w -> ItemRow(w, { onRemove(w) }) }
    }
}

@Composable
private fun LearnedBlock(d: AppData) {
    val c = Pb.c
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Learned from refusals", style = T.bodyMed)
        Txt(
            if (d.learned.isEmpty()) "Empty. When Gemini refuses a text, I add it here and keep it on the phone from then on."
            else "${d.learned.size} phrase(s). Similar text is flagged and kept on the phone.",
            style = T.small, color = c.muted,
        )
        if (d.learned.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PbButton(if (open) "Hide list" else "Review list", { open = !open }, Modifier.weight(1f), BtnKind.Tonal)
                PbButton("Clear all", { Store.update { it.copy(learned = emptyList()) } }, Modifier.weight(1f), BtnKind.Danger)
            }
            if (open) d.learned.forEach { p -> ItemRow(p, { Store.update { s -> s.copy(learned = s.learned - p) } }) }
        }
    }
}

@Composable
private fun GlossaryBlock(d: AppData) {
    val c = Pb.c
    var term by remember { mutableStateOf("") }
    var fa by remember { mutableStateOf("") }
    var en by remember { mutableStateOf("") }
    var isName by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().panel(18.dp, c.surface, c.line).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt("Words and names with a fixed spelling. Names never leave the phone: I swap them for a placeholder and put your spelling back.", style = T.small, color = c.muted)
        LabeledField("As it appears in chats", term, { term = it }, hint = "Sara")
        LabeledField("In Persian", fa, { fa = it }, hint = "سارا", rtl = true)
        LabeledField("In English", en, { en = it }, hint = "Sara")
        SwitchRow("This is a name", "Hidden from Google and restored afterwards.", isName, { isName = it })
        PbButton(
            "Add to glossary", {
                val e = GlossaryEntry(term.trim().take(60), fa.trim().take(60), en.trim().take(60), isName)
                Store.update { s -> s.copy(glossary = (s.glossary + e).takeLast(200)) }
                term = ""; fa = ""; en = ""
            }, Modifier.fillMaxWidth(), BtnKind.Primary,
            enabled = term.isNotBlank() && (fa.isNotBlank() || en.isNotBlank()),
        )
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        d.glossary.forEach { g ->
            val line = buildString {
                append(g.term)
                if (g.persian.isNotBlank()) append("  ->  ").append(g.persian)
                if (g.english.isNotBlank()) append("  /  ").append(g.english)
                if (g.isName) append("  (name)")
            }
            ItemRow(line, { Store.update { s -> s.copy(glossary = s.glossary - g) } })
        }
    }
}

@Composable
private fun ChatsBlock(d: AppData) {
    val c = Pb.c
    if (d.chats.isEmpty()) {
        EmptyState("No chats yet", "A chat shows up here after you open the panel in it and change its tone, notes or mode.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        d.chats.entries.sortedBy { it.value.title.lowercase() }.forEach { (key, chat) ->
            Row(
                Modifier.fillMaxWidth().panel(14.dp, c.surface, c.line).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Txt(chat.title, style = T.bodyMed, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeBadge(chat.effectiveMode)
                        Spacer(Modifier.width(8.dp))
                        Txt(chat.tone.label, style = T.small, color = c.muted)
                    }
                }
                Box(
                    Modifier.size(48.dp).semantics { contentDescription = "Forget ${chat.title}" }
                        .pressable(true) { Store.update { s -> s.copy(chats = s.chats - key) } },
                    contentAlignment = Alignment.Center,
                ) { Glyph(Ic.Trash, c.muted, dp = 20.dp) }
            }
        }
    }
}

@Composable
private fun EraseBlock() {
    val c = Pb.c
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Removes your key, glossary, word lists, chat settings and everything else I keep. It cannot be undone.", style = T.small, color = c.muted)
        if (!confirm) {
            PbButton("Erase everything", { confirm = true }, Modifier.fillMaxWidth(), BtnKind.Danger, Ic.Trash)
        } else {
            Notice(NoticeKind.Warn, "This deletes all my data on this phone. Erase it now?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PbButton("Keep my data", { confirm = false }, Modifier.weight(1f), BtnKind.Tonal)
                PbButton("Erase", {
                    Store.update { AppData() }
                    Bridge.clearSession()
                    confirm = false
                }, Modifier.weight(1f), BtnKind.Danger)
            }
        }
    }
}
