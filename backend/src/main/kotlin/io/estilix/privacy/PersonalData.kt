package io.estilix.privacy

/**
 * Personal data is declared by its TYPE (design D8): a field typed as one of these classes is
 * masked for platform support and redacted from `toString()`, so string templates, data-class
 * `toString()` and logs never print the value.
 *
 * Plain final classes instead of Kotlin value classes: value-class properties compile to their
 * underlying `String` on the JVM, and Jackson would then skip the masking serializer.
 */
sealed class PersonalData(val value: String) {

    /** Shape kept for diagnosis while hiding the identity (spec "Masking format"). */
    abstract fun masked(): String

    final override fun toString(): String = REDACTED

    final override fun equals(other: Any?): Boolean =
        other != null && other.javaClass == javaClass && (other as PersonalData).value == value

    final override fun hashCode(): Int = 31 * javaClass.hashCode() + value.hashCode()

    companion object {
        const val REDACTED = "***"
    }
}

class PersonName(value: String) : PersonalData(value) {
    override fun masked() = Masking.name(value)
}

class Email(value: String) : PersonalData(value) {
    override fun masked() = Masking.email(value)
}

class Phone(value: String) : PersonalData(value) {
    override fun masked() = Masking.phone(value)
}

/** Free text about a person (notes, reasons): fully hidden when masked. */
class PersonalText(value: String) : PersonalData(value) {
    override fun masked() = Masking.other(value)
}

object Masking {

    /** "Ana Paula Souza" -> "A*** P**** S****" (at least 3 stars, so short names hide their length). */
    fun name(value: String): String =
        value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.first() + "*".repeat(maxOf(3, word.length - 1)) }

    /** "ana.souza@gmail.com" -> "a***@gmail.com". */
    fun email(value: String): String {
        val at = value.lastIndexOf('@')
        if (at <= 0) return other(value)
        return value.first() + "***" + value.substring(at)
    }

    /** "+55 85 99876-5432" -> "***********32" (one star per hidden digit, last 2 digits shown). */
    fun phone(value: String): String {
        val digits = value.filter(Char::isDigit)
        if (digits.length <= 2) return other(value)
        return "*".repeat(digits.length - 2) + digits.takeLast(2)
    }

    fun other(@Suppress("UNUSED_PARAMETER") value: String): String = PersonalData.REDACTED
}
