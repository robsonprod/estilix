package io.estilix.customers

import io.estilix.privacy.Phones
import io.estilix.salon.nameKey
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the customer list search means for one typed term (spec "Customer list and search"). */
sealed interface CustomerSearch {
    data class PhoneDigits(val digits: String) : CustomerSearch
    data class NameWord(val prefix: String) : CustomerSearch
}

/** Pure rules of spec customers/customer-records. */
object CustomerRules {

    const val NAME_MIN = 2
    const val NAME_MAX = 80
    const val NOTES_MAX = 1000
    const val ANONYMISED_NAME = "Cliente anonimizado"

    /**
     * "Costuma voltar a cada N dias": median of the gaps between distinct visit days, shown only
     * with at least 3 visit days. With an even number of gaps, the mean of the two middle ones,
     * rounded down (design D8).
     */
    fun usualIntervalDays(visitDays: List<LocalDate>): Int? {
        val days = visitDays.distinct().sorted()
        if (days.size < 3) return null
        val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.sorted()
        val mid = gaps.size / 2
        return if (gaps.size % 2 == 1) gaps[mid].toInt() else ((gaps[mid - 1] + gaps[mid]) / 2).toInt()
    }

    /** Digits (4+) search the phone; otherwise 2+ letters search the start of a name word. */
    fun search(term: String?): CustomerSearch? {
        val trimmed = term?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.none(Char::isLetter)) {
            val digits = trimmed.filter(Char::isDigit)
            return if (digits.length >= 4) CustomerSearch.PhoneDigits(digits) else null
        }
        val prefix = nameKey(trimmed).filter { it.isLetterOrDigit() || it == ' ' }
        return if (prefix.length >= 2) CustomerSearch.NameWord(prefix) else null
    }

    fun validate(name: String?, phone: String?, notes: String?): Map<String, String> = buildMap {
        if (name?.trim()?.length !in NAME_MIN..NAME_MAX) put("name", "informe o nome ($NAME_MIN a $NAME_MAX letras)")
        if (Phones.normalize(phone) == null) put("phone", "telefone inválido: use DDD + número")
        if (notes != null && notes.length > NOTES_MAX) put("notes", "até $NOTES_MAX caracteres")
    }
}
