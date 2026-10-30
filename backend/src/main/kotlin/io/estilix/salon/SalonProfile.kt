package io.estilix.salon

import io.estilix.privacy.Phone
import io.estilix.shared.DomainException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.EstablishmentType
import io.estilix.tenancy.TenantStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class Address(
    val street: String?,
    val number: String?,
    val district: String?,
    val city: String?,
    val state: String?,
    val postalCode: String?,
)

data class SalonProfile(
    val id: UUID,
    val name: String,
    val establishmentType: EstablishmentType,
    val status: TenantStatus,
    /** Business phone: in clear for members, masked for support (may be a MEI's personal number). */
    val phone: Phone?,
    val address: Address,
    val timeZone: String,
    val slug: String,
)

data class SalonProfileUpdate(
    val name: String?,
    val phone: String?,
    val address: Address?,
    val timeZone: String?,
    val slug: String?,
)

/** Brazilian IANA time zones accepted for a salon. */
object TimeZones {
    val BRAZIL = listOf(
        "America/Sao_Paulo", "America/Bahia", "America/Fortaleza", "America/Recife", "America/Maceio",
        "America/Belem", "America/Santarem", "America/Araguaina", "America/Manaus", "America/Cuiaba",
        "America/Campo_Grande", "America/Porto_Velho", "America/Boa_Vista", "America/Rio_Branco",
        "America/Eirunepe", "America/Noronha",
    )
}

object ProfileRules {

    private val PHONE = Regex("^\\+?[0-9 ()-]{8,20}$")
    private val STATE = Regex("^[A-Z]{2}$")
    private val POSTAL = Regex("^\\d{5}-?\\d{3}$")

    fun validate(update: SalonProfileUpdate): Map<String, String> = buildMap {
        if (update.name.isNullOrBlank() || update.name.trim().length > 120) put("name", "informe o nome do salão (até 120 caracteres)")
        update.phone?.takeIf { it.isNotBlank() }?.let { if (!PHONE.matches(it.trim())) put("phone", "telefone inválido") }
        if (update.timeZone == null || update.timeZone !in TimeZones.BRAZIL) put("timeZone", "escolha um fuso horário do Brasil")
        if (update.slug == null) put("slug", "informe o endereço público") else Slugs.problem(update.slug)?.let { put("slug", it) }
        update.address?.state?.takeIf { it.isNotBlank() }?.let { if (!STATE.matches(it)) put("address.state", "use a sigla do estado, ex.: CE") }
        update.address?.postalCode?.takeIf { it.isNotBlank() }?.let { if (!POSTAL.matches(it)) put("address.postalCode", "CEP inválido") }
    }
}

/** Reads and writes the salon's own row and hours; RLS keeps every statement inside the current salon. */
@Repository
class SalonRepository(private val jdbc: JdbcClient) {

    fun profile(): SalonProfile? =
        jdbc.sql(
            """
            select id, name, establishment_type, status, phone, address_street, address_number, address_district,
                   address_city, address_state, address_postal_code, time_zone, slug
            from tenant where id = app_tenant_id()
            """.trimIndent(),
        ).query { rs, _ ->
            SalonProfile(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                establishmentType = EstablishmentType.valueOf(rs.getString("establishment_type")),
                status = TenantStatus.valueOf(rs.getString("status")),
                phone = rs.getString("phone")?.let(::Phone),
                address = Address(
                    rs.getString("address_street"), rs.getString("address_number"), rs.getString("address_district"),
                    rs.getString("address_city"), rs.getString("address_state"), rs.getString("address_postal_code"),
                ),
                timeZone = rs.getString("time_zone"),
                slug = rs.getString("slug"),
            )
        }.optional().orElse(null)

    fun updateProfile(update: SalonProfileUpdate) {
        val a = update.address ?: Address(null, null, null, null, null, null)
        try {
            jdbc.sql(
                """
                update tenant set name = :name, phone = :phone, time_zone = :tz, slug = :slug,
                       address_street = :street, address_number = :number, address_district = :district,
                       address_city = :city, address_state = :state, address_postal_code = :postal
                where id = app_tenant_id()
                """.trimIndent(),
            )
                .param("name", update.name!!.trim()).param("phone", update.phone?.trim()?.ifBlank { null })
                .param("tz", update.timeZone).param("slug", update.slug)
                .param("street", a.street?.trim()).param("number", a.number?.trim()).param("district", a.district?.trim())
                .param("city", a.city?.trim()).param("state", a.state?.trim()).param("postal", a.postalCode?.trim())
                .update()
        } catch (_: DuplicateKeyException) {
            throw DomainException(ProblemType.CONFLICT, "Este endereço público já está em uso", mapOf("slug" to "já está em uso"))
        }
    }

    fun hours(): List<HoursInterval> =
        jdbc.sql("select weekday, starts_minute, ends_minute from opening_hours order by weekday, starts_minute")
            .query { rs, _ -> HoursInterval.ofMinutes(rs.getInt(1), rs.getInt(2), rs.getInt(3)) }.list()

    fun replaceHours(intervals: List<HoursInterval>) {
        jdbc.sql("delete from opening_hours").update()
        intervals.forEach {
            jdbc.sql("insert into opening_hours (tenant_id, weekday, starts_minute, ends_minute) values (app_tenant_id(), :d, :s, :e)")
                .param("d", it.weekday).param("s", it.startMinute).param("e", it.endMinute).update()
        }
    }
}

@Service
class SalonService(private val salons: SalonRepository) {

    @Transactional(readOnly = true)
    fun profile(): SalonProfile = salons.profile()!!

    @Transactional
    fun updateProfile(update: SalonProfileUpdate): SalonProfile {
        val errors = ProfileRules.validate(update)
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira os dados do salão", errors)
        salons.updateProfile(update)
        return salons.profile()!!
    }

    @Transactional(readOnly = true)
    fun hours(): List<HoursInterval> = salons.hours()

    @Transactional
    fun replaceHours(intervals: List<HoursInterval>): List<HoursInterval> {
        val errors = WeeklyHours.validate(intervals)
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira os horários", errors)
        salons.replaceHours(intervals)
        return salons.hours()
    }
}
