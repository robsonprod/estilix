package io.estilix.scheduling

import io.estilix.Fixtures
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * A salon open every day 00:00–24:00 (so tests can book near "now" without mocking the clock),
 * time zone America/Sao_Paulo, two services and two professionals.
 */
class SchedulingSetup(private val admin: JdbcClient, private val fixtures: Fixtures, val slug: String = "salao-teste-${UUID.randomUUID().toString().take(6)}") {

    val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    val tenant: UUID = fixtures.tenant("Salão Teste")
    val corte: UUID = service("Corte", 30)
    val escova: UUID = service("Escova", 60)
    val manicure: UUID = service("Manicure", 45)
    val paulaUser: UUID = fixtures.memberOf(tenant, "pro-paula-$slug", "PROFESSIONAL", "Paula Rocha")
    val paula: UUID = professional("Paula", paulaUser)
    val rosa: UUID = professional("Rosa", null)

    init {
        admin.sql("update tenant set slug = :s, time_zone = 'America/Sao_Paulo', phone = '+55 11 3333-0000' where id = :t")
            .param("s", slug).param("t", tenant).update()
        (1..7).forEach {
            admin.sql("insert into opening_hours (tenant_id, weekday, starts_minute, ends_minute) values (:t, :d, 0, 1440)")
                .param("t", tenant).param("d", it).update()
        }
        link(paula, corte, escova)
        link(rosa, corte, manicure)
    }

    fun owner(subject: String = "owner-$slug"): String = subject.also { fixtures.memberOf(tenant, it, "OWNER") }
    fun manager(subject: String = "manager-$slug"): String = subject.also { fixtures.memberOf(tenant, it, "MANAGER") }
    val paulaSubject get() = "pro-paula-$slug"

    /** A day comfortably in the future and inside the default 30-day window. */
    fun dayAfterTomorrow(): LocalDate = ZonedDateTime.now(zone).toLocalDate().plusDays(2)

    /**
     * Tomorrow 00:00 is always less than 24 hours away, whatever the time of day the tests run, so it
     * is "inside" any notice of 1440 minutes or more. (A "now + N minutes" slot is flaky: near midnight
     * it would cross into the next day, which a booking may not do.)
     */
    fun tomorrowMidnight(): Pair<LocalDate, String> = ZonedDateTime.now(zone).toLocalDate().plusDays(1) to "00:00"

    fun rules(minNotice: Int = 60, cancelNotice: Int = 120) {
        admin.sql("update tenant set booking_min_notice_minutes = :m, booking_cancel_notice_minutes = :c where id = :t")
            .param("m", minNotice).param("c", cancelNotice).param("t", tenant).update()
    }

    private fun service(name: String, minutes: Int): UUID = UUID.randomUUID().also {
        admin.sql(
            """
            insert into service (id, tenant_id, name, name_key, category, duration_minutes, price_cents, price_type)
            values (:id, :t, :n, :k, 'Cabelo', :m, 5000, 'FIXED')
            """.trimIndent(),
        ).param("id", it).param("t", tenant).param("n", name).param("k", name.lowercase()).param("m", minutes).update()
    }

    private fun professional(name: String, user: UUID?): UUID = UUID.randomUUID().also {
        admin.sql("insert into professional (id, tenant_id, display_name, user_id, color) values (:id, :t, :n, :u, '#8b2f5c')")
            .param("id", it).param("t", tenant).param("n", name).param("u", user).update()
    }

    private fun link(professional: UUID, vararg services: UUID) = services.forEach {
        admin.sql("insert into professional_service (tenant_id, professional_id, service_id) values (:t, :p, :s)")
            .param("t", tenant).param("p", professional).param("s", it).update()
    }
}
