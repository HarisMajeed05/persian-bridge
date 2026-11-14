package app.persianbridge

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractionRemoteModel
import com.google.mlkit.nl.entityextraction.EntityExtractor
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.math.BigInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Tiny bridge so I don't need the play-services coroutines artifact for one function. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

enum class Kind(val tag: String, val label: String) {
    NAME("NAME", "name"),
    PHONE("PHONE", "phone number"),
    EMAIL("EMAIL", "email address"),
    URL("URL", "link or website"),
    HANDLE("HANDLE", "username"),
    CARD("CARD", "payment card number"),
    IBAN("IBAN", "bank account (IBAN or Sheba)"),
    DATE("DATE", "full date, could be a birth date"),
    ID("ID", "ID number"),
    CODE("CODE", "one-time code"),
    SECRET("SECRET", "password or secret"),
    COORD("COORD", "coordinates"),
    ADDR("ADDR", "address"),
    NUM("NUM", "long number"),
}

data class Span(val start: Int, val end: Int, val kind: Kind, val entry: GlossaryEntry? = null)

enum class Topic(val label: String) {
    HEALTH("health"),
    MONEY("money or debt"),
    LEGAL("legal or visa trouble"),
    INTIMACY("relationships or intimacy"),
    RELIGION("religion"),
    POLITICS("politics"),
    SECRET("secrets"),
    VULGAR("vulgar or abusive language"),
}

/** Layer 1. Everything that makes two spellings of the same thing look equal. */
object Norm {
    private const val FA = "۰۱۲۳۴۵۶۷۸۹"
    private const val AR = "٠١٢٣٤٥٦٧٨٩"
    private val marks = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val spaced = Regex("(?<![\\p{L}\\p{N}])(?:\\p{L}[\\s.\\-_*]+){2,}\\p{L}(?![\\p{L}\\p{N}])")
    private val leet = Regex("(?<=\\p{L})[0134578@$](?=\\p{L})")
    private val repeats = Regex("(\\p{L})\\1+")
    private val ws = Regex("\\s+")
    private val split = Regex("[^\\p{L}\\p{N}]+")

    /** Persian and Arabic-Indic digits to ASCII. Same length, so spans stay valid. */
    fun digits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val i = FA.indexOf(ch).takeIf { it >= 0 } ?: AR.indexOf(ch)
            sb.append(if (i >= 0) '0' + i else ch)
        }
        return sb.toString()
    }

    fun isLatin(w: String) = w.isNotEmpty() && w.all { it in 'a'..'z' || it in '0'..'9' }

    /** Loose form used only for detection, never for what gets sent. */
    fun loose(raw: String): String {
        var s = digits(raw).lowercase()
        s = s.replace('ي', 'ی').replace('ك', 'ک').replace('ة', 'ه').replace('ۀ', 'ه')
            .replace('أ', 'ا').replace('إ', 'ا').replace('ؤ', 'و').replace('ئ', 'ی')
        s = marks.replace(s, "")
        s = s.replace('‌', ' ')
        s = spaced.replace(s) { m -> m.value.filter { it.isLetter() } }
        s = leet.replace(s) { m ->
            when (m.value[0]) {
                '0' -> "o"; '1' -> "i"; '3' -> "e"; '4' -> "a"; '5' -> "s"; '7' -> "t"
                '@' -> "a"; '$' -> "s"; else -> m.value
            }
        }
        return ws.replace(s, " ").trim()
    }

    fun tokens(loose: String): List<String> = loose.split(split).filter { it.isNotEmpty() }

    /** Rough phonetic key so Roman Urdu and Finglish spelling variants land on the same string. */
    fun key(word: String): String {
        var w = repeats.replace(word, "$1")
        for ((a, b) in listOf("ph" to "f", "kh" to "k", "gh" to "g", "bh" to "b", "dh" to "d", "th" to "t",
            "sh" to "s", "ch" to "c", "jh" to "j", "zh" to "j")) w = w.replace(a, b)
        w = w.replace("h", "").replace('c', 'k').replace('q', 'k').replace('w', 'v')
            .replace('e', 'a').replace('o', 'u')
        return repeats.replace(w, "$1")
    }
}

/** Layer 3, honestly a lexicon and not a trained model. See the README for the accuracy notes. */
object Topics {
    private class Lex(raw: String) {
        val latin: Set<String>
        val persian: List<String>

        init {
            val words = raw.split(' ').filter { it.isNotBlank() }
            latin = words.filter { Norm.isLatin(it) }.map { Norm.key(it) }.toSet()
            persian = words.filter { !Norm.isLatin(it) }.map { Norm.loose(it).replace(" ", "") }
        }
    }

    private val lex: Map<Topic, Lex> = mapOf(
        Topic.HEALTH to Lex(
            "doctor hospital medicine medication pills surgery cancer diabetes pregnant pregnancy depression " +
                "anxiety therapy therapist covid disease infection symptoms diagnosed clinic dawai bimar bimari " +
                "bimarestan hamele afsordegi ghors " +
                "دکتر بیمار بیمارستان دارو قرص جراحی سرطان دیابت حامله بارداری افسردگی اضطراب درمان روانشناس عفونت کرونا تشخیص کلینیک مریض"
        ),
        Topic.MONEY to Lex(
            "money loan debt owe borrow salary income bank payment transfer rent tax bitcoin crypto cash paisa " +
                "paise qarz qarza gharz udhaar udhar tankhwah vam hoghoogh hoqooq pool pul toman tuman rial rupay " +
                "پول وام قرض بدهی بدهکار حقوق حساب کارت بانک تومان ریال دلار اجاره مالیات واریز ارز کریپتو"
        ),
        Topic.LEGAL to Lex(
            "police lawyer court arrest jail prison visa immigration asylum deport passport lawsuit illegal crime " +
                "wakeel adalat giraftar polis vakil dadgah zendan panahandegi panahjoo ghachagh " +
                "پلیس وکیل دادگاه زندان بازداشت ویزا پناهندگی پناهجو اخراج قاچاق جرم شکایت پاسپورت گذرنامه مهاجرت اقامت"
        ),
        Topic.INTIMACY to Lex(
            "kiss sex naked nude horny virgin affair cheating cheated divorce breakup hookup chumma talaq dhokha " +
                "لخت بوس سکس خیانت طلاق ازدواج عاشق رابطه جنسی بکارت"
        ),
        Topic.RELIGION to Lex(
            "allah prayer mosque church islam muslim christian jewish hindu atheist convert quran bible hijab " +
                "ramadan namaz roza shia sunni ahmadi bahai halal masjid kafir mazhab " +
                "حجاب نماز روزه قرآن مسجد کلیسا مسلمان مسیحی یهودی بهایی ملحد شیعه سنی مذهب اسلام اذان"
        ),
        Topic.POLITICS to Lex(
            "government regime president election vote protest politics sanctions army military revolution " +
                "opposition censorship vpn filtering irgc khamenei hukumat sarkar siyasat fauj jang hokumat " +
                "enteghabat tahrim sepah " +
                "انتخابات حکومت رژیم اعتراض سیاست تحریم جنگ ارتش سپاه انقلاب اپوزیسیون سانسور فیلترشکن فیلتر بسیج رهبر جمهوری"
        ),
        Topic.SECRET to Lex(
            "secret confidential raaz raz mahramane chupana راز محرمانه خصوصی"
        ),
        Topic.VULGAR to Lex(
            "fuck fucking shit bitch asshole dick pussy cunt slut whore bastard motherfucker " +
                "bhenchod madarchod chutiya gandu harami kutta kamina lund randi bhosdi " +
                "jende harumzade kir " +
                "کیر کصافت جنده کونی حرومزاده مادرجنده کسکش"
        ),
    )

    /** Returns one human readable reason per hit. Nothing here is stored. */
    fun detect(texts: List<String>, d: AppData): List<String> {
        val reasons = LinkedHashSet<String>()
        val userKeys = d.wordlist.map { Norm.loose(it) }.filter { it.length >= 2 }
        val learned = d.learned.map { Norm.loose(it) }.filter { it.length >= 3 }
        for (raw in texts) {
            val loose = Norm.loose(raw)
            if (loose.isEmpty()) continue
            val tokens = Norm.tokens(loose)
            val keys = tokens.filter { Norm.isLatin(it) && it.length >= 3 }.map { Norm.key(it) }.toSet()
            val persian = tokens.filter { !Norm.isLatin(it) }
            for ((topic, l) in lex) {
                val hit = keys.any { it in l.latin } ||
                    persian.any { t -> l.persian.any { s -> s.length >= 3 && t.startsWith(s) } }
                if (hit) reasons += "Topic: ${topic.label}"
            }
            if (userKeys.any { w -> if (Norm.isLatin(w)) Norm.key(w) in keys else w in tokens }) {
                reasons += "Word from your own list"
            }
            if (learned.any { loose.contains(it) }) reasons += "Matches your learned sensitive list"
        }
        return reasons.toList()
    }
}

/** Layer 2. Regex plus checksums, plus ML Kit when its models are on the device. */
object Detect {
    private val card = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
    private val iban = Regex("(?<![A-Za-z0-9])[A-Za-z]{2}\\d{2}(?: ?[A-Za-z0-9]){11,30}(?![A-Za-z0-9])")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val url = Regex(
        "(?i)(?:https?://|www\\.|t\\.me/|telegram\\.me/)\\S+|" +
            "(?<![@\\w.])[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|ir|io|me|info|app|co|dev|xyz)(?:/\\S*)?(?![\\w@])"
    )
    private val date = Regex("(?<!\\d)(?:(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})|(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4}))(?!\\d)")
    private val coord = Regex("[-+]?\\d{1,3}\\.\\d{3,}\\s*[,،]\\s*[-+]?\\d{1,3}\\.\\d{3,}")
    private val secret = Regex("(?i)(?:password|passcode|passwd|pwd|پسورد|رمز عبور|رمز)\\s*(?:is|هست|:|=|-)?\\s*(\\S{4,})")
    private val code = Regex("(?i)(?:code|otp|pin|verification|verify|کد|رمز|kod)\\D{0,25}?(\\d{4,8})(?!\\d)")
    private val ten = Regex("(?<!\\d)\\d{10}(?!\\d)")
    private val phone = Regex("(?<![\\p{L}\\p{N}])\\+?\\d(?:[\\s().\\-]?\\d){7,14}(?![\\p{L}\\p{N}])")
    private val num = Regex("(?<!\\d)\\d{9,}(?!\\d)")
    private val handle = Regex("(?<![\\w@])@[A-Za-z][A-Za-z0-9_]{3,31}")
    private val addr = Regex(
        "(?i)(?:خیابان|کوچه|بلوار|میدان|پلاک|واحد|کدپستی|کد پستی|khiaban|khiyaban|kooche|kuche|bolvar|meydan|" +
            "pelak|plak|vahed|street|road|avenue|apartment|apt|flat|postal code|zip code)" +
            "(?:\\s+[\\p{L}\\p{N}\\-/]{1,20}){1,5}"
    )

    fun luhn(digits: String): Boolean {
        var sum = 0
        var alt = false
        for (i in digits.indices.reversed()) {
            var n = digits[i] - '0'
            if (alt) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alt = !alt
        }
        return digits.isNotEmpty() && sum % 10 == 0
    }

    fun ibanOk(raw: String): Boolean {
        val s = raw.replace(" ", "").uppercase()
        if (s.length !in 15..34) return false
        val moved = s.substring(4) + s.substring(0, 4)
        val sb = StringBuilder()
        for (c in moved) sb.append(if (c.isDigit()) c.toString() else (c - 'A' + 10).toString())
        return BigInteger(sb.toString()).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    /** Iranian national code checksum. */
    fun nationalIdOk(s: String): Boolean {
        if (s.length != 10 || s.all { it == s[0] }) return false
        val sum = (0..8).sumOf { (s[it] - '0') * (10 - it) }
        val r = sum % 11
        val check = if (r < 2) r else 11 - r
        return check == s[9] - '0'
    }

    fun structured(text: String, glossary: List<GlossaryEntry>, titleTokens: List<String>): List<Span> {
        val t = Norm.digits(text)
        val out = ArrayList<Span>()
        fun add(s: Span) {
            if (s.end > s.start && out.none { it.start < s.end && s.start < it.end }) out += s
        }

        // Names come first so glossary names always keep their preferred spelling on restore.
        for (g in glossary.filter { it.isName }) {
            for (form in listOf(g.term, g.persian, g.english).filter { it.isNotBlank() }.distinct()) {
                Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(form) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                    .findAll(text).forEach { add(Span(it.range.first, it.range.last + 1, Kind.NAME, g)) }
            }
        }
        for (tok in titleTokens) {
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(tok) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                .findAll(text).forEach { add(Span(it.range.first, it.range.last + 1, Kind.NAME)) }
        }

        card.findAll(t).forEach {
            val d = it.value.filter(Char::isDigit)
            if (d.length in 13..19 && luhn(d)) add(Span(it.range.first, it.range.last + 1, Kind.CARD))
        }
        iban.findAll(t).forEach {
            if (ibanOk(it.value)) add(Span(it.range.first, it.range.last + 1, Kind.IBAN))
        }
        email.findAll(t).forEach { add(Span(it.range.first, it.range.last + 1, Kind.EMAIL)) }
        url.findAll(t).forEach { add(Span(it.range.first, it.range.last + 1, Kind.URL)) }
        date.findAll(t).forEach {
            val y = (it.groupValues[1].ifEmpty { it.groupValues[6] }).toIntOrNull() ?: 0
            if (y in 1300..1420 || y in 1900..2100) add(Span(it.range.first, it.range.last + 1, Kind.DATE))
        }
        coord.findAll(t).forEach { add(Span(it.range.first, it.range.last + 1, Kind.COORD)) }
        secret.findAll(t).forEach { m -> m.groups[1]?.range?.let { add(Span(it.first, it.last + 1, Kind.SECRET)) } }
        code.findAll(t).forEach { m -> m.groups[1]?.range?.let { add(Span(it.first, it.last + 1, Kind.CODE)) } }
        ten.findAll(t).forEach {
            if (nationalIdOk(it.value)) add(Span(it.range.first, it.range.last + 1, Kind.ID))
        }
        phone.findAll(t).forEach {
            val n = it.value.count(Char::isDigit)
            if (n in 8..15) add(Span(it.range.first, it.range.last + 1, Kind.PHONE))
        }
        num.findAll(t).forEach { add(Span(it.range.first, it.range.last + 1, Kind.NUM)) }
        handle.findAll(t).forEach { add(Span(it.range.first, it.range.last + 1, Kind.HANDLE)) }
        addr.findAll(t).forEach { add(Span(it.range.first, it.range.last + 1, Kind.ADDR)) }
        return out
    }
}

/**
 * ML Kit Entity Extraction. It has no Persian model, so I run the English and Arabic ones,
 * which still catch emails, links, cards, IBANs and most phone numbers in Persian chats.
 */
class EntityScanner {
    private val langs = listOf(EntityExtractorOptions.ENGLISH, EntityExtractorOptions.ARABIC)
    private val clients = HashMap<String, EntityExtractor>()

    private fun client(lang: String): EntityExtractor = clients.getOrPut(lang) {
        EntityExtraction.getClient(EntityExtractorOptions.Builder(lang).build())
    }

    suspend fun ready(): Boolean = try {
        val mgr = RemoteModelManager.getInstance()
        langs.all { mgr.isModelDownloaded(EntityExtractionRemoteModel.Builder(it).build()).await() }
    } catch (e: Exception) {
        false
    }

    suspend fun download() {
        for (l in langs) client(l).downloadModelIfNeeded().await()
    }

    suspend fun scan(text: String): List<Span> {
        if (text.isBlank() || !ready()) return emptyList()
        val out = ArrayList<Span>()
        for (l in langs) {
            val found = try {
                client(l).annotate(EntityExtractionParams.Builder(text).build()).await()
            } catch (e: Exception) {
                emptyList()
            }
            for (a in found) {
                val kind = a.entities.firstNotNullOfOrNull { e ->
                    when (e.type) {
                        Entity.TYPE_ADDRESS -> Kind.ADDR
                        Entity.TYPE_EMAIL -> Kind.EMAIL
                        Entity.TYPE_IBAN -> Kind.IBAN
                        Entity.TYPE_PAYMENT_CARD -> Kind.CARD
                        Entity.TYPE_PHONE -> Kind.PHONE
                        Entity.TYPE_URL -> Kind.URL
                        else -> null
                    }
                } ?: continue
                out += Span(a.start, a.end, kind)
            }
        }
        return out
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}

data class Replacement(val original: String, val kind: Kind, val entry: GlossaryEntry?)

/** What the privacy layers decided for one request. Lives in memory only. */
data class Analysis(
    val inputs: List<String>,
    val redacted: List<String>,
    val map: Map<String, Replacement>,
    val reasons: List<String>,
    val hiddenNames: Int,
) {
    val flagged: Boolean get() = reasons.isNotEmpty()
}

object Privacy {
    suspend fun analyze(
        inputs: List<String>,
        d: AppData,
        chatTitle: String?,
        scanner: EntityScanner?,
    ): Analysis {
        // The chat title is almost always the other person's name, so its words get hidden too.
        val titleTokens = chatTitle.orEmpty().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
        val reasons = LinkedHashSet<String>()
        val perInput = inputs.map { text ->
            val spans = ArrayList<Span>(Detect.structured(text, d.glossary, titleTokens))
            scanner?.scan(text)?.forEach { s ->
                if (spans.none { it.start < s.end && s.start < it.end }) spans += s
            }
            spans.sortedBy { it.start }
        }
        perInput.flatten().filter { it.kind != Kind.NAME }.map { it.kind }.distinct()
            .forEach { reasons += "${it.label.replaceFirstChar { c -> c.uppercase() }} detected" }
        reasons += Topics.detect(inputs, d)

        val byKey = LinkedHashMap<String, String>()
        val map = LinkedHashMap<String, Replacement>()
        val counters = HashMap<Kind, Int>()
        var names = 0
        val redacted = inputs.mapIndexed { i, text ->
            val sb = StringBuilder(text)
            for (s in perInput[i].sortedByDescending { it.start }) {
                val original = text.substring(s.start, s.end)
                val k = s.kind.name + "|" + original.lowercase()
                val ph = byKey.getOrPut(k) {
                    val n = (counters[s.kind] ?: 0) + 1
                    counters[s.kind] = n
                    "[${s.kind.tag}_$n]".also { map[it] = Replacement(original, s.kind, s.entry) }
                }
                sb.replace(s.start, s.end, ph)
                if (s.kind == Kind.NAME) names++
            }
            sb.toString()
        }
        return Analysis(inputs, redacted, map, reasons.toList(), names)
    }

    /** Puts the real values back. Names use the spelling I chose for the language of the result. */
    fun restore(text: String, map: Map<String, Replacement>, toPersian: Boolean): String {
        var out = text
        for ((ph, r) in map) {
            val form = r.entry?.let { if (toPersian) it.persian else it.english }.orEmpty()
            out = out.replace(ph, form.ifBlank { r.original })
        }
        return out
    }

    private fun count(s: String, sub: String) = Regex(Regex.escape(sub)).findAll(s).count()

    /** Every placeholder must come back exactly as often as it went out, and no stray ones may appear. */
    fun intact(source: String, output: String, map: Map<String, Replacement>): Boolean {
        for (ph in map.keys) if (count(source, ph) != count(output, ph)) return false
        return Regex("\\[[A-Z]+_\\d+]").findAll(output).none { it.value !in map }
    }
}
