package io.estilix.salon

import java.math.BigDecimal
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** One working interval as the API speaks it: ISO weekday (1 = Monday) and "HH:mm" times. */
data class HoursInterval(val weekday: Int, val start: String, val end: String) {

    internal val startMinute: Int? get() = WeeklyHours.parse(start)
    internal val endMinute: Int? get() = WeeklyHours.parse(end)

    companion object {
        fun ofMinutes(weekday: Int, start: Int, end: Int) = HoursInterval(weekday, WeeklyHours.format(start), WeeklyHours.format(end))
    }
}

/** Rules for a week of intervals (spec salon-profile "Weekly opening hours with split shifts"). */
object WeeklyHours {

    const val GRID = 15
    private val TIME = Regex("^([01]\\d|2[0-4]):([0-5]\\d)$")

    fun parse(value: String): Int? {
        val m = TIME.matchEntire(value) ?: return null
        val minutes = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        return if (minutes > 24 * 60) null else minutes
    }

    fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** Returns field errors keyed by `intervals[i]`; empty when the week is valid. */
    fun validate(intervals: List<HoursInterval>, field: String = "intervals"): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        intervals.forEachIndexed { i, interval ->
            val key = "$field[$i]"
            val start = interval.startMinute
            val end = interval.endMinute
            when {
                interval.weekday !in 1..7 -> errors[key] = "dia da semana inválido"
                start == null || end == null -> errors[key] = "use o formato HH:mm"
                start % GRID != 0 || end % GRID != 0 -> errors[key] = "use horários de 15 em 15 minutos"
                start >= end -> errors[key] = "o início precisa ser antes do fim"
            }
        }
        if (errors.isNotEmpty()) return errors
        intervals.withIndex().groupBy { it.value.weekday }.forEach { (weekday, day) ->
            val sorted = day.sortedBy { it.value.startMinute }
            sorted.zipWithNext().forEach { (a, b) ->
                if (b.value.startMinute!! < a.value.endMinute!!) {
                    errors["$field[${b.index}]"] = "sobrepõe outro horário de ${dayName(weekday)}"
                }
            }
        }
        return errors
    }

    /** Intersection of [own] with [salon], per weekday; used for a professional's effective hours. */
    fun intersect(own: List<HoursInterval>, salon: List<HoursInterval>): List<HoursInterval> =
        own.flatMap { o ->
            salon.filter { it.weekday == o.weekday }.mapNotNull { s ->
                val start = maxOf(o.startMinute!!, s.startMinute!!)
                val end = minOf(o.endMinute!!, s.endMinute!!)
                if (start < end) HoursInterval.ofMinutes(o.weekday, start, end) else null
            }
        }.sortedWith(compareBy({ it.weekday }, { it.startMinute }))

    fun dayName(weekday: Int): String =
        DayOfWeek.of(weekday).getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR"))

    /** Mon–Sat 09:00–19:00: the wizard's prefilled week. */
    val DEFAULT: List<HoursInterval> = (1..6).map { HoursInterval(it, "09:00", "19:00") }
}

/** Public address of the salon (spec salon-profile "Unique public slug"). */
object Slugs {

    val RESERVED = setOf(
        "api", "app", "admin", "suporte", "support", "planos", "entrar", "cadastro", "login", "logout",
        "estilix", "www", "s", "static", "assets", "ajuda", "termos", "privacidade",
    )
    private val FORMAT = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    fun suggest(name: String): String {
        val base = withoutAccents(name).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).trim('-')
        return when {
            base.length < 3 -> "salao-$base".trim('-')
            base in RESERVED -> "$base-salao"
            else -> base
        }
    }

    /** Error message, or null when the slug is valid (uniqueness is checked by the database). */
    fun problem(slug: String): String? = when {
        slug.length !in 3..60 -> "use de 3 a 60 caracteres"
        !FORMAT.matches(slug) -> "use só letras minúsculas, números e hífens"
        slug in RESERVED -> "este endereço é reservado"
        else -> null
    }
}

enum class PriceType { FIXED, FROM }

/** Money rules (spec service-catalog): never floating point, never rounded silently. */
object Prices {

    fun toCents(price: BigDecimal): Long? {
        if (price.signum() < 0) return null
        if (price.stripTrailingZeros().scale() > 2) return null
        return price.movePointRight(2).longValueExact()
    }

    fun fromCents(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

    /** "R$ 45,00" or "a partir de R$ 150,00". */
    fun label(cents: Long, type: PriceType): String {
        val reais = cents / 100
        val formatted = "R$ " + "%,d".format(Locale.forLanguageTag("pt-BR"), reais) + "," + "%02d".format(cents % 100)
        return if (type == PriceType.FROM) "a partir de $formatted" else formatted
    }
}

fun withoutAccents(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** Key used to compare service names: "Corte Masculino" and "corte  masculino" collide. */
fun nameKey(name: String): String = withoutAccents(name).lowercase().trim().replace(Regex("\\s+"), " ")

/** Field rules of a service (spec service-catalog "Service definition"). */
object ServiceRules {

    fun validate(name: String?, category: String?, durationMinutes: Int?, price: BigDecimal?, priceType: PriceType?): Map<String, String> =
        buildMap {
            if (name.isNullOrBlank() || name.trim().length > 80) put("name", "informe um nome de até 80 caracteres")
            if (category.isNullOrBlank() || category.trim().length > 40) put("category", "informe uma categoria de até 40 caracteres")
            if (durationMinutes == null || durationMinutes !in 15..480 || durationMinutes % 15 != 0) {
                put("durationMinutes", "use de 15 a 480 minutos, de 15 em 15")
            }
            if (price == null || Prices.toCents(price) == null) put("price", "use um valor positivo com até 2 casas decimais")
            if (priceType == null) put("priceType", "informe se o preço é fixo ou a partir de")
        }
}
