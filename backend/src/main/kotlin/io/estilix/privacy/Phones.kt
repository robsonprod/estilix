package io.estilix.privacy

/** Brazilian phones in E.164 (+55 DDD number); the customer may type any formatting. */
object Phones {

    /** "(85) 99876-5432" -> "+5585998765432"; null when it is not a Brazilian number with DDD. */
    fun normalize(raw: String?): String? {
        val digits = raw?.filter(Char::isDigit) ?: return null
        val national = when {
            digits.length in 12..13 && digits.startsWith("55") -> digits.drop(2)
            digits.length in 10..11 -> digits
            else -> return null
        }
        if (national.startsWith("0") || national[0] == '0') return null
        return "+55$national"
    }
}
