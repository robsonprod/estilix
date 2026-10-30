package io.estilix.salon

import io.estilix.tenancy.EstablishmentType
import java.math.BigDecimal

data class ServicePreset(
    val name: String,
    val category: String,
    val durationMinutes: Int,
    val price: BigDecimal,
    val priceType: PriceType,
)

/**
 * Suggested services per establishment type (design D7): code, not data, so they are versioned and
 * tested. Prices are round, conservative suggestions; the owner edits them in the wizard.
 */
object Presets {

    private fun p(name: String, category: String, minutes: Int, price: String, type: PriceType = PriceType.FIXED) =
        ServicePreset(name, category, minutes, BigDecimal(price), type)

    private val SALON = listOf(
        p("Corte feminino", "Cabelo", 60, "80.00"),
        p("Escova", "Cabelo", 45, "50.00"),
        p("Coloração", "Cabelo", 120, "150.00", PriceType.FROM),
        p("Hidratação", "Tratamento", 45, "70.00"),
        p("Progressiva", "Tratamento", 180, "200.00", PriceType.FROM),
        p("Manicure", "Unhas", 45, "35.00"),
        p("Pedicure", "Unhas", 45, "40.00"),
        p("Design de sobrancelha", "Rosto", 30, "40.00"),
    )

    private val BARBERSHOP = listOf(
        p("Corte masculino", "Cabelo", 30, "45.00"),
        p("Barba", "Barba", 30, "35.00"),
        p("Corte + barba", "Combo", 60, "70.00"),
        p("Pezinho", "Cabelo", 15, "15.00"),
        p("Sobrancelha", "Rosto", 15, "15.00"),
        p("Pigmentação", "Barba", 30, "40.00", PriceType.FROM),
    )

    fun of(type: EstablishmentType): List<ServicePreset> = when (type) {
        EstablishmentType.SALON -> SALON
        EstablishmentType.BARBERSHOP -> BARBERSHOP
    }
}
