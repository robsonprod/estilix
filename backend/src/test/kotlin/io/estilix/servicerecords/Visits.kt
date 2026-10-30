package io.estilix.servicerecords

import io.estilix.Api
import io.estilix.scheduling.SchedulingSetup
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * Helpers shared by the customers and service-records API tests, on top of [SchedulingSetup].
 * Bookings are made through the real staff API (so customers are linked for real), two days ahead,
 * and then moved to today at the same clock time when a test needs "today".
 */
class Visits(private val api: Api, private val admin: JdbcClient, val s: SchedulingSetup, private val token: (String) -> String) {

    val base get() = "/api/v1/tenants/${s.tenant}"

    fun book(
        subject: String,
        professional: UUID = s.paula,
        time: String = "15:00",
        service: UUID = s.corte,
        name: String = "Ana Souza",
        phone: String = "(11) 98888-7777",
    ): UUID {
        val response = api.post(
            "$base/agenda/bookings", token(subject),
            mapOf("serviceId" to service, "professionalId" to professional, "date" to s.dayAfterTomorrow(), "time" to time, "name" to name, "phone" to phone),
        )
        check(response.status == 201) { "booking failed: ${response.status} ${response.body}" }
        return UUID.fromString(response.node.path("item").path("id").asString())
    }

    /** Same clock time, today (salon time zone has no DST, so 48 hours earlier is today). */
    fun moveToToday(vararg entries: UUID) = entries.forEach {
        admin.sql("update agenda_entry set starts_at = starts_at - interval '2 days', ends_at = ends_at - interval '2 days' where id = :id")
            .param("id", it).update()
    }

    fun start(subject: String, entry: UUID) = api.post("$base/service-records/from-booking/$entry", token(subject))

    fun walkIn(subject: String, professional: UUID = s.paula, service: UUID = s.corte, phone: String = "(11) 97777-1111", name: String? = "Bia Lima") =
        api.post("$base/service-records/walk-in", token(subject), mapOf("phone" to phone, "name" to name, "serviceId" to service, "professionalId" to professional))

    fun close(subject: String, record: String, method: String = "PIX") = api.post("$base/service-records/$record/close", token(subject), mapOf("paymentMethod" to method))

    /** A closed visit for [phone] on a given day offset (negative = past), written straight to the database. */
    fun pastVisit(customer: UUID, daysAgo: Long, cents: Long = 5000, professional: UUID = s.paula) {
        val record = UUID.randomUUID()
        admin.sql(
            """
            insert into service_record (id, tenant_id, customer_id, status, payment_method, total_cents, opened_at, closed_at)
            values (:id, :t, :c, 'CLOSED', 'PIX', :cents, now() - make_interval(days => :d), now() - make_interval(days => :d))
            """.trimIndent(),
        ).param("id", record).param("t", s.tenant).param("c", customer).param("cents", cents).param("d", daysAgo.toInt()).update()
        admin.sql(
            "insert into service_record_item (id, tenant_id, record_id, service_id, professional_id, price_cents) values (:id, :t, :r, :s, :p, :c)",
        ).param("id", UUID.randomUUID()).param("t", s.tenant).param("r", record).param("s", s.corte).param("p", professional).param("c", cents).update()
    }

    fun customerOf(entry: UUID): UUID =
        admin.sql("select customer_id from agenda_entry where id = :id").param("id", entry).query(UUID::class.java).single()
}
