package io.estilix.customers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Task 2.1 — pure rules of spec customers/customer-records. */
class CustomerRulesTest {

    private val jan1: LocalDate = LocalDate.of(2026, 1, 1)
    private fun day(n: Long) = jan1.plusDays(n - 1)

    @Nested
    inner class UsualInterval {

        @Test
        fun `median of the gaps, rounded down (spec example days 1, 31, 66)`() {
            assertThat(CustomerRules.usualIntervalDays(listOf(day(1), day(31), day(66)))).isEqualTo(32)
        }

        @Test
        fun `odd number of gaps takes the middle one`() {
            // gaps 10, 40, 20 -> sorted 10, 20, 40 -> 20
            assertThat(CustomerRules.usualIntervalDays(listOf(day(1), day(11), day(51), day(71)))).isEqualTo(20)
        }

        @Test
        fun `fewer than three visits has no usual interval`() {
            assertThat(CustomerRules.usualIntervalDays(listOf(day(1), day(31)))).isNull()
            assertThat(CustomerRules.usualIntervalDays(emptyList())).isNull()
        }

        @Test
        fun `two visits on the same day count once, order does not matter`() {
            assertThat(CustomerRules.usualIntervalDays(listOf(day(31), day(1), day(1)))).isNull()
            assertThat(CustomerRules.usualIntervalDays(listOf(day(61), day(31), day(1), day(1), day(91)))).isEqualTo(30)
        }
    }

    @Nested
    inner class Search {

        @Test
        fun `four or more digits search the phone`() {
            assertThat(CustomerRules.search("98888")).isEqualTo(CustomerSearch.PhoneDigits("98888"))
            assertThat(CustomerRules.search("(11) 9888")).isEqualTo(CustomerSearch.PhoneDigits("119888"))
        }

        @Test
        fun `letters search the start of a name word, without accents or case`() {
            assertThat(CustomerRules.search("  JOÃO ")).isEqualTo(CustomerSearch.NameWord("joao"))
        }

        @Test
        fun `too short or empty means no filter`() {
            assertThat(CustomerRules.search("j")).isNull()
            assertThat(CustomerRules.search("988")).isNull()
            assertThat(CustomerRules.search(null)).isNull()
            assertThat(CustomerRules.search("  ")).isNull()
        }
    }

    @Nested
    inner class Profile {

        @Test
        fun `name, phone and notes are validated with field errors`() {
            val errors = CustomerRules.validate(name = "A", phone = "123", notes = "x".repeat(1001))
            assertThat(errors.keys).containsExactlyInAnyOrder("name", "phone", "notes")
            assertThat(CustomerRules.validate(name = "Ana Souza", phone = "(11) 98888-7777", notes = null)).isEmpty()
        }
    }
}
