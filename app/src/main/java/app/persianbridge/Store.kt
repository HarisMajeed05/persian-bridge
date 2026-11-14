package app.persianbridge

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** I keep every model decision in this one object so a rename is a one line change. */
object Models {
    const val DEFAULT = "gemini-3.8-flash"
    val choices = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

    // Google says Gemini 3.6 Flash and newer ignore temperature and later ones may reject it.
    // So I only send a low temperature to the older Flash-Lite models that still honor it.
    val acceptsTemperature = setOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

    // The model id goes straight into a URL path, so I only let plain ids through.
    private val safeId = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    fun isSafe(id: String) = safeId.matches(id)

    // Models that answer generateContent but can't return my JSON text, so I never offer them.
    private val notForText = Regex("image|tts|audio|live|embed|robotics|computer-use|imagen|veo|aqa")
    fun isTextModel(id: String) = id.startsWith("gemini-") && !notForText.containsMatchIn(id)

    /** The model to fall back to when my pick is not in the list Google sent. */
    fun pick(ids: List<String>): String? = choices.firstOrNull { it in ids } ?: ids.firstOrNull()
}

/** One entry from the list Google returns for my key. */
data class ModelInfo(val id: String, val label: String)

enum class Mode { SMART, PRIVATE }

enum class Tone(val key: String, val label: String, val prompt: String) {
    FRIEND(
        "friend", "Friend",
        "Close friend. Use informal tu forms (تو), fully spoken Persian, light everyday slang is fine."
    ),
    FAMILY(
        "family", "Family",
        "Family member. Warm and informal. Use tu with peers and younger people, but shoma (شما) with elders if the notes say so."
    ),
    PARTNER(
        "partner", "Partner",
        "Romantic partner. Warm, affectionate, informal tu forms. Natural endearments in moderation, nothing cringey."
    ),
    COLLEAGUE(
        "colleague", "Colleague",
        "Colleague or acquaintance. Polite spoken Persian with shoma (شما) forms and normal contractions, no slang."
    ),
    FORMAL(
        "formal", "Formal",
        "Formal. Use shoma (شما) and a respectful register, but still natural, never stiff or bookish for no reason, no slang."
    );

    companion object {
        fun from(key: String?): Tone = entries.firstOrNull { it.key == key } ?: FRIEND
    }
}

/** A glossary line. Names are redacted before anything leaves the phone and restored afterwards. */
data class GlossaryEntry(val term: String, val persian: String, val english: String, val isName: Boolean)

data class ChatSettings(
    val title: String,
    val tone: Tone,
    val mode: Mode,
    val notes: String,
    val smartApproved: Boolean,
    val trusted: Boolean,
) {
    /** A chat only gets Smart after I approved it, no matter what the default mode says. */
    val effectiveMode: Mode get() = if (smartApproved) mode else Mode.PRIVATE
}

data class AppData(
    val apiKey: String = "",
    val model: String = Models.DEFAULT,
    val defaultMode: Mode = Mode.PRIVATE,
    val defaultTone: Tone = Tone.FRIEND,
    val doubleCheck: Boolean = false,
    val dynamicColor: Boolean = false,
    val contextSize: Int = 8,
    val warningAccepted: Boolean = false,
    val glossary: List<GlossaryEntry> = emptyList(),
    val wordlist: List<String> = emptyList(),
    val learned: List<String> = emptyList(),
    val chats: Map<String, ChatSettings> = emptyMap(),
    val usageDay: String = "",
    val usageCount: Int = 0,
    val bubbleRight: Boolean = true,
    val bubbleY: Float = 0.42f,
    val alwaysPrivate: Boolean = false,
    val availableModels: List<ModelInfo> = emptyList(),
) {
    /** The mode a chat really runs in. My global Always Private switch beats everything else. */
    fun modeFor(title: String?): Mode = if (alwaysPrivate) Mode.PRIVATE else chatFor(title).effectiveMode

    fun chatFor(title: String?): ChatSettings =
        chats[Store.chatKey(title)] ?: ChatSettings(
            title = title?.trim().orEmpty().ifBlank { "Unknown chat" },
            tone = defaultTone,
            mode = defaultMode,
            notes = "",
            smartApproved = false,
            trusted = false,
        )

    fun toJson(): JSONObject = JSONObject().apply {
        put("apiKey", apiKey)
        put("model", model)
        put("defaultMode", defaultMode.name)
        put("defaultTone", defaultTone.key)
        put("doubleCheck", doubleCheck)
        put("dynamicColor", dynamicColor)
        put("contextSize", contextSize)
        put("warningAccepted", warningAccepted)
        put("usageDay", usageDay)
        put("usageCount", usageCount)
        put("bubbleRight", bubbleRight)
        put("bubbleY", bubbleY.toDouble())
        put("alwaysPrivate", alwaysPrivate)
        put("availableModels", JSONArray().also { arr ->
            availableModels.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label)) }
        })
        put("wordlist", JSONArray(wordlist))
        put("learned", JSONArray(learned))
        put("glossary", JSONArray().also { arr ->
            glossary.forEach {
                arr.put(
                    JSONObject().put("term", it.term).put("fa", it.persian)
                        .put("en", it.english).put("name", it.isName)
                )
            }
        })
        put("chats", JSONArray().also { arr ->
            chats.forEach { (key, c) ->
                arr.put(
                    JSONObject().put("key", key).put("title", c.title).put("tone", c.tone.key)
                        .put("mode", c.mode.name).put("notes", c.notes)
                        .put("approved", c.smartApproved).put("trusted", c.trusted)
                )
            }
        })
    }

    companion object {
        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }

        fun fromJson(o: JSONObject): AppData {
            val model = o.optString("model", Models.DEFAULT).takeIf { Models.isSafe(it) } ?: Models.DEFAULT
            val available = o.optJSONArray("availableModels")?.let { arr ->
                List(arr.length()) { i ->
                    val m = arr.optJSONObject(i)
                    ModelInfo(m?.optString("id").orEmpty(), m?.optString("label").orEmpty())
                }
            }.orEmpty().filter { Models.isSafe(it.id) }.distinctBy { it.id }
            val glossary = o.optJSONArray("glossary")?.let { arr ->
                List(arr.length()) { i ->
                    val g = arr.getJSONObject(i)
                    GlossaryEntry(g.optString("term"), g.optString("fa"), g.optString("en"), g.optBoolean("name"))
                }
            }.orEmpty().filter { it.term.isNotBlank() }
            val chats = o.optJSONArray("chats")?.let { arr ->
                List(arr.length()) { i ->
                    val c = arr.getJSONObject(i)
                    c.optString("key") to ChatSettings(
                        title = c.optString("title"),
                        tone = Tone.from(c.optString("tone")),
                        mode = runCatching { Mode.valueOf(c.optString("mode")) }.getOrDefault(Mode.PRIVATE),
                        notes = c.optString("notes"),
                        smartApproved = c.optBoolean("approved"),
                        trusted = c.optBoolean("trusted"),
                    )
                }.toMap()
            }.orEmpty()
            return AppData(
                apiKey = o.optString("apiKey"),
                model = model,
                defaultMode = runCatching { Mode.valueOf(o.optString("defaultMode")) }.getOrDefault(Mode.PRIVATE),
                defaultTone = Tone.from(o.optString("defaultTone")),
                doubleCheck = o.optBoolean("doubleCheck"),
                dynamicColor = o.optBoolean("dynamicColor"),
                contextSize = o.optInt("contextSize", 8).coerceIn(5, 10),
                warningAccepted = o.optBoolean("warningAccepted"),
                glossary = glossary,
                wordlist = strings(o.optJSONArray("wordlist")),
                learned = strings(o.optJSONArray("learned")),
                chats = chats,
                usageDay = o.optString("usageDay"),
                usageCount = o.optInt("usageCount"),
                bubbleRight = o.optBoolean("bubbleRight", true),
                bubbleY = o.optDouble("bubbleY", 0.42).toFloat().coerceIn(0.05f, 0.9f),
                alwaysPrivate = o.optBoolean("alwaysPrivate"),
                availableModels = available,
            )
        }
    }
}

/**
 * I don't trust plain prefs for anything personal, and Jetpack security-crypto is deprecated.
 * So the whole data file is sealed with an AES-256-GCM key that lives in the Android Keystore
 * and never leaves it.
 */
private object Vault {
    private const val ALIAS = "pb_data_key_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(spec(true)); gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            gen.init(spec(false)); gen.generateKey()
        }
    }

    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    fun open(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }
}

/** One encrypted file, one in-memory state. Nothing here is ever logged. */
object Store {
    private var file: File? = null
    private val state = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = state.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    fun chatKey(title: String?): String =
        title?.trim()?.lowercase().orEmpty().ifBlank { "unknown" }.take(80)

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.noBackupFilesDir, "pb.dat")
        file = f
        if (f.exists()) {
            // If the key is gone or the file is damaged I just start clean instead of crashing.
            runCatching {
                state.value = AppData.fromJson(JSONObject(String(Vault.open(f.readBytes()), Charsets.UTF_8)))
            }
        }
    }

    fun update(block: (AppData) -> AppData) {
        state.update(block)
        saveJob?.cancel()
        saveJob = io.launch {
            delay(300)
            withContext(NonCancellable) { write(state.value) }
        }
    }

    fun updateChat(title: String?, block: (ChatSettings) -> ChatSettings) = update { d ->
        val key = chatKey(title)
        d.copy(chats = d.chats + (key to block(d.chatFor(title))))
    }

    private fun write(d: AppData) {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, "pb.tmp")
            tmp.writeBytes(Vault.seal(d.toJson().toString().toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(f)) {
                f.delete(); tmp.renameTo(f)
            }
        }
    }

    // Google resets the daily quota at midnight Pacific, so I count days the same way.
    private fun today(): String = LocalDate.now(ZoneId.of("America/Los_Angeles")).toString()

    fun usageToday(d: AppData = state.value): Int = if (d.usageDay == today()) d.usageCount else 0

    fun countRequest() = update { d ->
        val t = today()
        if (d.usageDay == t) d.copy(usageCount = d.usageCount + 1) else d.copy(usageDay = t, usageCount = 1)
    }

    fun learn(phrase: String) {
        val p = phrase.trim().take(160)
        if (p.length < 3) return
        update { d -> if (p in d.learned) d else d.copy(learned = (d.learned + p).takeLast(300)) }
    }
}

/** Clipboard helper. Copied text is marked sensitive and wiped after a minute if it is still mine. */
object Clip {
    private const val MARK = "app.persianbridge.mark"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wipe: Job? = null

    fun copy(ctx: Context, text: String) {
        val cm = ctx.applicationContext.getSystemService(ClipboardManager::class.java)
        val nonce = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText("Persian Bridge", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            putString(MARK, nonce)
        }
        cm.setPrimaryClip(clip)
        wipe?.cancel()
        wipe = scope.launch {
            delay(60_000)
            // I only clear it when the clip still carries my marker, so I never wipe something else.
            if (cm.primaryClipDescription?.extras?.getString(MARK) == nonce) cm.clearPrimaryClip()
        }
    }

    /** Only works while one of my windows has focus, which is true when the panel is open. */
    fun read(ctx: Context): String? =
        ctx.applicationContext.getSystemService(ClipboardManager::class.java)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
}
