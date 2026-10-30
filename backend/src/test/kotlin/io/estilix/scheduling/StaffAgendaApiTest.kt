package io.estilix.scheduling

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.util.UUID

/** Spec scheduling/staff-agenda and the booking-rules requirement of scheduling/availability. */
@ExtendWith(OutputCaptureExtension::class)
class StaffAgendaApiTest : IntegrationTest() {

    private lateinit var s: SchedulingSetup
    private lateinit var owner: String
    private lateinit var manager: String

    @BeforeEach
    fun data() {
        s = SchedulingSetup(admin, fixtures)
        owner = s.owner()
        manager = s.manager()
    }

    private val base get() = "/api/v1/tenants/${s.tenant}"

    private fun staffBook(subject: String, professional: UUID, time: String = "15:00", date: Any = s.dayAfterTomorrow(), service: UUID = s.corte) =
        api.post("$base/agenda/bookings", token(subject),
            mapOf("serviceId" to service, "professionalId" to professional, "date" to date, "time" to time, "name" to "Ana Souza", "phone" to "(11) 98888-7777"))

    private fun day(subject: String, date: Any = s.dayAfterTomorrow()) = api.get("$base/agenda?date=$date", token(subject))

    @Test
    fun `booking rules have defaults, owners change them, managers cannot`() {
        assertThat(api.get("$base/booking-rules", token(manager)).node.path("cancelNoticeMinutes").asInt()).isEqualTo(120)
        assertThat(api.put("$base/booking-rules", token(manager), mapOf("daysAhead" to 15, "minNoticeMinutes" to 30, "cancelNoticeMinutes" to 60)).status).isEqualTo(403)
        assertThat(api.put("$base/booking-rules", token(owner), mapOf("daysAhead" to 120, "minNoticeMinutes" to 30, "cancelNoticeMinutes" to 60)).code).isEqualTo("validation")
        assertThat(api.put("$base/booking-rules", token(owner), mapOf("daysAhead" to 15, "minNoticeMinutes" to 30, "cancelNoticeMinutes" to 60)).node.path("daysAhead").asInt())
            .isEqualTo(15)
    }

    @Test
    fun `owner sees every professional's day with customer data in clear`() {
        staffBook(owner, s.paula)

        val columns = day(owner).node.path("professionals")
        assertThat(columns.toList().map { it.path("displayName").asString() }).containsExactlyInAnyOrder("Paula", "Rosa")
        val item = columns.toList().first { it.path("displayName").asString() == "Paula" }.path("items")[0]
        assertThat(item.path("customerName").asString()).isEqualTo("Ana Souza")
        assertThat(item.path("origin").asString()).isEqualTo("STAFF")
    }

    @Test
    fun `a professional sees and manages only their own agenda`() {
        val rosaBooking = staffBook(owner, s.rosa).node.path("item").path("id").asString()

        val mine = day(s.paulaSubject).node
        assertThat(mine.path("professionals").toList().map { it.path("displayName").asString() }).containsExactly("Paula")
        assertThat(api.post("$base/agenda/bookings/$rosaBooking/cancel", token(s.paulaSubject)).status).isEqualTo(403)
        assertThat(staffBook(s.paulaSubject, s.rosa, time = "16:00").status).isEqualTo(403)
        assertThat(staffBook(s.paulaSubject, s.paula, time = "16:00").status).isEqualTo(201)
    }

    @Test
    fun `a professional without a link gets an empty agenda with a hint`() {
        fixtures.memberOf(s.tenant, "pro-sem-link-${s.slug}", "PROFESSIONAL")

        val view = day("pro-sem-link-${s.slug}").node
        assertThat(view.path("professionals").size()).isZero()
        assertThat(view.path("linkedProfessionalMissing").asBoolean()).isTrue()
    }

    @Test
    fun `another salon's owner gets not-found`() {
        val other = fixtures.tenant("Outro")
        fixtures.memberOf(other, "owner-outro", "OWNER")

        assertThat(api.get("$base/agenda?date=${s.dayAfterTomorrow()}", token("owner-outro")).status).isEqualTo(404)
    }

    @Test
    fun `staff books a walk-in inside the minimum notice, never over another booking`() {
        s.rules(minNotice = 1440)
        val (date, soon) = s.tomorrowMidnight()
        val public = api.post("/api/v1/public/salons/${s.slug}/bookings", null,
            mapOf("serviceId" to s.corte, "professionalId" to s.paula, "date" to date, "time" to soon, "name" to "Bia", "phone" to "(11) 97777-1111"))
        assertThat(public.status).isEqualTo(409) // inside the notice for customers

        assertThat(staffBook(manager, s.paula, time = soon, date = date).status).isEqualTo(201)
        assertThat(staffBook(manager, s.paula, time = soon, date = date).code).isEqualTo("slot-taken")
    }

    @Test
    fun `staff cancels and reschedules anytime and it is audited without customer data`() {
        s.rules(cancelNotice = 2880)
        val (date, soon) = s.tomorrowMidnight()
        val id = staffBook(manager, s.paula, time = soon, date = date).node.path("item").path("id").asString()

        val moved = api.post("$base/agenda/bookings/$id/reschedule", token(manager), mapOf("date" to s.dayAfterTomorrow(), "time" to "16:00", "professionalId" to s.rosa))
        assertThat(moved.node.path("start").asString()).isEqualTo("16:00")
        assertThat(api.post("$base/agenda/bookings/$id/cancel", token(manager)).node.path("cancelledBy").asString()).isEqualTo("STAFF")

        val audit = admin.sql("select action || ' ' || details::text from audit_event where tenant_id = :t order by occurred_at").param("t", s.tenant)
            .query(String::class.java).list().filterNotNull()
        assertThat(audit.map { it.substringBefore(' ') }).containsExactly("BOOKING_RESCHEDULED_BY_STAFF", "BOOKING_CANCELLED_BY_STAFF")
        assertThat(audit.joinToString()).doesNotContain("Ana", "98888")
    }

    @Test
    fun `blocks take time, cannot cover a booking, and removing them frees the time`() {
        staffBook(manager, s.paula, time = "15:00")

        val overBooking = api.post("$base/agenda/blocks", token(manager), mapOf("professionalId" to s.paula, "date" to s.dayAfterTomorrow(), "start" to "14:00", "end" to "15:30"))
        assertThat(overBooking.code).isEqualTo("slot-taken")

        val block = api.post("$base/agenda/blocks", token(manager), mapOf("professionalId" to s.rosa, "date" to s.dayAfterTomorrow(), "start" to "00:00", "end" to "24:00", "reason" to "Folga"))
        assertThat(block.status).isEqualTo(201)
        val free = api.get("/api/v1/public/salons/${s.slug}/times?serviceId=${s.manicure}&professionalId=${s.rosa}&date=${s.dayAfterTomorrow()}").node.path("free")
        assertThat(free.size()).isZero()

        assertThat(api.delete("$base/agenda/blocks/${block.node.path("id").asString()}", token(manager)).status).isEqualTo(204)
        val after = api.get("/api/v1/public/salons/${s.slug}/times?serviceId=${s.manicure}&professionalId=${s.rosa}&date=${s.dayAfterTomorrow()}").node.path("free")
        assertThat(after.size()).isGreaterThan(0)
    }

    @Test
    fun `support sees the agenda with customer data masked`() {
        staffBook(owner, s.paula)

        val view = api.get("$base/agenda?date=${s.dayAfterTomorrow()}", supportToken()).node
        assertThat(view.toString()).doesNotContain("Ana Souza", "98888-7777", "11988887777")
        val item = view.path("professionals").toList().first { it.path("items").size() > 0 }.path("items")[0]
        assertThat(item.path("customerName").asString()).isEqualTo("A*** S****")
    }

    @Test
    fun `agenda operations log no customer data`(output: CapturedOutput) {
        staffBook(owner, s.paula)
        staffBook(owner, s.paula) // slot-taken

        assertThat(output.all).doesNotContain("Ana Souza", "98888")
    }
}
