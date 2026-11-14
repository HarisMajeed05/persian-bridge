package app.persianbridge

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One line of the open chat. fromMe is null when I can't tell. */
data class ChatMsg(val text: String, val fromMe: Boolean?)

data class OutResult(val persian: String, val back: String, val note: String, val engine: Mode)
data class InRow(val original: String, val english: String, val flag: String, val fromMe: Boolean)

sealed class TxError(val userMessage: String) : Exception(userMessage) {
    class NoKey : TxError("Add your Gemini API key in Settings first.")
    class Offline : TxError("No internet connection. Private mode still works.")
    class Network : TxError("The network request failed. Try again in a moment.")
    class Timeout : TxError("Google took too long to answer.")
    class RateLimited(val retryAfterSec: Int?) : TxError(
        "Free quota is used up for now." + (retryAfterSec?.let { " Try again in about ${it}s." } ?: " Try again in a minute.")
    )
    class Blocked(val reason: String) : TxError("Gemini refused this text ($reason). I added it to your sensitive list.")
    class Empty : TxError("Gemini sent back nothing usable.")
    class Malformed : TxError("The answer came back broken, so I ignored it.")
    class Auth : TxError("Google rejected the API key. Check it in Settings.")
    class Server(code: Int) : TxError("Google had a problem (HTTP $code). Try again shortly.")
    class Placeholders : TxError("The result altered a hidden value, so I threw it away.")
    class ModelMissing : TxError("The offline translation models are not downloaded yet.")
    class NoModels : TxError("This key has no Gemini text model I can use.")
}

/** Everything the prompts say lives here, so I can tune the register in one place. */
object Prompts {
    private const val Z = "‌"

    private const val PLACEHOLDERS =
        "Placeholders like [NAME_1], [PHONE_1] or [ADDR_1] stand for private values that are hidden from you. " +
            "Copy each placeholder into your output exactly as written, once for every time it appears in the input. " +
            "Never translate, rewrite, split, add or drop a placeholder."

    val OUT = """
You are a native Tehrani speaker helping an English speaker chat with Persian friends on Telegram. Turn the English text into the Persian people really type in chat (mohavere'i), not the bookish Persian (ketabi) that machine translation produces.

Rules for the Persian:
- Use spoken forms and contractions such as می${Z}رم، می${Z}خوام، نمی${Z}دونم، خونه، چیکار، اینجوری. Prefer everyday words over literary ones.
- Never translate word for word. Say what a real person would say in that situation, keeping the meaning, tone and humour.
- Use the zero width non-joiner (U+200C) the way Persian orthography needs it: after the می and نمی prefixes, before plural ها, and before attached suffixes.
- Use Persian punctuation (، ؛ ؟) and Persian digits (۰۱۲۳۴۵۶۷۸۹). Keep emojis. Keep names or brand words in Latin only when Persians write them that way in chat.
- Politeness follows the TONE line. Informal tones use tu (تو) forms. Colleague and formal tones use shoma (شما) forms. Never mix both in one message.
- Persian pronouns carry no gender, but some verbs and words reflect the speaker or listener. Use the notes and the chat context to decide. If you can't tell, choose neutral wording and say so in the note.
- Stay about as short as the English. Do not add greetings, explanations or emojis that were not there.
- The chat context is only there so replies fit the conversation and follow-ups sound natural. Do not translate it.

$PLACEHOLDERS

Fields to return:
- persian: the message, ready to paste.
- back_translation: a faithful, literal English translation of your Persian, so the sender can check the meaning. Do not just copy the original English.
- note: at most one short sentence about anything ambiguous (formality, gender, an idiom with several readings). Empty string when there is nothing worth saying.

Examples of the register wanted. Stiff is wrong, Natural is right.
English: I'm heading home, can we talk later?
Stiff: من به خانه می${Z}روم، می${Z}توانیم بعداً صحبت کنیم؟
Natural: دارم می${Z}رم خونه، بعداً حرف بزنیم؟

English: I don't know what to say.
Stiff: نمی${Z}دانم چه بگویم.
Natural: نمی${Z}دونم چی بگم.

English: What are you doing? Did you eat yet?
Stiff: تو چه کار می${Z}کنی؟ آیا غذا خوردی؟
Natural: چیکار می${Z}کنی؟ غذا خوردی؟

English: That was so funny, I laughed a lot.
Stiff: بسیار خنده${Z}دار بود، من بسیار خندیدم.
Natural: خیلی خنده${Z}دار بود، کلی خندیدم.

English: I miss you.
Stiff: من برای تو دلتنگ هستم.
Natural: دلم برات تنگ شده.

English (formal tone): Thank you very much for your help.
Stiff: از شما برای کمک${Z}تان بسیار سپاسگزارم.
Natural: خیلی ممنون از کمکتون.
""".trimIndent()

    val IN = """
You translate Persian (Farsi, Iran) chat messages into natural English for someone who cannot read Persian.

- Preserve meaning, tone, sarcasm, endearments, slang, teasing and emojis. Do not make a message more formal than it is.
- Messages may be Persian script, Finglish (Persian in Latin letters, like "salam khobi") or a mix of Persian and English. Understand all of them.
- Translate idioms by what they mean. When a literal reading would mislead, say so in the flag field.
- Persian pronouns carry no gender. Use the surrounding messages to pick he, she or they. If you can't tell, stay neutral and mention it in the flag field.
- flag: one short sentence, only when a word, the formality level, the gender or an idiom is genuinely ambiguous. Otherwise an empty string.
- Later messages can help you understand earlier ones. Return exactly one item per message, using the same id.

$PLACEHOLDERS

Examples:
قربونت برم، چطوری؟ -> Bless you, how are you doing? (flag: A very warm endearment, literally "let me be sacrificed for you". It just shows affection.)
salam khobi? chikar mikoni -> Hi, how are you? What are you up to? (flag: empty)
دمت گرم -> Nice one! (flag: Idiom of praise or thanks, literally "may your breath stay warm".)
ای بابا، بیخیال -> Oh come on, forget it. (flag: empty)
""".trimIndent()

    val CHECK = """
You are a strict native Persian copy editor. You get an English message, a Persian draft and the chat tone. Decide whether the draft reads like something a real person would type in a chat: spoken, natural, correct zero width non-joiners, correct punctuation, the right politeness level, and true to the English.

If it is already natural, return it unchanged with changed set to false. If it is stiff, too literal, wrong in tone or wrong in meaning, rewrite it and set changed to true. Return persian, back_translation (literal English of the final Persian), note and changed.

$PLACEHOLDERS
""".trimIndent()
}

/** Response schemas. Gemini is told to follow them and I validate the result anyway. */
private object Schemas {
    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    val OUT: JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties", JSONObject()
                .put("persian", str("The Persian message, ready to send."))
                .put("back_translation", str("Literal English translation of the Persian."))
                .put("note", str("One short sentence about ambiguity, or an empty string."))
        )
        .put("required", JSONArray(listOf("persian", "back_translation", "note")))

    val CHECK: JSONObject = JSONObject(OUT.toString()).also {
        it.getJSONObject("properties").put("changed", JSONObject().put("type", "boolean"))
        it.put("required", JSONArray(listOf("persian", "back_translation", "note", "changed")))
    }

    val IN: JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties", JSONObject().put(
                "items", JSONObject().put("type", "array").put(
                    "items", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put("id", JSONObject().put("type", "integer"))
                                .put("english", str("Natural English translation."))
                                .put("flag", str("One short sentence about ambiguity, or an empty string."))
                        )
                        .put("required", JSONArray(listOf("id", "english", "flag")))
                )
            )
        )
        .put("required", JSONArray(listOf("items")))
}

/** Thin Gemini client. The key only ever travels in the x-goog-api-key header. */
private class Gemini {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    // Docs now describe generationConfig.responseFormat. If the server ever rejects it,
    // I flip to the older responseMimeType plus responseJsonSchema pair and remember that.
    @Volatile private var legacy = false

    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 48
    }

    private data class Raw(val code: Int, val body: String, val retryHeader: Int?)

    fun clearCache() = synchronized(cache) { cache.clear() }

    private fun sha(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun call(model: String, key: String, system: String, user: String, schema: JSONObject): JSONObject {
        require(Models.isSafe(model))
        val ck = sha("$model|$system|$user")
        synchronized(cache) { cache[ck] }?.let { return JSONObject(it) }

        var raw = send(model, key, system, user, schema, legacy)
        if (raw.code == 400 && !raw.body.contains("API key", ignoreCase = true)) {
            legacy = !legacy
            raw = send(model, key, system, user, schema, legacy)
        }
        val out = parse(raw)
        synchronized(cache) { cache[ck] = out.toString() }
        return out
    }

    /** Cheap check for the Test button. Returns normally when the key and model work. */
    suspend fun ping(model: String, key: String) {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "Reply with the single word OK.")))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 512))
        val raw = execute(model, key, body)
        when (raw.code) {
            200 -> Unit
            else -> throw errorFor(raw)
        }
    }

    private suspend fun send(
        model: String, key: String, system: String, user: String, schema: JSONObject, legacyFormat: Boolean,
    ): Raw {
        val gen = JSONObject().put("maxOutputTokens", 4096)
        if (legacyFormat) {
            gen.put("responseMimeType", "application/json").put("responseJsonSchema", schema)
        } else {
            gen.put(
                "responseFormat",
                JSONObject().put("text", JSONObject().put("mimeType", "APPLICATION_JSON").put("schema", schema))
            )
        }
        if (model in Models.acceptsTemperature) gen.put("temperature", 0.2)
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", gen)
        return execute(model, key, body)
    }

    private suspend fun execute(model: String, key: String, body: JSONObject): Raw {
        Store.countRequest()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return fire(req, 262_144)
    }

    /**
     * Asks Google which models this key can use. I follow nextPageToken for at most 5 pages and keep
     * only Gemini text models that answer generateContent. This is not a generation call, so it
     * does not count toward my daily request tally.
     */
    suspend fun list(key: String): List<ModelInfo> {
        val found = LinkedHashMap<String, ModelInfo>()
        var token = ""
        for (page in 0 until 5) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "1000")
                .apply { if (token.isNotEmpty()) addQueryParameter("pageToken", token) }
                .build()
            val raw = fire(Request.Builder().url(url).header("x-goog-api-key", key).get().build(), 1_048_576)
            if (raw.code != 200) throw errorFor(raw)
            val root = try { JSONObject(raw.body) } catch (e: JSONException) { throw TxError.Malformed() }
            val arr = root.optJSONArray("models")
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                val canGenerate = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                val id = m.optString("name").removePrefix("models/")
                if (!canGenerate || !Models.isSafe(id) || !Models.isTextModel(id)) continue
                val label = m.optString("displayName").trim().take(40).ifBlank { id }
                found.putIfAbsent(id, ModelInfo(id, label))
            }
            token = root.optString("nextPageToken")
            if (token.isEmpty()) break
        }
        if (found.isEmpty()) throw TxError.NoModels()
        return found.values.sortedByDescending { it.id }
    }

    private suspend fun fire(req: Request, maxBytes: Int): Raw = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(mapIo(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val text = readLimited(r.body, maxBytes)
                        if (cont.isActive) cont.resume(Raw(r.code, text, r.header("Retry-After")?.toIntOrNull()))
                    }
                } catch (e: TxError) {
                    if (cont.isActive) cont.resumeWithException(e)
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(mapIo(e))
                }
            }
        })
    }

    private fun mapIo(e: IOException): TxError = when (e) {
        is SocketTimeoutException -> TxError.Timeout()
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> TxError.Offline()
        else -> if (e.message?.contains("timeout", true) == true) TxError.Timeout() else TxError.Network()
    }

    // I refuse to buffer more than the cap I pass in. A translation is never big, and a model list stays under 1 MB.
    private fun readLimited(body: ResponseBody?, maxBytes: Int): String {
        if (body == null) return ""
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        body.byteStream().use { s ->
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > maxBytes) throw TxError.Malformed()
            }
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    private fun errorFor(raw: Raw): TxError = when {
        raw.code == 429 -> TxError.RateLimited(
            raw.retryHeader ?: Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(raw.body)?.groupValues?.get(1)?.toIntOrNull()
        )
        raw.code == 401 || raw.code == 403 -> TxError.Auth()
        raw.code == 400 && raw.body.contains("API key", ignoreCase = true) -> TxError.Auth()
        raw.code in 500..599 -> TxError.Server(raw.code)
        else -> TxError.Server(raw.code)
    }

    private fun parse(raw: Raw): JSONObject {
        if (raw.code != 200) throw errorFor(raw)
        val root = try { JSONObject(raw.body) } catch (e: JSONException) { throw TxError.Malformed() }
        root.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
            ?.let { throw TxError.Blocked(it) }
        val cand = root.optJSONArray("candidates")?.optJSONObject(0) ?: throw TxError.Empty()
        val finish = cand.optString("finishReason")
        if (finish in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")) {
            throw TxError.Blocked(finish)
        }
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (!p.optBoolean("thought")) append(p.optString("text"))
            }
        }
        if (text.isBlank()) throw TxError.Empty()
        if (finish == "MAX_TOKENS") throw TxError.Malformed()
        val s = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw TxError.Malformed()
        return try { JSONObject(s.substring(a, b + 1)) } catch (e: JSONException) { throw TxError.Malformed() }
    }
}

enum class ModelState { UNKNOWN, MISSING, DOWNLOADING, READY, FAILED }

/** Private mode. ML Kit on-device translation, nothing leaves the phone. */
private class Local {
    private val translators = HashMap<String, Translator>()

    private fun get(src: String, dst: String): Translator = translators.getOrPut("$src>$dst") {
        Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
        )
    }

    suspend fun ready(): Boolean = try {
        val set = RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
        set.any { it.language == TranslateLanguage.PERSIAN }
    } catch (e: Exception) {
        false
    }

    suspend fun download() {
        val cond = DownloadConditions.Builder().build()
        get(TranslateLanguage.PERSIAN, TranslateLanguage.ENGLISH).downloadModelIfNeeded(cond).await()
        get(TranslateLanguage.ENGLISH, TranslateLanguage.PERSIAN).downloadModelIfNeeded(cond).await()
    }

    suspend fun translate(text: String, src: String, dst: String): String {
        if (text.isBlank()) return ""
        return try {
            get(src, dst).translate(text).await()
        } catch (e: Exception) {
            throw TxError.ModelMissing()
        }
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}

/** What leaves the phone for one Smart request, plus what I need to check and restore the answer. */
data class SmartPlan(
    val incoming: Boolean,
    val analysis: Analysis,
    val system: String,
    val user: String,
    val rawInputs: List<String>,
    val chat: ChatSettings,
)

/** The single entry point the panel talks to. */
object Bridge {
    private lateinit var app: Context
    private val gemini = Gemini()
    private val local = Local()
    val scanner = EntityScanner()

    private val _models = MutableStateFlow(ModelState.UNKNOWN)
    val models: StateFlow<ModelState> = _models.asStateFlow()
    private val _entities = MutableStateFlow(ModelState.UNKNOWN)
    val entities: StateFlow<ModelState> = _entities.asStateFlow()

    // Normalized copies of what I filled into Telegram this session, so I can tell my own lines apart.
    private val sent = ArrayDeque<String>()

    @Synchronized
    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        Store.init(ctx)
    }

    fun rememberSent(text: String) {
        sent.addFirst(Norm.loose(text))
        while (sent.size > 20) sent.removeLast()
    }

    fun isMine(text: String): Boolean {
        val n = Norm.loose(text)
        return sent.any { it.length >= 4 && n.contains(it) }
    }

    fun clearSession() {
        sent.clear()
        gemini.clearCache()
    }

    suspend fun refreshModels() {
        if (_models.value != ModelState.DOWNLOADING) _models.value = if (local.ready()) ModelState.READY else ModelState.MISSING
        if (_entities.value != ModelState.DOWNLOADING) _entities.value = if (scanner.ready()) ModelState.READY else ModelState.MISSING
    }

    suspend fun downloadTranslation() {
        _models.value = ModelState.DOWNLOADING
        _models.value = try { local.download(); ModelState.READY } catch (e: Exception) { ModelState.FAILED }
    }

    suspend fun downloadEntities() {
        _entities.value = ModelState.DOWNLOADING
        _entities.value = try { scanner.download(); ModelState.READY } catch (e: Exception) { ModelState.FAILED }
    }

    suspend fun testKey(key: String, model: String) = gemini.ping(model, key.trim())

    /** The models this key can use, straight from Google. */
    suspend fun listModels(key: String): List<ModelInfo> = gemini.list(key.trim())

    // ---------- planning: builds exactly what would leave the phone ----------

    private fun glossaryLines(d: AppData): String {
        val lines = d.glossary.filter { !it.isName }.map { g ->
            buildString {
                append("- ").append(g.term)
                if (g.persian.isNotBlank()) append(" | Persian: ").append(g.persian)
                if (g.english.isNotBlank()) append(" | English: ").append(g.english)
            }
        }
        return if (lines.isEmpty()) "none" else lines.joinToString("\n")
    }

    suspend fun planOut(typed: String, context: List<ChatMsg>, chat: ChatSettings, title: String?): SmartPlan {
        val d = Store.data.value
        val ctx = context.takeLast(d.contextSize)
        val inputs = listOf(typed, chat.notes) + ctx.map { it.text }
        val a = Privacy.analyze(inputs, d, title, scanner)
        val ctxText = if (ctx.isEmpty()) "(none)" else ctx.indices.joinToString("\n") { i ->
            (if (ctx[i].fromMe == true) "ME: " else "THEM: ") + a.redacted[2 + i].replace('\n', ' ')
        }
        val user = """
CHAT CONTEXT (oldest first):
$ctxText

TONE: ${chat.tone.label}. ${chat.tone.prompt}
NOTES ABOUT THIS PERSON: ${a.redacted[1].ifBlank { "none" }}
GLOSSARY:
${glossaryLines(d)}

ENGLISH TEXT TO TRANSLATE:
${a.redacted[0]}
""".trimIndent()
        return SmartPlan(false, a, Prompts.OUT, user, listOf(typed), chat)
    }

    suspend fun planIn(msgs: List<ChatMsg>, chat: ChatSettings, title: String?): SmartPlan {
        val d = Store.data.value
        val inputs = listOf(chat.notes) + msgs.map { it.text }
        val a = Privacy.analyze(inputs, d, title, scanner)
        val list = msgs.indices.joinToString("\n") { i -> "[${i + 1}] " + a.redacted[1 + i].replace('\n', ' ') }
        val user = """
MESSAGES (oldest first). Translate every one:
$list

CHAT TONE: ${chat.tone.label}
NOTES ABOUT THIS PERSON: ${a.redacted[0].ifBlank { "none" }}
GLOSSARY:
${glossaryLines(d)}
""".trimIndent()
        return SmartPlan(true, a, Prompts.IN, user, msgs.map { it.text }, chat)
    }

    // ---------- running ----------

    private fun key(d: AppData): String = d.apiKey.trim().ifEmpty { throw TxError.NoKey() }

    suspend fun runOut(plan: SmartPlan): OutResult {
        val d = Store.data.value
        val a = plan.analysis
        try {
            val j = gemini.call(d.model, key(d), plan.system, plan.user, Schemas.OUT)
            var fa = j.optString("persian").trim()
            var back = j.optString("back_translation").trim()
            var note = j.optString("note").trim()
            if (fa.isEmpty()) throw TxError.Empty()
            if (!Privacy.intact(a.redacted[0], fa, a.map)) throw TxError.Placeholders()

            if (d.doubleCheck) {
                val check = """
TONE: ${plan.chat.tone.label}. ${plan.chat.tone.prompt}
ENGLISH ORIGINAL:
${a.redacted[0]}

PERSIAN DRAFT:
$fa
""".trimIndent()
                val c = gemini.call(d.model, key(d), Prompts.CHECK, check, Schemas.CHECK)
                if (c.optBoolean("changed")) {
                    val fa2 = c.optString("persian").trim()
                    if (fa2.isNotEmpty() && Privacy.intact(a.redacted[0], fa2, a.map)) {
                        fa = fa2
                        back = c.optString("back_translation").trim().ifEmpty { back }
                        note = c.optString("note").trim().ifEmpty { note }
                    }
                }
            }
            return OutResult(
                Privacy.restore(fa, a.map, true),
                Privacy.restore(back, a.map, false),
                Privacy.restore(note, a.map, false),
                Mode.SMART,
            )
        } catch (e: TxError.Blocked) {
            Store.learn(plan.rawInputs.first())
            throw e
        }
    }

    suspend fun runIn(plan: SmartPlan, msgs: List<ChatMsg>): List<InRow> {
        val d = Store.data.value
        val a = plan.analysis
        try {
            val j = gemini.call(d.model, key(d), plan.system, plan.user, Schemas.IN)
            val arr = j.optJSONArray("items") ?: throw TxError.Malformed()
            val byId = HashMap<Int, JSONObject>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { byId[it.optInt("id")] = it }
            return msgs.mapIndexed { i, m ->
                val item = byId[i + 1] ?: throw TxError.Malformed()
                val en = item.optString("english").trim()
                if (en.isEmpty()) throw TxError.Empty()
                if (!Privacy.intact(a.redacted[1 + i], en, a.map)) throw TxError.Placeholders()
                InRow(
                    m.text,
                    Privacy.restore(en, a.map, false),
                    Privacy.restore(item.optString("flag").trim(), a.map, false),
                    m.fromMe == true,
                )
            }
        } catch (e: TxError.Blocked) {
            msgs.take(8).forEach { Store.learn(it.text) }
            throw e
        }
    }

    suspend fun privateOut(text: String): OutResult {
        val fa = local.translate(text, TranslateLanguage.ENGLISH, TranslateLanguage.PERSIAN)
        val back = local.translate(fa, TranslateLanguage.PERSIAN, TranslateLanguage.ENGLISH)
        return OutResult(fa, back, "Private mode is literal and stiffer than Smart. Read it twice before sending.", Mode.PRIVATE)
    }

    suspend fun privateIn(msgs: List<ChatMsg>): List<InRow> = msgs.map {
        val en = try {
            local.translate(it.text, TranslateLanguage.PERSIAN, TranslateLanguage.ENGLISH)
        } catch (e: TxError.ModelMissing) {
            throw e
        }
        InRow(it.text, en, "", it.fromMe == true)
    }

    fun close() {
        local.close()
        scanner.close()
    }
}
