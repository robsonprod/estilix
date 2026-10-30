package io.estilix.customers

import io.estilix.IntegrationTest
import io.estilix.scheduling.SchedulingSetup
import io.estilix.servicerecords.Visits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Task 4.3 — spec customers/data-subject-rights. */
class DataSubjectRightsApiTest : IntegrationTest() {

    private lateinit var s: SchedulingSetup
    private lateinit var v: Visits
    private lateinit var owner: String
    private lateinit var manager: String

    @BeforeEach
    fun data() {
        s = SchedulingSetup(admin, fixtures)
        v = Visits(api, admin, s) { token(it) }
        owner = s.owner()
        manager = s.manager()
    }

    private fun customers() = "${v.base}/customers"

    /** Ana with a past booking (and its manage link), 3 closed records worth R$ 240,00, plus Bia as another customer. */
    private fun ana(): Pair<UUID, String> {
        val booked = api.post(
            "/api/v1/public/salons/${s.slug}/bookings", null,
            mapOf("serviceId" to s.corte, "professionalId" to s.paula, "date" to s.dayAfterTomorrow(), "time" to "15:00", "name" to "Ana Souza", "phone" to "(11) 98888-7777"),
        ).node
        val entry = UUID.fromString(booked.path("bookings")[0].path("id").asString())
        admin.sql("update agenda_entry set starts_at = starts_at - interval '9 days', ends_at = ends_at - interval '9 days', status = 'DONE' where id = :id")
            .param("id", entry).update()
        val id = v.customerOf(entry)
        listOf(8000L, 8000L, 8000L).forEachIndexed { i, cents -> v.pastVisit(id, daysAgo = 10L + i * 30, cents = cents) }
        api.put("${customers()}/$id", token(manager), mapOf("name" to "Ana Souza", "phone" to "(11) 98888-7777", "notes" to "Alergia a amônia"))
        v.book(manager, name = "Bia Lima", phone = "(11) 97777-1111", time = "16:00")
        return id to booked.path("token").asString()
    }

    @Test
    fun `the owner exports one customer's data and nothing about others`() {
        val (id, _) = ana()

        val export = api.get("${customers()}/$id/export", token(owner))

        assertThat(export.status).isEqualTo(200)
        assertThat(export.header("Content-Disposition")).contains("cliente-$id.json")
        assertThat(export.header("Cache-Control")).contains("no-store")
        val body = export.node
        assertThat(body.path("customer").path("name").asString()).isEqualTo("Ana Souza")
        assertThat(body.path("customer").path("notes").asString()).isEqualTo("Alergia a amônia")
        assertThat(body.path("bookings").size()).isEqualTo(1)
        assertThat(body.path("serviceRecords").size()).isEqualTo(3)
        assertThat(body.toString()).doesNotContain("Bia", "97777")
        assertThat(fixtures.auditActions(s.tenant)).contains("CUSTOMER_DATA_EXPORTED")
    }

    @Test
    fun `managers cannot export and another salon gets not-found`() {
        val (id, _) = ana()
        val other = SchedulingSetup(admin, fixtures)

        assertThat(api.get("${customers()}/$id/export", token(manager)).status).isEqualTo(403)
        assertThat(api.get("/api/v1/tenants/${other.tenant}/customers/$id/export", token(other.owner())).status).isEqualTo(404)
    }

    @Test
    fun `anonymisation keeps the amounts, erases the identity and kills the old link`() {
        val (id, link) = ana()

        assertThat(api.post("${customers()}/$id/anonymise", token(owner), mapOf("confirmation" to "sim")).code).isEqualTo("validation")
        assertThat(api.post("${customers()}/$id/anonymise", token(owner), mapOf("confirmation" to "ANONIMIZAR")).status).isEqualTo(200)

        val detail = api.get("${customers()}/$id", token(owner)).node
        assertThat(detail.path("name").asString()).isEqualTo("Cliente anonimizado")
        assertThat(detail.path("phone").isNull).isTrue()
        assertThat(detail.path("notes").isNull).isTrue()
        assertThat(detail.path("totalSpent").asString()).isEqualTo("240.00")
        val leftovers = admin.sql(
            "select count(*) from agenda_entry where customer_id = :c and (customer_name <> 'Cliente anonimizado' or customer_phone is not null)",
        ).param("c", id).query(Long::class.java).single()
        assertThat(leftovers).isZero()
        assertThat(admin.sql("select count(*) from booking_group where phone = '+5511988887777'").query(Long::class.java).single()).isZero()
        assertThat(api.get("/api/v1/public/bookings/$link").status).isEqualTo(404)
    }

    @Test
    fun `after anonymisation the phone is free and books a brand-new customer`() {
        val (id, _) = ana()
        api.post("${customers()}/$id/anonymise", token(owner), mapOf("confirmation" to "ANONIMIZAR"))

        val again = v.customerOf(v.book(manager, time = "17:00"))

        assertThat(again).isNotEqualTo(id)
        assertThat(api.get("${customers()}/$again", token(owner)).node.path("visits").asInt()).isZero()
    }

    @Test
    fun `an open record or a future booking blocks anonymisation`() {
        val (id, _) = ana()
        v.book(manager, time = "18:00") // Ana again, in two days

        val blocked = api.post("${customers()}/$id/anonymise", token(owner), mapOf("confirmation" to "ANONIMIZAR"))

        assertThat(blocked.code).isEqualTo("validation")
        assertThat(blocked.node.path("detail").asString()).contains("agendamentos futuros")
        assertThat(api.get("${customers()}/$id", token(owner)).node.path("name").asString()).isEqualTo("Ana Souza")

        admin.sql("update agenda_entry set status = 'CANCELLED' where customer_id = :c and status = 'CONFIRMED'").param("c", id).update()
        v.walkIn(manager, phone = "(11) 98888-7777", name = null)
        assertThat(api.post("${customers()}/$id/anonymise", token(owner), mapOf("confirmation" to "ANONIMIZAR")).node.path("detail").asString())
            .contains("comanda aberta")
    }

    @Test
    fun `both actions are audited by customer id, without personal data`() {
        val (id, _) = ana()
        api.get("${customers()}/$id/export", token(owner))
        api.post("${customers()}/$id/anonymise", token(owner), mapOf("confirmation" to "ANONIMIZAR"))

        val events = admin.sql("select action || ' ' || target_id || ' ' || details::text from audit_event where tenant_id = :t and action like 'CUSTOMER_%' order by occurred_at")
            .param("t", s.tenant).query(String::class.java).list().filterNotNull()
        assertThat(events.map { it.substringBefore(' ') }).containsExactly("CUSTOMER_DATA_EXPORTED", "CUSTOMER_ANONYMISED")
        assertThat(events).allMatch { it.contains(id.toString()) }
        assertThat(events.joinToString()).doesNotContain("Ana", "98888", "amônia")
    }
}
