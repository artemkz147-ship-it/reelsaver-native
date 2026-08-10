package com.artem.neuroreader

import android.content.Context
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline Russian text preprocessor for TTS.
 *
 * The large stress dictionary lives in APK assets. It is warmed in a background
 * thread so the first spoken phrase never blocks on parsing tens of thousands
 * of entries. Until it is ready, common stresses + contextual homographs work.
 */
class RussianProsody(private val context: Context) {
    @Volatile private var stressMap: Map<String, String>? = null
    private val warming = AtomicBoolean(false)

    fun warmUpAsync() {
        if (stressMap != null || !warming.compareAndSet(false, true)) return
        Thread({
            try {
                stressMap = loadStressDictionary()
            } finally {
                warming.set(false)
            }
        }, "NeuroReader-StressDict").start()
    }

    fun prepare(text: String): String {
        val normalized = text
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .trim()

        if (normalized.isBlank()) return normalized

        val withYo = restoreCommonYo(normalized)
        val withContext = applyContextHomographs(withYo)
        val dictionary = stressMap
        return if (dictionary != null) {
            applyDictionaryStress(withContext, dictionary)
        } else {
            applyFallbackStress(withContext)
        }
    }

    private fun loadStressDictionary(): Map<String, String> {
        val result = HashMap<String, String>(110_000)

        runCatching {
            context.assets.open("tts/ru-stress-dict.txt")
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    lines.forEach { raw ->
                        val value = raw.trim()
                        if (value.isEmpty() || value.startsWith("#") || value.startsWith(";")) {
                            return@forEach
                        }
                        if ('+' !in value && '\'' !in value) return@forEach

                        val accented = plusToAcute(value.replace("'", "+"))
                        val key = accented
                            .replace("\u0301", "")
                            .lowercase(Locale.ROOT)

                        if (
                            key.length >= 3 &&
                            key.none { it.isWhitespace() } &&
                            key.any { it in "аеёиоуыэюя" }
                        ) {
                            result.putIfAbsent(key, accented)
                        }
                    }
                }
        }

        fallbackStress.forEach { (plain, accented) ->
            result.putIfAbsent(plain, accented)
        }
        return result
    }

    private fun applyDictionaryStress(
        text: String,
        dictionary: Map<String, String>
    ): String {
        return russianWord.replace(text) { match ->
            val original = match.value
            val key = original.lowercase(Locale.ROOT)
            val accented = dictionary[key] ?: return@replace original
            preserveCase(original, accented)
        }
    }

    private fun applyFallbackStress(text: String): String {
        return russianWord.replace(text) { match ->
            val original = match.value
            val accented = fallbackStress[original.lowercase(Locale.ROOT)]
                ?: return@replace original
            preserveCase(original, accented)
        }
    }

    private fun applyContextHomographs(text: String): String {
        var out = text

        out = replaceHomograph(out, "замок") { ctx ->
            if (ctx.hasAny("ключ", "двер", "запер", "отпер", "закры", "откры", "скваж", "засов")) {
                "замо́к"
            } else {
                "за́мок"
            }
        }

        out = replaceHomograph(out, "мука") { ctx ->
            if (ctx.hasAny("хлеб", "тесто", "пшен", "ржан", "мешок", "килограмм", "просе", "выпеч")) {
                "мука́"
            } else {
                "му́ка"
            }
        }

        out = replaceHomograph(out, "духи") { ctx ->
            if (ctx.hasAny("флакон", "запах", "аромат", "парфюм", "надуш", "одеколон")) {
                "духи́"
            } else {
                "ду́хи"
            }
        }

        out = replaceHomograph(out, "атлас") { ctx ->
            if (ctx.hasAny("ткан", "шелк", "шёлк", "плать", "лента", "материал")) {
                "атла́с"
            } else {
                "а́тлас"
            }
        }

        out = replaceHomograph(out, "орган") { ctx ->
            if (ctx.hasAny("музык", "клавиш", "собор", "концерт", "играл", "мелод")) {
                "орга́н"
            } else {
                "о́рган"
            }
        }

        out = replaceHomograph(out, "ирис") { ctx ->
            if (ctx.hasAny("конфет", "слад", "жеватель", "сливоч")) {
                "ири́с"
            } else {
                "и́рис"
            }
        }

        out = replaceHomograph(out, "хлопок") { ctx ->
            if (ctx.hasAny("ткан", "поле", "сырь", "волокн", "урожай")) {
                "хлопо́к"
            } else {
                "хло́пок"
            }
        }

        out = replaceHomograph(out, "кружки") { ctx ->
            if (ctx.hasAny("секци", "занят", "дворец", "школь", "творче")) {
                "кружки́"
            } else {
                "кру́жки"
            }
        }

        out = replaceHomograph(out, "плачу") { ctx ->
            if (ctx.hasAny("деньг", "рубл", "счёт", "счет", "покуп", "налог", "цен")) {
                "плачу́"
            } else {
                "пла́чу"
            }
        }

        out = replaceHomograph(out, "стоит") { ctx ->
            if (ctx.hasAny("рубл", "цен", "доллар", "евро", "сколько", "дорог", "дешев")) {
                "сто́ит"
            } else {
                "стои́т"
            }
        }

        out = replaceHomograph(out, "уже") { ctx ->
            if (ctx.hasAny("чем", "шир", "коридор", "проход", "талия")) {
                "у́же"
            } else {
                "уже́"
            }
        }

        return out
    }

    private fun replaceHomograph(
        text: String,
        plain: String,
        choose: (String) -> String
    ): String {
        val regex = Regex(
            "(?iu)(?<![А-Яа-яЁё])${Regex.escape(plain)}(?![А-Яа-яЁё])"
        )
        return regex.replace(text) { match ->
            val left = (match.range.first - 60).coerceAtLeast(0)
            val right = (match.range.last + 61).coerceAtMost(text.length)
            val nearby = text.substring(left, right).lowercase(Locale.ROOT)
            preserveCase(match.value, choose(nearby))
        }
    }

    private fun restoreCommonYo(text: String): String {
        var out = text
        commonYo.forEach { (plain, correct) ->
            val regex = Regex(
                "(?iu)(?<![А-Яа-яЁё])${Regex.escape(plain)}(?![А-Яа-яЁё])"
            )
            out = regex.replace(out) { preserveCase(it.value, correct) }
        }
        return out
    }

    private fun plusToAcute(value: String): String {
        val builder = StringBuilder(value.length + 2)
        for (char in value) {
            if (char == '+' && builder.isNotEmpty()) {
                builder.append('\u0301')
            } else {
                builder.append(char)
            }
        }
        return builder.toString()
    }

    private fun preserveCase(source: String, target: String): String {
        if (source.all { !it.isLetter() || it.isUpperCase() }) {
            return target.uppercase(Locale.ROOT)
        }
        if (source.firstOrNull()?.isUpperCase() == true) {
            return target.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
        return target
    }

    private fun String.hasAny(vararg fragments: String): Boolean =
        fragments.any { contains(it) }

    companion object {
        private val russianWord = Regex("[А-Яа-яЁё-]{3,}")

        private val commonYo = linkedMapOf(
            "еще" to "ещё",
            "ее" to "её",
            "елка" to "ёлка",
            "елки" to "ёлки",
            "ежик" to "ёжик",
            "ежика" to "ёжика",
            "береза" to "берёза",
            "березы" to "берёзы",
            "слезы" to "слёзы",
            "слез" to "слёз",
            "шепот" to "шёпот",
            "желтый" to "жёлтый",
            "желтая" to "жёлтая",
            "желтое" to "жёлтое",
            "черный" to "чёрный",
            "черная" to "чёрная",
            "черное" to "чёрное",
            "черт" to "чёрт"
        )

        private val fallbackStress = mapOf(
            "красивее" to "краси́вее",
            "звонит" to "звони́т",
            "звонишь" to "звони́шь",
            "торты" to "то́рты",
            "тортов" to "то́ртов",
            "каталог" to "катало́г",
            "договор" to "догово́р",
            "договоры" to "догово́ры",
            "жалюзи" to "жалюзи́",
            "баловать" to "балова́ть",
            "начала" to "начала́",
            "начался" to "начался́",
            "приняла" to "приняла́",
            "включит" to "включи́т",
            "облегчить" to "облегчи́ть",
            "углубить" to "углуби́ть",
            "цемент" to "цеме́нт",
            "щавель" to "щаве́ль",
            "свекла" to "свёкла",
            "средства" to "сре́дства",
            "километр" to "киломе́тр",
            "километров" to "киломе́тров",
            "диспансер" to "диспансе́р",
            "эксперт" to "экспе́рт",
            "ходатайство" to "хода́тайство"
        )
    }
}
