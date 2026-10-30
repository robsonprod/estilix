package io.estilix.customers

import io.estilix.IntegrationTest
import io.estilix.scheduling.SchedulingSetup
import io.estilix.servicerecords.Visits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.util.UUID

/** Task 2.3 — spec customers/customer-records at the API level. */
@ExtendWith(OutputCaptureExtension::class)
class CustomersApiTest : IntegrationTest() {

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

    private val base get() = "${v.base}/customers"

    private fun create(subject: String = manager, name: String = "Ana Souza", phone: String = "(11) 98888-7777", notes: String? = null) =
        api.post(base, token(subject), mapOf("name" to name, "phone" to phone, "notes" to notes))

    private fun list(subject: String = manager, q: String? = null) =
        api.get(base + (q?.let { "?q=$it" } ?: ""), token(subject)).node.path("items").toList()

    private fun names(subject: String = manager, q: String? = null) = list(subject, q).map { it.path("name").asString() }

    // One customer per mobile phone per salon -----------------------------------------------------

    @Test
    fun `the same phone written differently is the same customer`() {
        val first = create().node.path("id").asString()

        val second = create(name = "Outra", phone = "+55 11 98888-7777")

        assertThat(second.code).isEqualTo("validation")
        assertThat(second.node.path("errors").path("phone").asString()).isEqualTo("já cadastrado")
        assertThat(second.node.path("errors").path("existingCustomerId").asString()).isEqualTo(first)
    }

    @Test
    fun `the same phone in two salons is two unrelated customers, and another salon gets not-found`() {
        val ours = create().node.path("id").asString()
        val other = SchedulingSetup(admin, fixtures)
        val otherOwner = other.owner()
        api.post("/api/v1/tenants/${other.tenant}/customers", token(otherOwner), mapOf("name" to "Ana Outro Salão", "phone" to "(11) 98888-7777"))

        assertThat(names()).containsExactly("Ana Souza")
        assertThat(api.get("/api/v1/tenants/${other.tenant}/customers", token(otherOwner)).node.path("items").toList().map { it.path("name").asString() })
            .containsExactly("Ana Outro Salão")
        assertThat(api.get("/api/v1/tenants/${other.tenant}/customers/$ours", token(otherOwner)).status).isEqualTo(404)
    }

    // Linked automatically ----------------------------------------------------------------------------

    @Test
    fun `a first public booking creates the customer, a returning one keeps the saved name`() {
        api.post(
            "/api/v1/public/salons/${s.slug}/bookings", null,
            mapOf("serviceId" to s.corte, "professionalId" to s.paula, "date" to s.dayAfterTomorrow(), "time" to "15:00", "name" to "Ana Souza", "phone" to "(11) 98888-7777"),
        )
        api.post(
            "/api/v1/public/salons/${s.slug}/bookings", null,
            mapOf("serviceId" to s.corte, "professionalId" to s.paula, "date" to s.dayAfterTomorrow(), "time" to "16:00", "name" to "Aninha", "phone" to "11988887777"),
        )

        assertThat(names()).containsExactly("Ana Souza")
        val linked = admin.sql("select count(distinct customer_id) from agenda_entry where customer_id is not null").query(Long::class.java).single()
        assertThat(linked).isEqualTo(1)
        val dayItem = api.get("${v.base}/agenda?date=${s.dayAfterTomorrow()}", token(manager)).node.path("professionals")
            .first { it.path("displayName").asString() == "Paula" }.path("items")[0]
        assertThat(dayItem.path("customerId").asString()).isEqualTo(list().single().path("id").asString())
    }

    // Profile and consent -----------------------------------------------------------------------------

    @Test
    fun `fix the name, and notes too long are refused`() {
        val id = create(name = "Aninha").node.path("id").asString()

        val renamed = api.put("$base/$id", token(manager), mapOf("name" to "Ana Souza", "phone" to "11988887777", "notes" to "Alergia a amônia"))
        assertThat(renamed.node.path("name").asString()).isEqualTo("Ana Souza")
        assertThat(renamed.node.path("notes").asString()).isEqualTo("Alergia a amônia")

        val tooLong = api.put("$base/$id", token(manager), mapOf("name" to "Ana Souza", "phone" to "11988887777", "notes" to "x".repeat(1001)))
        assertThat(tooLong.node.path("errors").has("notes")).isTrue()
    }

    @Test
    fun `consent starts unknown, is granted and revoked with history, never by a professional`() {
        val entry = v.book(manager)
        val id = v.customerOf(entry)
        assertThat(api.get("$base/$id", token(manager)).node.path("marketingConsent").asString()).isEqualTo("UNKNOWN")

        val granted = api.put("$base/$id/consent", token(manager), mapOf("consent" to "GRANTED")).node
        assertThat(granted.path("marketingConsent").asString()).isEqualTo("GRANTED")
        assertThat(granted.path("consentHistory")[0].path("source").asString()).isEqualTo("STAFF")
        assertThat(granted.path("consentHistory")[0].path("actorUserId").asString()).isNotBlank()

        val revoked = api.put("$base/$id/consent", token(manager), mapOf("consent" to "REVOKED")).node
        assertThat(revoked.path("marketingConsent").asString()).isEqualTo("REVOKED")
        assertThat(revoked.path("consentHistory").toList().map { it.path("consent").asString() }).containsExactly("REVOKED", "GRANTED")

        assertThat(api.put("$base/$id/consent", token(s.paulaSubject), mapOf("consent" to "GRANTED")).status).isEqualTo(403)
    }

    // History -----------------------------------------------------------------------------------------

    @Test
    fun `history shows visits, no-shows, total, last visit and the usual interval`() {
        val id = v.customerOf(v.book(manager))
        v.pastVisit(id, daysAgo = 66, cents = 5000)
        v.pastVisit(id, daysAgo = 36, cents = 8000)
        v.pastVisit(id, daysAgo = 1, cents = 4500)
        admin.sql("update agenda_entry set status = 'NO_SHOW' where customer_id = :c").param("c", id).update()

        val detail = api.get("$base/$id", token(manager)).node

        assertThat(detail.path("visits").asInt()).isEqualTo(3)
        assertThat(detail.path("noShows").asInt()).isEqualTo(1)
        assertThat(detail.path("totalSpent").asString()).isEqualTo("175.00")
        assertThat(detail.path("usualIntervalDays").asInt()).isEqualTo(32) // gaps 30 and 35
        assertThat(detail.path("history").size()).isEqualTo(3)
        assertThat(detail.path("history")[0].path("total").asString()).isEqualTo("45.00")
    }

    @Test
    fun `voided records do not count and two visits show no interval`() {
        val id = v.customerOf(v.book(manager))
        v.pastVisit(id, daysAgo = 40)
        v.pastVisit(id, daysAgo = 10)
        admin.sql("update service_record set status = 'VOIDED', void_reason = 'lançado errado', voided_at = now() where closed_at < now() - interval '20 days'").update()

        val detail = api.get("$base/$id", token(manager)).node
        assertThat(detail.path("visits").asInt()).isEqualTo(1)
        assertThat(detail.path("totalSpent").asString()).isEqualTo("50.00")
        assertThat(detail.path("usualIntervalDays").isNull).isTrue()
    }

    // List and search ---------------------------------------------------------------------------------

    @Test
    fun `list is ordered by last visit, never-visited last, and searches by digits and without accents`() {
        val recent = create(name = "Carla Dias", phone = "(11) 91111-2222").node.path("id").asString()
        val old = create(name = "João Silva", phone = "(11) 93333-4444").node.path("id").asString()
        create(name = "Nunca Veio", phone = "(11) 95555-6666")
        v.pastVisit(UUID.fromString(old), daysAgo = 30)
        v.pastVisit(UUID.fromString(recent), daysAgo = 2)

        assertThat(names()).containsExactly("Carla Dias", "João Silva", "Nunca Veio")
        assertThat(names(q = "93333")).containsExactly("João Silva")
        assertThat(names(q = "joao")).containsExactly("João Silva")
        assertThat(names(q = "sil")).containsExactly("João Silva")
        assertThat(names(q = "ilva")).isEmpty() // start of a word only
    }

    // Who sees which customers ------------------------------------------------------------------------

    @Test
    fun `a professional sees only customers they booked or served, others are not-found`() {
        val mine = v.customerOf(v.book(manager, professional = s.paula, name = "Cliente da Paula", phone = "(11) 91111-0001"))
        val rosas = v.customerOf(v.book(manager, professional = s.rosa, name = "Cliente da Rosa", phone = "(11) 91111-0002"))

        assertThat(names(s.paulaSubject)).containsExactly("Cliente da Paula")
        assertThat(api.get("$base/$mine", token(s.paulaSubject)).status).isEqualTo(200)
        assertThat(api.get("$base/$rosas", token(s.paulaSubject)).status).isEqualTo(404)
        assertThat(api.put("$base/$rosas", token(s.paulaSubject), mapOf("name" to "X Y", "phone" to "(11) 91111-0002")).status).isEqualTo(404)
    }

    @Test
    fun `support sees names, phones and notes masked`() {
        val id = create(notes = "Prefere a tarde").node.path("id").asString()

        val listed = api.get(base, supportToken()).node
        val detail = api.get("$base/$id", supportToken()).node

        assertThat(listed.toString() + detail.toString()).doesNotContain("Ana Souza", "98888", "Prefere")
        assertThat(detail.path("name").asString()).isEqualTo("A*** S****")
        assertThat(detail.path("notes").asString()).isEqualTo("***")
    }

    @Test
    fun `customer data never reaches logs, error bodies or audit details`(output: CapturedOutput) {
        val id = create(name = "Clarice Lispector", phone = "(11) 95555-4444", notes = "Segredo da Clarice").node.path("id").asString()
        val error = api.put("$base/$id", token(manager), mapOf("name" to "Clarice Lispector", "phone" to "(11) 95555-4444", "notes" to "Segredo ".repeat(200)))

        assertThat(error.body).doesNotContain("Clarice", "95555", "Segredo")
        assertThat(output.all).doesNotContain("Clarice", "95555", "Segredo")
        assertThat(admin.sql("select coalesce(string_agg(details::text, ''), '') from audit_event").query(String::class.java).single())
            .doesNotContain("Clarice", "95555")
    }
}
