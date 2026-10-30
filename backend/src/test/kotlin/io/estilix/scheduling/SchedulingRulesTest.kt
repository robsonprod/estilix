package io.estilix.scheduling

import io.estilix.privacy.Phones
import io.estilix.salon.HoursInterval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Tasks 2.1–2.3: pure scheduling rules with fixed clocks. */
class SchedulingRulesTest {

    private val zone = ZoneId.of("America/Fortaleza")
    private val tuesday = LocalDate.of(2026, 11, 3) // weekday 2
    private val paula = UUID.randomUUID()
    private val rosa = UUID.randomUUID()

    private fun at(date: LocalDate, time: String) = ZonedDateTime.of(date, java.time.LocalTime.parse(time), zone).toInstant()
    private fun hours(vararg pairs: Pair<String, String>) = pairs.map { HoursInterval(2, it.first, it.second) }
    private val earlyMorning = at(tuesday, "06:00")

    @Nested
    inner class Rules {
        @Test
        fun `defaults and ranges`() {
            assertThat(BookingRules()).isEqualTo(BookingRules(30, 60, 120))
            assertThat(BookingRules().problems()).isEmpty()
            assertThat(BookingRules(daysAhead = 120).problems()).containsKey("daysAhead")
            assertThat(BookingRules(minNoticeMinutes = -1, cancelNoticeMinutes = 3000).problems()).containsKeys("minNoticeMinutes", "cancelNoticeMinutes")
        }
    }

    @Nested
    inner class Phone {
        @Test
        fun `any formatting becomes E164`() {
            assertThat(Phones.normalize("(85) 99876-5432")).isEqualTo("+5585998765432")
            assertThat(Phones.normalize("+55 85 99876-5432")).isEqualTo("+5585998765432")
            assertThat(Phones.normalize("85 3333-4444")).isEqualTo("+558533334444")
        }

        @Test
        fun `too short or without DDD is rejected`() {
            assertThat(Phones.normalize("99876-5432")).isNull()
            assertThat(Phones.normalize("")).isNull()
            assertThat(Phones.normalize("085998765432")).isNull()
        }
    }

    @Nested
    inner class Secrets {
        @Test
        fun `codes have six digits and tokens are long`() {
            repeat(50) { assertThat(BookingSecrets.newCode()).matches("\\d{6}") }
            assertThat(BookingSecrets.newToken().length).isGreaterThanOrEqualTo(43)
        }

        @Test
        fun `code hash depends on pepper, salon and phone`() {
            val t = UUID.randomUUID()
            val h = BookingSecrets.codeHash("pepper", t, "+5585999990000", "123456")
            assertThat(BookingSecrets.matches(h, BookingSecrets.codeHash("pepper", t, "+5585999990000", "123456"))).isTrue()
            assertThat(h).isNotEqualTo(BookingSecrets.codeHash("other", t, "+5585999990000", "123456"))
            assertThat(h).isNotEqualTo(BookingSecrets.codeHash("pepper", t, "+5585999991111", "123456"))
            assertThat(h).doesNotContain("123456")
        }

        @Test
        fun `token hash is stable and not the token`() {
            val token = BookingSecrets.newToken()
            assertThat(BookingSecrets.tokenHash(token)).isEqualTo(BookingSecrets.tokenHash(token)).isNotEqualTo(token).hasSize(64)
        }
    }

    @Nested
    inner class Times {
        private fun day(candidates: List<Candidate>, busy: List<Busy> = emptyList(), duration: Int = 30, now: java.time.Instant = earlyMorning, rules: BookingRules = BookingRules()) =
            Availability.day(tuesday, zone, now, rules, duration, candidates, busy)

        @Test
        fun `a 60-minute service fits from 09-00 to 11-00 in 09-00 to 12-00`() {
            val result = day(listOf(Candidate(paula, hours("09:00" to "12:00"))), duration = 60)
            assertThat(result.free).containsExactly("09:00", "09:15", "09:30", "09:45", "10:00", "10:15", "10:30", "10:45", "11:00")
        }

        @Test
        fun `a booking makes its time unavailable and frees right after`() {
            val result = day(listOf(Candidate(paula, hours("09:00" to "12:00"))), listOf(Busy(paula, at(tuesday, "10:00"), at(tuesday, "10:30"))))
            assertThat(result.free).doesNotContain("09:45", "10:00", "10:15").contains("09:30", "10:30")
            assertThat(result.unavailable).contains("09:45", "10:00", "10:15")
        }

        @Test
        fun `a block works like a booking`() {
            val result = day(listOf(Candidate(paula, hours("13:00" to "16:00"))), listOf(Busy(paula, at(tuesday, "14:00"), at(tuesday, "15:00"))))
            assertThat(result.free).doesNotContain("14:00", "14:30").contains("13:30", "15:00")
        }

        @Test
        fun `a service never crosses the lunch break`() {
            val result = day(listOf(Candidate(paula, hours("09:00" to "12:00", "14:00" to "18:00"))), duration = 60)
            assertThat(result.free).doesNotContain("11:30", "11:15").contains("11:00", "14:00")
        }

        @Test
        fun `the minimum notice hides times too soon`() {
            val result = day(listOf(Candidate(paula, hours("09:00" to "18:00"))), now = at(tuesday, "09:20"))
            assertThat(result.free.first()).isEqualTo("10:30")
        }

        @Test
        fun `any professional means free when at least one is free`() {
            val result = day(
                listOf(Candidate(paula, hours("09:00" to "12:00")), Candidate(rosa, hours("09:00" to "12:00"))),
                listOf(Busy(paula, at(tuesday, "10:00"), at(tuesday, "10:30"))),
            )
            assertThat(result.free).contains("10:00")
            assertThat(result.freeBy["10:00"]).containsExactly(rosa)
        }

        @Test
        fun `another weekday's hours give nothing`() {
            val result = day(listOf(Candidate(paula, listOf(HoursInterval(3, "09:00", "12:00")))))
            assertThat(result.free).isEmpty()
            assertThat(result.unavailable).isEmpty()
        }

        @Test
        fun `no candidates means no times`() {
            assertThat(day(emptyList()).free).isEmpty()
        }

        @Test
        fun `booking window`() {
            val now = at(tuesday, "08:00")
            assertThat(Availability.inWindow(tuesday.plusDays(30), zone, now, BookingRules())).isTrue()
            assertThat(Availability.inWindow(tuesday.plusDays(31), zone, now, BookingRules())).isFalse()
            assertThat(Availability.inWindow(tuesday.minusDays(1), zone, now, BookingRules())).isFalse()
        }
    }

    @Test
    fun `closing early rounds the freed time up to the next quarter hour`() {
        fun at(t: String) = java.time.Instant.parse("2026-11-03T${t}Z")
        assertThat(BookingLifecycle.ceilToQuarter(at("18:35:00"))).isEqualTo(at("18:45:00"))
        assertThat(BookingLifecycle.ceilToQuarter(at("18:45:00"))).isEqualTo(at("18:45:00"))
        assertThat(BookingLifecycle.ceilToQuarter(at("18:45:00.001"))).isEqualTo(at("19:00:00"))
        assertThat(BookingLifecycle.ceilToQuarter(at("23:59:59"))).isEqualTo(java.time.Instant.parse("2026-11-04T00:00:00Z"))
    }
}
