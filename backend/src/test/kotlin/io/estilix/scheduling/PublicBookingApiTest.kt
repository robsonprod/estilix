package io.estilix.scheduling

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.YearMonth
import java.time.ZonedDateTime

/** Specs scheduling/public-booking, scheduling/bookings and scheduling/availability (API level). */
@ExtendWith(OutputCaptureExtension::class)
class PublicBookingApiTest : IntegrationTest() {

    private lateinit var s: SchedulingSetup
    private val base get() = "/api/v1/public/salons/${s.slug}"

    @BeforeEach
    fun data() {
        s = SchedulingSetup(admin, fixtures)
    }

    private fun book(time: String = "15:00", professional: Any? = s.paula, service: Any = s.corte, date: Any = s.dayAfterTomorrow(), phone: String = "(11) 98888-7777", name: String = "Ana Souza") =
        api.post("$base/bookings", null, mapOf("serviceId" to service, "professionalId" to professional, "date" to date, "time" to time, "name" to name, "phone" to phone))

    private fun times(date: Any = s.dayAfterTomorrow(), professional: Any? = s.paula, service: Any = s.corte) =
        api.get("$base/times?serviceId=$service&date=$date" + (professional?.let { "&professionalId=$it" } ?: "")).node

    @Test
    fun `active salon page shows services and professionals by display name, a pending salon is not found`() {
        val page = api.get(base).node
        assertThat(page.path("name").asString()).isEqualTo("Salão Teste")
        assertThat(page.path("services").toList().map { it.path("name").asString() }).contains("Corte", "Escova", "Manicure")
        assertThat(page.path("professionals").toList().map { it.path("displayName").asString() }).containsExactlyInAnyOrder("Paula", "Rosa")
        assertThat(page.toString()).doesNotContain("Paula Rocha", "@example.com")

        admin.sql("update tenant set status = 'PENDING_APPROVAL' where id = :t").param("t", s.tenant).update()
        assertThat(api.get(base).status).isEqualTo(404)
    }

    @Test
    fun `successful booking returns code and link, takes the time and stores only hashes`() {
        val response = book()

        assertThat(response.status).isEqualTo(201)
        val code = response.node.path("code").asString()
        val token = response.node.path("token").asString()
        assertThat(code).matches("\\d{6}")
        assertThat(response.node.path("bookings")[0].path("start").asString()).isEqualTo("15:00")
        assertThat(times().path("free").toList().map { it.asString() }).doesNotContain("15:00", "14:45").contains("15:30")
        assertThat(times().path("unavailable").toList().map { it.asString() }).contains("15:00")
        val stored = admin.sql("select code_hash || token_hash from booking_group").query(String::class.java).single()
        assertThat(stored).doesNotContain(token).doesNotContain(code)
    }

    @Test
    fun `a taken time answers slot-taken with the pt-BR message`() {
        book()
        val second = book(phone = "(11) 97777-6666", name = "Bia")

        assertThat(second.status).isEqualTo(409)
        assertThat(second.code).isEqualTo("slot-taken")
        assertThat(second.node.path("detail").asString()).isEqualTo("Esse horário acabou de ser preenchido. Escolha outro horário.")
    }

    @Test
    fun `different durations overlap, back-to-back is fine, different professionals are fine`() {
        book(time = "15:00", service = s.escova) // 15:00–16:00
        assertThat(book(time = "15:30", phone = "(11) 90000-0001").code).isEqualTo("slot-taken")
        assertThat(book(time = "16:00", phone = "(11) 90000-0002").status).isEqualTo(201)
        assertThat(book(time = "15:00", professional = s.rosa, phone = "(11) 90000-0003").status).isEqualTo(201)
    }

    @Test
    fun `any professional is assigned to a free one`() {
        book(time = "10:00", professional = s.paula)

        val anyone = book(time = "10:00", professional = null, phone = "(11) 90000-0004")

        assertThat(anyone.status).isEqualTo(201)
        assertThat(anyone.node.path("bookings")[0].path("professionalName").asString()).isEqualTo("Rosa")
        assertThat(times(professional = null).path("free").toList().map { it.asString() }).doesNotContain("10:00")
    }

    @Test
    fun `professional who does not do the service, inactive service and invalid phone are rejected`() {
        assertThat(book(service = s.manicure, professional = s.paula).code).isEqualTo("validation")
        admin.sql("update service set active = false where id = :id").param("id", s.corte).update()
        assertThat(book().node.path("errors").has("serviceId")).isTrue()
        admin.sql("update service set active = true where id = :id").param("id", s.corte).update()
        assertThat(book(phone = "9888-77").node.path("errors").has("customerPhone")).isTrue()
    }

    @Test
    fun `the duration is copied at booking time`() {
        val id = book().node.path("bookings")[0]
        admin.sql("update service set duration_minutes = 45 where id = :id").param("id", s.corte).update()

        val view = api.get("/api/v1/public/bookings/${book(time = "17:00").node.path("token").asString()}").node
        assertThat(id.path("end").asString()).isEqualTo("15:30")
        assertThat(view.path("bookings")[0].path("end").asString()).isEqualTo("17:45")
    }

    @Test
    fun `days of the month enable only bookable days and times never reveal other customers`() {
        val day = s.dayAfterTomorrow()
        val month = YearMonth.from(day)
        val days = api.get("$base/days?serviceId=${s.corte}&month=$month").node.path("days").toList().map { it.asString() }
        assertThat(days).contains(day.toString())
        assertThat(days).allMatch { it >= ZonedDateTime.now(s.zone).toLocalDate().toString() }

        book(name = "Cliente Secreta", phone = "(11) 91234-5678")
        assertThat(times().toString()).doesNotContain("Secreta", "91234")
    }

    @Test
    fun `a day beyond the window is rejected`() {
        val far = ZonedDateTime.now(s.zone).toLocalDate().plusDays(31)
        assertThat(api.get("$base/times?serviceId=${s.corte}&date=$far").code).isEqualTo("validation")
    }

    @Test
    fun `minimum notice hides times that are too soon`() {
        s.rules(minNotice = 1440)
        val (tomorrow, midnight) = s.tomorrowMidnight()

        val free = times(date = tomorrow).path("free").toList().map { it.asString() }
        assertThat(free).doesNotContain(midnight)
        assertThat(times(date = tomorrow.plusDays(1)).path("free").toList().map { it.asString() }).contains("23:00")
    }

    @Test
    fun `chained service starts when the first ends, same code`() {
        val first = book(time = "15:00").node
        val token = first.path("token").asString()

        val chained = api.post("/api/v1/public/bookings/$token/next", null, mapOf("serviceId" to s.manicure)).node

        val items = chained.path("bookings")
        assertThat(items.size()).isEqualTo(2)
        assertThat(items[1].path("start").asString()).isEqualTo("15:30")
        assertThat(items[1].path("professionalName").asString()).isEqualTo("Rosa")
    }

    @Test
    fun `chained service when nobody is free right after`() {
        val token = book(time = "15:00").node.path("token").asString()
        book(time = "15:30", professional = s.rosa, service = s.manicure, phone = "(11) 90000-0005")

        val chained = api.post("/api/v1/public/bookings/$token/next", null, mapOf("serviceId" to s.manicure))

        assertThat(chained.code).isEqualTo("slot-taken")
        assertThat(chained.node.path("detail").asString()).contains("logo em seguida")
    }

    @Test
    fun `manage with the link, cancel in time and reschedule keep the code`() {
        val token = book(time = "15:00").node.path("token").asString()
        val id = api.get("/api/v1/public/bookings/$token").node.path("bookings")[0].path("id").asString()

        val moved = api.post("/api/v1/public/bookings/$token/$id/reschedule", null, mapOf("date" to s.dayAfterTomorrow(), "time" to "16:00")).node
        assertThat(moved.path("bookings")[0].path("start").asString()).isEqualTo("16:00")
        assertThat(times().path("free").toList().map { it.asString() }).contains("15:00")

        val cancelled = api.post("/api/v1/public/bookings/$token/$id/cancel", null).node
        assertThat(cancelled.path("bookings")[0].path("status").asString()).isEqualTo("CANCELLED")
        assertThat(api.post("/api/v1/public/bookings/$token/$id/cancel", null).status).isEqualTo(200)
    }

    @Test
    fun `reschedule to a taken time keeps the booking where it was`() {
        val token = book(time = "15:00").node.path("token").asString()
        book(time = "16:00", phone = "(11) 90000-0006")
        val id = api.get("/api/v1/public/bookings/$token").node.path("bookings")[0].path("id").asString()

        assertThat(api.post("/api/v1/public/bookings/$token/$id/reschedule", null, mapOf("date" to s.dayAfterTomorrow(), "time" to "16:00")).code)
            .isEqualTo("slot-taken")
        assertThat(api.get("/api/v1/public/bookings/$token").node.path("bookings")[0].path("start").asString()).isEqualTo("15:00")
    }

    @Test
    fun `too late to cancel shows the salon phone`() {
        s.rules(cancelNotice = 2880)
        val (date, soon) = s.tomorrowMidnight()
        val owner = s.owner()
        val staff = api.post("/api/v1/tenants/${s.tenant}/agenda/bookings", token(owner),
            mapOf("serviceId" to s.corte, "professionalId" to s.rosa, "date" to date, "time" to soon, "name" to "Ana", "phone" to "(11) 98888-1111"))
        assertThat(staff.status).isEqualTo(201)
        val code = staff.node.path("code").asString()

        val view = api.post("$base/lookup", null, mapOf("phone" to "11988881111", "code" to code)).node
        val token = view.path("token").asString()
        val item = view.path("bookings")[0]
        assertThat(item.path("canChange").asBoolean()).isFalse()

        val late = api.post("/api/v1/public/bookings/$token/${item.path("id").asString()}/cancel", null)
        assertThat(late.code).isEqualTo("too-late")
        assertThat(view.path("salonPhone").asString()).isEqualTo("+55 11 3333-0000")
    }

    @Test
    fun `phone and code open the bookings with a fresh link`() {
        val first = book().node
        val oldToken = first.path("token").asString()

        val view = api.post("$base/lookup", null, mapOf("phone" to "+55 11 98888-7777", "code" to first.path("code").asString()))

        assertThat(view.status).isEqualTo(200)
        assertThat(view.node.path("bookings").size()).isEqualTo(1)
        assertThat(api.get("/api/v1/public/bookings/$oldToken").status).isEqualTo(404)
        assertThat(api.get("/api/v1/public/bookings/${view.node.path("token").asString()}").status).isEqualTo(200)
    }

    @Test
    fun `five wrong codes lock the phone for a while`() {
        book()
        repeat(5) { assertThat(api.post("$base/lookup", null, mapOf("phone" to "11988887777", "code" to "000000")).status).isEqualTo(404) }

        val locked = api.post("$base/lookup", null, mapOf("phone" to "11988887777", "code" to "000000"))
        assertThat(locked.status).isEqualTo(429)
        assertThat(locked.header("Retry-After")).isNotBlank()
    }

    @Test
    fun `at most three active bookings per phone per day`() {
        listOf("09:00", "10:00", "11:00").forEach { assertThat(book(time = it).status).isEqualTo(201) }

        val fourth = book(time = "12:00")
        assertThat(fourth.status).isEqualTo(400)
        assertThat(fourth.node.path("errors").has("customerPhone")).isTrue()
    }

    @Test
    fun `customer data never reaches the logs`(output: CapturedOutput) {
        book(name = "Clarice Lispector", phone = "(11) 95555-4444")
        book(name = "Clarice Lispector", phone = "(11) 95555-4444") // slot-taken

        assertThat(output.all).doesNotContain("Clarice", "95555")
    }
}
