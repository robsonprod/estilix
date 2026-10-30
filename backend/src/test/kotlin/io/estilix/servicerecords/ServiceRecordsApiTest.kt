package io.estilix.servicerecords

import io.estilix.IntegrationTest
import io.estilix.scheduling.BookingLifecycle
import io.estilix.scheduling.SchedulingSetup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.sql.Timestamp
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Tasks 4.1 and 4.2 — spec service-records/service-records and the booking lifecycle behind it. */
@ExtendWith(OutputCaptureExtension::class)
class ServiceRecordsApiTest : IntegrationTest() {

    private lateinit var s: SchedulingSetup
    private lateinit var v: Visits
    private lateinit var manager: String
    private lateinit var owner: String

    @BeforeEach
    fun data() {
        s = SchedulingSetup(admin, fixtures)
        v = Visits(api, admin, s) { token(it) }
        manager = s.manager()
        owner = s.owner()
    }

    private val records get() = "${v.base}/service-records"

    private fun entryStatus(id: UUID) = admin.sql("select status from agenda_entry where id = :id").param("id", id).query(String::class.java).single()

    private fun startToday(time: String = "15:00", professional: UUID = s.paula, service: UUID = s.corte, subject: String = manager): Pair<UUID, String> {
        val entry = v.book(manager, professional = professional, time = time, service = service)
        v.moveToToday(entry)
        val record = v.start(subject, entry)
        check(record.status == 201) { "start failed: ${record.status} ${record.body}" }
        return entry to record.node.path("id").asString()
    }

    // Items -----------------------------------------------------------------------------------------

    @Test
    fun `price is prefilled from the catalog and can be edited, negative is refused`() {
        val (_, record) = startToday()
        val item = api.get("$records/$record", token(manager)).node.path("items")[0]
        assertThat(item.path("price").asString()).isEqualTo("50.00")

        val edited = api.put("$records/$record/items/${item.path("id").asString()}", token(manager), mapOf("price" to "45.00")).node
        assertThat(edited.path("items")[0].path("price").asString()).isEqualTo("45.00")
        assertThat(edited.path("total").asString()).isEqualTo("45.00")

        val negative = api.put("$records/$record/items/${item.path("id").asString()}", token(manager), mapOf("price" to "-1.00"))
        assertThat(negative.node.path("errors").has("price")).isTrue()
    }

    @Test
    fun `a starting-at price is prefilled with its base and can be raised`() {
        admin.sql("update service set price_type = 'FROM', price_cents = 6000 where id = :id").param("id", s.escova).update()
        val (_, record) = startToday(service = s.escova)
        val item = api.get("$records/$record", token(manager)).node.path("items")[0]
        assertThat(item.path("price").asString()).isEqualTo("60.00")

        val raised = api.put("$records/$record/items/${item.path("id").asString()}", token(manager), mapOf("price" to "95.00")).node
        assertThat(raised.path("total").asString()).isEqualTo("95.00")
    }

    // States ----------------------------------------------------------------------------------------

    @Test
    fun `a closed record is read-only and the last item cannot be removed`() {
        val (_, record) = startToday()
        val item = api.get("$records/$record", token(manager)).node.path("items")[0].path("id").asString()

        val last = api.delete("$records/$record/items/$item", token(manager))
        assertThat(last.code).isEqualTo("validation")
        assertThat(last.node.path("detail").asString()).contains("anule")

        v.close(manager, record)
        assertThat(api.put("$records/$record/items/$item", token(manager), mapOf("price" to "10.00")).code).isEqualTo("validation")
    }

    @Test
    fun `another salon's record is not-found`() {
        val (_, record) = startToday()
        val other = SchedulingSetup(admin, fixtures)
        val otherOwner = other.owner()

        assertThat(api.get("/api/v1/tenants/${other.tenant}/service-records/$record", token(otherOwner)).status).isEqualTo(404)
    }

    // Start from a booking ------------------------------------------------------------------------------

    @Test
    fun `customer arrives - the booking is in service and the record has its item`() {
        val (entry, record) = startToday()

        assertThat(entryStatus(entry)).isEqualTo("IN_SERVICE")
        val view = api.get("$records/$record", token(manager)).node
        assertThat(view.path("status").asString()).isEqualTo("OPEN")
        assertThat(view.path("customerName").asString()).isEqualTo("Ana Souza")
        assertThat(view.path("items")[0].path("serviceName").asString()).isEqualTo("Corte")
        assertThat(view.path("items")[0].path("professionalName").asString()).isEqualTo("Paula")
        assertThat(view.path("items")[0].path("fromBooking").asBoolean()).isTrue()
        assertThat(api.get("$records/by-agenda-entry/$entry", token(manager)).node.path("id").asString()).isEqualTo(record)
    }

    @Test
    fun `chained services of the same code come in together`() {
        val booked = api.post(
            "/api/v1/public/salons/${s.slug}/bookings", null,
            mapOf("serviceId" to s.corte, "professionalId" to s.paula, "date" to s.dayAfterTomorrow(), "time" to "15:00", "name" to "Ana Souza", "phone" to "(11) 98888-7777"),
        ).node
        api.post("/api/v1/public/bookings/${booked.path("token").asString()}/next", null, mapOf("serviceId" to s.manicure))
        admin.sql("update agenda_entry set starts_at = starts_at - interval '2 days', ends_at = ends_at - interval '2 days'").update()
        val first = UUID.fromString(booked.path("bookings")[0].path("id").asString())

        val record = v.start(manager, first).node

        assertThat(record.path("items").toList().map { it.path("serviceName").asString() }).containsExactly("Corte", "Manicure")
        assertThat(record.path("total").asString()).isEqualTo("100.00")
        assertThat(admin.sql("select count(*) from agenda_entry where status = 'IN_SERVICE'").query(Long::class.java).single()).isEqualTo(2)
    }

    @Test
    fun `a booking on another day cannot be started`() {
        val entry = v.book(manager)

        assertThat(v.start(manager, entry).code).isEqualTo("validation")
        assertThat(entryStatus(entry)).isEqualTo("CONFIRMED")
    }

    @Test
    fun `starting a second booking of the same customer adds to the open record`() {
        val (_, record) = startToday(time = "10:00")
        val second = v.book(manager, professional = s.rosa, time = "11:00", service = s.manicure)
        v.moveToToday(second)

        val again = v.start(manager, second).node

        assertThat(again.path("id").asString()).isEqualTo(record)
        assertThat(again.path("items").size()).isEqualTo(2)
    }

    // Walk-in ---------------------------------------------------------------------------------------

    @Test
    fun `a walk-in takes the professional's time from now`() {
        val before = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val record = v.walkIn(manager)

        assertThat(record.status).isEqualTo(201)
        val entry = UUID.fromString(record.node.path("items")[0].path("agendaEntryId").asString())
        val (start, end) = admin.sql("select starts_at, ends_at from agenda_entry where id = :id and origin = 'WALK_IN' and status = 'IN_SERVICE'")
            .param("id", entry).query { rs, _ -> rs.getTimestamp(1).toInstant() to rs.getTimestamp(2).toInstant() }.single()
        assertThat(start).isBetween(before, Instant.now())
        assertThat(ChronoUnit.MINUTES.between(start, end)).isEqualTo(30)
        assertThat(record.node.path("items")[0].path("fromBooking").asBoolean()).isFalse()

        val today = ZonedDateTime.now(s.zone).toLocalDate()
        val walkIn = api.get("${v.base}/agenda?date=$today", token(manager)).node.path("professionals").toList()
            .first { it.path("displayName").asString() == "Paula" }.path("items").toList().single()
        assertThat(walkIn.path("origin").asString()).isEqualTo("WALK_IN")
        assertThat(walkIn.path("status").asString()).isEqualTo("IN_SERVICE")
    }

    @Test
    fun `a walk-in over a booking is refused naming the booking time, and nothing is left behind`() {
        val soon = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
        val entry = v.book(manager, time = "15:00")
        admin.sql("update agenda_entry set starts_at = :s, ends_at = cast(:s as timestamptz) + interval '30 minutes' where id = :id")
            .param("s", Timestamp.from(soon)).param("id", entry).update()

        val refused = v.walkIn(manager)

        assertThat(refused.code).isEqualTo("slot-taken")
        val local = soon.atZone(s.zone).toLocalTime().toString().take(5)
        assertThat(refused.node.path("detail").asString()).contains(local)
        assertThat(admin.sql("select count(*) from service_record").query(Long::class.java).single()).isZero()
        assertThat(admin.sql("select count(*) from customer where phone = '+5511977771111'").query(Long::class.java).single()).isZero()
    }

    @Test
    fun `a known phone fills the name and links the record to that customer`() {
        val known = v.customerOf(v.book(manager, phone = "(11) 97777-1111", name = "Bia Lima"))
        val lookup = api.get("${v.base}/customers/lookup?phone=11977771111", token(manager)).node
        assertThat(lookup.path("name").asString()).isEqualTo("Bia Lima")

        val record = v.walkIn(manager, service = s.escova, name = null).node

        assertThat(record.path("customerId").asString()).isEqualTo(known.toString())
        assertThat(record.path("customerName").asString()).isEqualTo("Bia Lima")
    }

    @Test
    fun `an extra service by the same professional starts when the previous one ends`() {
        val first = v.walkIn(manager).node
        val record = first.path("id").asString()

        val extra = api.post("$records/$record/items", token(manager), mapOf("serviceId" to s.escova, "professionalId" to s.paula)).node

        val entries = extra.path("items").toList().map { UUID.fromString(it.path("agendaEntryId").asString()) }
        val times = entries.map { id ->
            admin.sql("select starts_at, ends_at from agenda_entry where id = :id").param("id", id)
                .query { rs, _ -> rs.getTimestamp(1).toInstant() to rs.getTimestamp(2).toInstant() }.single()
        }
        assertThat(times[1].first).isEqualTo(times[0].second)
        assertThat(extra.path("total").asString()).isEqualTo("100.00")
    }

    // Close ----------------------------------------------------------------------------------------

    @Test
    fun `close in two taps - payment chosen, record closed, booking done`() {
        val (entry, record) = startToday()

        val closed = v.close(manager, record).node

        assertThat(closed.path("status").asString()).isEqualTo("CLOSED")
        assertThat(closed.path("paymentMethod").asString()).isEqualTo("PIX")
        assertThat(entryStatus(entry)).isEqualTo("DONE")
        assertThat(v.close(manager, record).code).isEqualTo("validation")
    }

    @Test
    fun `finishing early frees the rest of the time from the next quarter hour`() {
        val record = v.walkIn(manager, service = s.escova).node // 60 minutes from now
        val entry = UUID.fromString(record.path("items")[0].path("agendaEntryId").asString())
        val before = BookingLifecycle.ceilToQuarter(Instant.now())

        v.close(manager, record.path("id").asString())

        val after = BookingLifecycle.ceilToQuarter(Instant.now())
        val (start, end) = admin.sql("select starts_at, ends_at from agenda_entry where id = :id").param("id", entry)
            .query { rs, _ -> rs.getTimestamp(1).toInstant() to rs.getTimestamp(2).toInstant() }.single()
        assertThat(end).isBetween(maxOf(before, start.plus(15, ChronoUnit.MINUTES)), maxOf(after, start.plus(15, ChronoUnit.MINUTES)))
        assertThat(end).isBefore(start.plus(60, ChronoUnit.MINUTES))
    }

    @Test
    fun `closing without a payment method is refused`() {
        val (_, record) = startToday()

        val response = api.post("$records/$record/close", token(manager), mapOf<String, Any>())

        assertThat(response.node.path("errors").has("paymentMethod")).isTrue()
    }

    // Void -----------------------------------------------------------------------------------------

    @Test
    fun `voiding an open record started by mistake puts the booking back to confirmed`() {
        val (entry, record) = startToday()

        val voided = api.post("$records/$record/void", token(manager), mapOf("reason" to "Iniciei a cliente errada")).node

        assertThat(voided.path("status").asString()).isEqualTo("VOIDED")
        assertThat(entryStatus(entry)).isEqualTo("CONFIRMED")
    }

    @Test
    fun `voiding an open walk-in frees its time, voiding a closed record keeps the agenda`() {
        val walkIn = v.walkIn(manager).node
        val walkInEntry = UUID.fromString(walkIn.path("items")[0].path("agendaEntryId").asString())
        api.post("$records/${walkIn.path("id").asString()}/void", token(manager), mapOf("reason" to "Cliente desistiu"))
        assertThat(entryStatus(walkInEntry)).isEqualTo("CANCELLED")

        val (entry, record) = startToday(professional = s.rosa, service = s.manicure)
        v.close(manager, record)
        api.post("$records/$record/void", token(manager), mapOf("reason" to "Lançado em duplicidade"))
        assertThat(entryStatus(entry)).isEqualTo("DONE")
    }

    @Test
    fun `a professional cannot void, and a void is audited with the amount but no reason or customer data`() {
        val (_, record) = startToday(subject = s.paulaSubject)
        v.close(s.paulaSubject, record)

        assertThat(api.post("$records/$record/void", token(s.paulaSubject), mapOf("reason" to "Quero anular")).status).isEqualTo(403)

        api.post("$records/$record/void", token(manager), mapOf("reason" to "Ana Souza pediu estorno"))
        val audit = admin.sql("select action || ' ' || details::text from audit_event where action = 'SERVICE_RECORD_VOIDED'").query(String::class.java).single()
        assertThat(audit).contains("5000").doesNotContain("Ana", "estorno", "98888")
    }

    // Who sees what -----------------------------------------------------------------------------------

    @Test
    fun `a professional sees only records with their items and cannot add a colleague's item`() {
        val (_, paulas) = startToday(time = "10:00")
        startToday(time = "11:00", professional = s.rosa, service = s.manicure)
        val today = ZonedDateTime.now(s.zone).toLocalDate()

        val mine = api.get("$records?date=$today", token(s.paulaSubject)).node.path("records").toList()
        assertThat(mine.map { it.path("id").asString() }).containsExactly(paulas)

        val forRosa = api.post("$records/$paulas/items", token(s.paulaSubject), mapOf("serviceId" to s.manicure, "professionalId" to s.rosa))
        assertThat(forRosa.status).isEqualTo(403)
    }

    @Test
    fun `day total counts closed records only`() {
        val (_, closed) = startToday(time = "09:00")
        api.put("$records/$closed/items/${api.get("$records/$closed", token(manager)).node.path("items")[0].path("id").asString()}", token(manager), mapOf("price" to "80.00"))
        v.close(manager, closed)
        val (_, voided) = startToday(time = "10:00", professional = s.rosa, service = s.manicure)
        api.post("$records/$voided/void", token(manager), mapOf("reason" to "Teste de anulação"))
        v.walkIn(manager, phone = "(11) 96666-5555", name = "Cliente Aberta")
        val today = ZonedDateTime.now(s.zone).toLocalDate()

        val day = api.get("$records?date=$today", token(manager)).node

        assertThat(day.path("records").size()).isEqualTo(3)
        assertThat(day.path("total").asString()).isEqualTo("80.00")
    }

    @Test
    fun `support sees records with the customer masked and amounts in clear`() {
        val (_, record) = startToday()

        val view = api.get("$records/$record", supportToken()).node

        assertThat(view.path("customerName").asString()).isEqualTo("A*** S****")
        assertThat(view.toString()).doesNotContain("98888-7777", "11988887777")
        assertThat(view.path("total").asString()).isEqualTo("50.00")
    }

    @Test
    fun `record operations log no customer data`(output: CapturedOutput) {
        v.walkIn(manager, phone = "(11) 95555-4444", name = "Clarice Lispector")
        v.walkIn(manager, phone = "(11) 95555-4444", name = "Clarice Lispector") // same professional, now busy

        assertThat(output.all).doesNotContain("Clarice", "95555")
    }

}
