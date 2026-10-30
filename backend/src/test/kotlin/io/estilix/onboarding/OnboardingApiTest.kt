package io.estilix.onboarding

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Spec tenancy/tenant-onboarding (tasks 5.1–5.4). */
@ExtendWith(OutputCaptureExtension::class)
class OnboardingApiTest : IntegrationTest() {

    private fun request(
        name: String = "Barbearia do Zé",
        slug: String = "barbearia-do-ze",
        type: String = "BARBERSHOP",
        hours: List<Map<String, Any>> = listOf(mapOf("weekday" to 1, "start" to "09:00", "end" to "19:00")),
        acceptTerms: Boolean = true,
        phone: String = "+55 85 98888-7777",
        contactName: String = "José Ferreira",
    ) = mapOf(
        "planCode" to "launch",
        "establishmentType" to type,
        "name" to name,
        "phone" to phone,
        "address" to mapOf("street" to "Rua A", "number" to "10", "district" to "Centro", "city" to "Fortaleza", "state" to "CE", "postalCode" to "60000-000"),
        "timeZone" to "America/Fortaleza",
        "slug" to slug,
        "contactName" to contactName,
        "hours" to hours,
        "services" to listOf(
            mapOf("name" to "Corte masculino", "category" to "Cabelo", "durationMinutes" to 30, "price" to "45.00", "priceType" to "FIXED"),
            mapOf("name" to "Barba", "category" to "Barba", "durationMinutes" to 30, "price" to "35.00", "priceType" to "FIXED"),
        ),
        "acceptTerms" to acceptTerms,
        "termsVersion" to Terms.VERSION,
    )

    private fun submit(subject: String = "ze", body: Map<String, Any?> = request()) = api.post("/api/v1/onboarding", token(subject, name = "José Ferreira"), body)

    private fun countOf(table: String) = admin.sql("select count(*) from $table").query(Long::class.java).single()

    @Test
    fun `complete submission creates a pending salon with its owner, hours and services`() {
        val response = submit()

        assertThat(response.status).isEqualTo(201)
        val tenant = UUID.fromString(response.node.path("tenantId").asString())
        val row = admin.sql("select status, terms_version, terms_accepted_at is not null as accepted, contact_email from tenant where id = :id")
            .param("id", tenant).query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getBoolean(3).toString(), rs.getString(4)) }.single()
        assertThat(row).containsExactly("PENDING_APPROVAL", Terms.VERSION, "true", "ze@example.com")
        assertThat(admin.sql("select role from membership where tenant_id = :t").param("t", tenant).query(String::class.java).single()).isEqualTo("OWNER")
        assertThat(countOf("opening_hours")).isEqualTo(1)
        assertThat(countOf("service")).isEqualTo(2)
        assertThat(fixtures.auditActions(tenant)).containsExactly("TENANT_SIGNED_UP")
    }

    @Test
    fun `invalid data saves nothing`() {
        val overlap = listOf(mapOf("weekday" to 1, "start" to "09:00", "end" to "13:00"), mapOf("weekday" to 1, "start" to "12:00", "end" to "18:00"))

        val response = submit(body = request(slug = "admin", hours = overlap))

        assertThat(response.status).isEqualTo(400)
        assertThat(response.node.path("errors").has("slug")).isTrue()
        assertThat(response.node.path("errors").has("hours[1]")).isTrue()
        assertThat(countOf("tenant") + countOf("membership") + countOf("service")).isZero()
    }

    @Test
    fun `terms must be accepted`() {
        assertThat(submit(body = request(acceptTerms = false)).node.path("errors").has("acceptTerms")).isTrue()
    }

    @Test
    fun `a slug already in use is a conflict`() {
        submit()
        val other = submit("ana", request(name = "Outra", slug = "barbearia-do-ze"))

        assertThat(other.status).isEqualTo(409)
        assertThat(other.node.path("errors").has("slug")).isTrue()
    }

    @Test
    fun `one salon awaiting approval per person, also under concurrency`() {
        assertThat(submit().status).isEqualTo(201)
        assertThat(submit(body = request(slug = "outra-barbearia")).status).isEqualTo(409)

        val start = CountDownLatch(1)
        val statuses = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (1..2).map { i -> pool.submit<Int> { start.await(); submit("maria", request(slug = "salao-maria-$i")).status } }
                .also { start.countDown() }.map { it.get() }
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409)
    }

    @Test
    fun `owner of a pending salon sees its status and no salon features`() {
        val tenant = submit().node.path("tenantId").asString()

        val status = api.get("/api/v1/onboarding/status", token("ze")).node
        assertThat(status.path("status").asString()).isEqualTo("PENDING_APPROVAL")
        assertThat(status.path("services").size()).isEqualTo(2)
        assertThat(api.get("/api/v1/tenants/$tenant/services", token("ze")).code).isEqualTo("tenant-pending-approval")
        assertThat(api.get("/api/v1/onboarding/status", token("nobody")).status).isEqualTo(204)
    }

    @Test
    fun `support queue shows contact unmasked, oldest first, and viewing it is audited`() {
        submit()
        val queue = api.get("/api/v1/support/approvals", supportToken())

        assertThat(queue.status).isEqualTo(200)
        val item = queue.node[0]
        assertThat(item.path("contactName").asString()).isEqualTo("José Ferreira")
        assertThat(item.path("contactEmail").asString()).isEqualTo("ze@example.com")
        assertThat(item.path("phone").asString()).isEqualTo("+55 85 98888-7777")
        assertThat(fixtures.auditActions(null)).contains("APPROVAL_QUEUE_VIEWED")
        assertThat(api.get("/api/v1/support/approvals", token("ze")).status).isEqualTo(403)
    }

    @Test
    fun `approve makes the salon active, clears the contact snapshot and masks it for support afterwards`() {
        val tenant = submit().node.path("tenantId").asString()

        assertThat(api.post("/api/v1/support/approvals/$tenant/approve", supportToken()).status).isEqualTo(204)

        assertThat(api.get("/api/v1/tenants/$tenant/services", token("ze")).status).isEqualTo(200)
        assertThat(admin.sql("select contact_name from tenant where id = :id").param("id", UUID.fromString(tenant)).query(String::class.java).list())
            .containsExactly(null)
        assertThat(api.get("/api/v1/tenants/$tenant/members", supportToken()).body).doesNotContain("José Ferreira", "ze@example.com")
        assertThat(fixtures.auditActions(UUID.fromString(tenant))).containsExactly("TENANT_SIGNED_UP", "TENANT_APPROVED")
        assertThat(api.post("/api/v1/support/approvals/$tenant/approve", supportToken()).code).isEqualTo("conflict")
    }

    @Test
    fun `reject needs a reason, then the owner fixes and resubmits`() {
        val tenant = submit().node.path("tenantId").asString()
        assertThat(api.post("/api/v1/support/approvals/$tenant/reject", supportToken(), mapOf("reason" to "curto")).status).isEqualTo(400)

        api.post("/api/v1/support/approvals/$tenant/reject", supportToken(), mapOf("reason" to "Telefone não atende, confirme o número"))
        val rejected = api.get("/api/v1/onboarding/status", token("ze")).node
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED")
        assertThat(rejected.path("rejectionReason").asString()).isEqualTo("Telefone não atende, confirme o número")

        val again = api.put("/api/v1/onboarding/$tenant", token("ze"), request(phone = "+55 85 97777-6666"))
        assertThat(again.status).isEqualTo(200)
        assertThat(api.get("/api/v1/support/approvals", supportToken()).node[0].path("phone").asString()).isEqualTo("+55 85 97777-6666")
        assertThat(fixtures.auditActions(UUID.fromString(tenant))).containsExactly("TENANT_SIGNED_UP", "TENANT_REJECTED", "TENANT_RESUBMITTED")
    }

    @Test
    fun `another user cannot resubmit someone else's salon`() {
        val tenant = submit().node.path("tenantId").asString()
        api.post("/api/v1/support/approvals/$tenant/reject", supportToken(), mapOf("reason" to "Telefone não atende, confirme o número"))

        assertThat(api.put("/api/v1/onboarding/$tenant", token("intruder"), request()).status).isEqualTo(404)
    }

    @Test
    fun `concurrent approve and reject apply exactly one decision`() {
        val tenant = submit().node.path("tenantId").asString()
        val start = CountDownLatch(1)
        val statuses = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            listOf(
                pool.submit<Int> { start.await(); api.post("/api/v1/support/approvals/$tenant/approve", supportToken("s1")).status },
                pool.submit<Int> {
                    start.await()
                    api.post("/api/v1/support/approvals/$tenant/reject", supportToken("s2"), mapOf("reason" to "Dados incompletos no cadastro")).status
                },
            ).also { start.countDown() }.map { it.get() }
        }

        assertThat(statuses).containsExactlyInAnyOrder(204, 409)
        val decisions = fixtures.auditActions(UUID.fromString(tenant)).filter { it == "TENANT_APPROVED" || it == "TENANT_REJECTED" }
        assertThat(decisions).hasSize(1)
    }

    @Test
    fun `onboarding errors leak no owner, phone or address values`(output: CapturedOutput) {
        val response = submit(body = request(slug = "x", contactName = "José Ferreira", phone = "+55 85 98888-7777"))

        assertThat(response.status).isEqualTo(400)
        listOf("José Ferreira", "98888-7777", "Rua A").forEach {
            assertThat(output.all).doesNotContain(it)
            assertThat(response.body).doesNotContain(it)
        }
    }
}
