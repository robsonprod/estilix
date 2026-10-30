package io.estilix.scheduling

import io.estilix.salon.HoursInterval
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Booking rules of a salon (spec availability "Booking rules per salon"). */
data class BookingRules(val daysAhead: Int = 30, val minNoticeMinutes: Int = 60, val cancelNoticeMinutes: Int = 120) {

    fun problems(): Map<String, String> = buildMap {
        if (daysAhead !in 1..90) put("daysAhead", "de 1 a 90 dias")
        if (minNoticeMinutes !in 0..1440) put("minNoticeMinutes", "de 0 a 1440 minutos")
        if (cancelNoticeMinutes !in 0..2880) put("cancelNoticeMinutes", "de 0 a 2880 minutos")
    }
}

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

/** Secrets of a booking group (design D6). */
object BookingSecrets {

    private val random = SecureRandom()

    /** Six digits, leading zeros kept ("048213"). */
    fun newCode(): String = "%06d".format(random.nextInt(1_000_000))

    /** 256-bit token for the manage link, base64url without padding. */
    fun newToken(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** A plain hash of 6 digits is brute-forced offline in milliseconds: keyed with a server-side pepper. */
    fun codeHash(pepper: String, tenantId: UUID, phone: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper.toByteArray(), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal("$tenantId|$phone|$code".toByteArray()))
    }

    fun tokenHash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    /** Constant-time comparison, so response time does not leak how many characters matched. */
    fun matches(expected: String, actual: String): Boolean = MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
}

/** A busy range of one professional (confirmed booking or block). */
data class Busy(val professionalId: UUID, val start: Instant, val end: Instant)

/** What availability needs to know about one professional on one weekday. */
data class Candidate(val professionalId: UUID, val effective: List<HoursInterval>, val bookingsThatDay: Int = 0)

data class DayTimes(val date: LocalDate, val free: List<String>, val unavailable: List<String>, val freeBy: Map<String, List<UUID>>)

/**
 * Free times of a day (spec scheduling/availability, design D5). Pure: everything comes in as data,
 * including "now", so it is unit-tested with fixed values.
 */
object Availability {

    const val GRID = 15

    fun day(
        date: LocalDate,
        zone: ZoneId,
        now: Instant,
        rules: BookingRules,
        durationMinutes: Int,
        candidates: List<Candidate>,
        busy: List<Busy>,
        ignoreNotice: Boolean = false,
    ): DayTimes {
        val earliest = if (ignoreNotice) now else now.plusSeconds(rules.minNoticeMinutes * 60L)
        val weekday = date.dayOfWeek.value
        val freeBy = sortedMapOf<Int, MutableList<UUID>>()
        val inHours = sortedSetOf<Int>()
        candidates.forEach { c ->
            c.effective.filter { it.weekday == weekday }.forEach { interval ->
                val from = interval.startMinute()
                val to = interval.endMinute()
                var minute = ceilToGrid(from)
                while (minute < to) {
                    inHours += minute
                    val end = minute + durationMinutes
                    if (end <= to) {
                        val start = date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant()
                        val finish = start.plusSeconds(durationMinutes * 60L)
                        val clashes = busy.any { it.professionalId == c.professionalId && it.start < finish && start < it.end }
                        if (!clashes && !start.isBefore(earliest)) freeBy.getOrPut(minute) { mutableListOf() } += c.professionalId
                    }
                    minute += GRID
                }
            }
        }
        val free = freeBy.keys.map(::format)
        val unavailable = inHours.filter { it !in freeBy.keys }.map(::format)
        return DayTimes(date, free, unavailable, freeBy.mapKeys { format(it.key) })
    }

    /** Whether [date] is inside today..today+daysAhead in the salon's zone. */
    fun inWindow(date: LocalDate, zone: ZoneId, now: Instant, rules: BookingRules): Boolean {
        val today = now.atZone(zone).toLocalDate()
        return !date.isBefore(today) && !date.isAfter(today.plusDays(rules.daysAhead.toLong()))
    }

    fun format(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    fun parse(time: String): Int? =
        Regex("^([01]\\d|2[0-3]):([0-5]\\d)$").matchEntire(time)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    private fun ceilToGrid(minute: Int) = ((minute + GRID - 1) / GRID) * GRID

    private fun HoursInterval.startMinute() = parse(start) ?: 0
    private fun HoursInterval.endMinute() = if (end == "24:00") 1440 else parse(end) ?: 0
}
