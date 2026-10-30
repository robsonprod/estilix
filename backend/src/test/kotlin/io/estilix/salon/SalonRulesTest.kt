package io.estilix.salon

import io.estilix.tenancy.EstablishmentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Task 2.1 and 3.1: pure rules of the salon module, no Spring, no database. */
class SalonRulesTest {

    @Nested
    inner class Hours {
        private fun i(day: Int, start: String, end: String) = HoursInterval(day, start, end)

        @Test
        fun `split shift is valid`() {
            assertThat(WeeklyHours.validate(listOf(i(6, "08:00", "12:00"), i(6, "14:00", "18:00")))).isEmpty()
        }

        @Test
        fun `back-to-back intervals do not overlap`() {
            assertThat(WeeklyHours.validate(listOf(i(1, "09:00", "12:00"), i(1, "12:00", "18:00")))).isEmpty()
        }

        @Test
        fun `overlapping intervals on the same day are rejected`() {
            val errors = WeeklyHours.validate(listOf(i(1, "09:00", "13:00"), i(1, "12:00", "18:00")))
            assertThat(errors).containsEntry("intervals[1]", "sobrepõe outro horário de segunda-feira")
        }

        @Test
        fun `same times on different days are fine`() {
            assertThat(WeeklyHours.validate(listOf(i(1, "09:00", "18:00"), i(2, "09:00", "18:00")))).isEmpty()
        }

        @Test
        fun `off the 15-minute grid is rejected`() {
            assertThat(WeeklyHours.validate(listOf(i(1, "09:10", "12:00")))).containsValue("use horários de 15 em 15 minutos")
        }

        @Test
        fun `start must be before end and format must be HH-mm`() {
            assertThat(WeeklyHours.validate(listOf(i(1, "18:00", "09:00")))).containsValue("o início precisa ser antes do fim")
            assertThat(WeeklyHours.validate(listOf(i(1, "9h", "12:00")))).containsValue("use o formato HH:mm")
            assertThat(WeeklyHours.validate(listOf(i(8, "09:00", "12:00")))).containsValue("dia da semana inválido")
        }

        @Test
        fun `closing at midnight is allowed`() {
            assertThat(WeeklyHours.validate(listOf(i(5, "18:00", "24:00")))).isEmpty()
        }

        @Test
        fun `an empty week means closed every day`() {
            assertThat(WeeklyHours.validate(emptyList())).isEmpty()
        }

        @Test
        fun `intersection clips own hours to the salon's`() {
            val salon = listOf(i(2, "09:00", "12:00"), i(2, "14:00", "19:00"))
            val own = listOf(i(2, "11:00", "16:00"), i(7, "09:00", "12:00"))

            assertThat(WeeklyHours.intersect(own, salon)).containsExactly(i(2, "11:00", "12:00"), i(2, "14:00", "16:00"))
        }
    }

    @Nested
    inner class Slug {
        @Test
        fun `suggested from the name without accents`() {
            assertThat(Slugs.suggest("Salão Bela Vista")).isEqualTo("salao-bela-vista")
            assertThat(Slugs.suggest("  Barbearia do Zé!! ")).isEqualTo("barbearia-do-ze")
        }

        @Test
        fun `short or reserved names still give a valid suggestion`() {
            assertThat(Slugs.problem(Slugs.suggest("Zé"))).isNull()
            assertThat(Slugs.problem(Slugs.suggest("Admin"))).isNull()
        }

        @Test
        fun `format and reserved words are enforced`() {
            assertThat(Slugs.problem("salao-bela")).isNull()
            assertThat(Slugs.problem("ab")).isNotNull()
            assertThat(Slugs.problem("Salao")).isNotNull()
            assertThat(Slugs.problem("-salao")).isNotNull()
            assertThat(Slugs.problem("salao--bela")).isNotNull()
            assertThat(Slugs.problem("admin")).isEqualTo("este endereço é reservado")
        }
    }

    @Nested
    inner class Money {
        @Test
        fun `two decimals are converted exactly to cents`() {
            assertThat(Prices.toCents(BigDecimal("45.00"))).isEqualTo(4500)
            assertThat(Prices.toCents(BigDecimal("45.5"))).isEqualTo(4550)
            assertThat(Prices.toCents(BigDecimal("0"))).isZero()
        }

        @Test
        fun `more than two decimals or negative values are rejected, never rounded`() {
            assertThat(Prices.toCents(BigDecimal("45.999"))).isNull()
            assertThat(Prices.toCents(BigDecimal("-1"))).isNull()
        }

        @Test
        fun `labels in pt-BR`() {
            assertThat(Prices.label(4500, PriceType.FIXED)).isEqualTo("R$ 45,00")
            assertThat(Prices.label(15000, PriceType.FROM)).isEqualTo("a partir de R$ 150,00")
            assertThat(Prices.label(123456, PriceType.FIXED)).isEqualTo("R$ 1.234,56")
        }
    }

    @Nested
    inner class Services {
        @Test
        fun `names collide ignoring case, accents and spaces`() {
            assertThat(nameKey("Corte  Masculino")).isEqualTo(nameKey("corte masculino"))
            assertThat(nameKey("Coloração")).isEqualTo("coloracao")
        }

        @Test
        fun `field rules`() {
            assertThat(ServiceRules.validate("Corte", "Cabelo", 30, BigDecimal("45.00"), PriceType.FIXED)).isEmpty()
            assertThat(ServiceRules.validate("Corte", "Cabelo", 40, BigDecimal("45.00"), PriceType.FIXED)).containsKey("durationMinutes")
            assertThat(ServiceRules.validate("Corte", "Cabelo", 495, BigDecimal("45.00"), PriceType.FIXED)).containsKey("durationMinutes")
            assertThat(ServiceRules.validate("", "Cabelo", 30, BigDecimal("45.00"), PriceType.FIXED)).containsKey("name")
            assertThat(ServiceRules.validate("Corte", "Cabelo", 30, BigDecimal("45.999"), null)).containsKeys("price", "priceType")
        }

        @Test
        fun `presets follow the service rules and fit each establishment type`() {
            EstablishmentType.entries.forEach { type ->
                Presets.of(type).forEach {
                    assertThat(ServiceRules.validate(it.name, it.category, it.durationMinutes, it.price, it.priceType)).`as`(it.name).isEmpty()
                }
            }
            assertThat(Presets.of(EstablishmentType.BARBERSHOP).map { it.name }).contains("Corte masculino", "Barba", "Corte + barba")
            assertThat(Presets.of(EstablishmentType.SALON).filter { it.priceType == PriceType.FROM }.map { it.name }).contains("Coloração")
        }
    }
}
