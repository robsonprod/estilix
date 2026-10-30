package io.estilix.support

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** Spec identity/support-access and the audit of break-glass (platform/audit-log). */
class SupportAccessApiTest : IntegrationTest() {

    @Autowired
    lateinit var expirationJob: BreakGlassExpirationJob

    private lateinit var salonA: UUID
    private lateinit var salonB: UUID
    private val reason = "Cliente relatou erro ao listar a equipe, ticket #4821"

    @BeforeEach
    fun data() {
        salonA = fixtures.tenant("Salão A")
        salonB = fixtures.tenant("Salão B")
        fixtures.memberOf(salonA, "owner-a", "OWNER", "Ana Paula Souza")
        fixtures.memberOf(salonA, "manager-a", "MANAGER", "Gil Gerente")
        fixtures.memberOf(salonB, "owner-b", "OWNER", "Bruno Dono")
    }

    private fun members(tenant: UUID) = "/api/v1/tenants/$tenant/members"
    private fun breakGlass(tenant: UUID) = "/api/v1/support/tenants/$tenant/break-glass"
    private fun open(tenant: UUID = salonA, minutes: Int = 30, why: String = reason) =
        api.post(breakGlass(tenant), supportToken(), mapOf("reason" to why, "minutes" to minutes))

    @Test
    fun `support lists every tenant`() {
        val list = api.get("/api/v1/support/tenants", supportToken()).node

        assertThat(list.toList().map { it.path("name").asString() }).containsExactly("Salão A", "Salão B")
        assertThat(list[0].has("status")).isTrue()
        assertThat(list[0].has("createdAt")).isTrue()
    }

    @Test
    fun `support reads members with personal data masked and roles in clear`() {
        val list = api.get(members(salonA), supportToken()).node
        val owner = list.toList().first { it.path("role").asString() == "OWNER" }

        assertThat(owner.path("name").asString()).isEqualTo("A*** P**** S****")
        assertThat(owner.path("email").asString()).isEqualTo("o***@example.com")
        assertThat(list.toString()).doesNotContain("Ana Paula", "owner-a@example.com")
    }

    @Test
    fun `support cannot change a tenant`() {
        val managerId = admin.sql("select id from app_user where subject = 'manager-a'").query(UUID::class.java).single()

        assertThat(api.put("${members(salonA)}/$managerId", supportToken(), mapOf("role" to "OWNER")).status).isEqualTo(403)
    }

    @Test
    fun `tenant users cannot call support endpoints`() {
        assertThat(api.get("/api/v1/support/tenants", token("owner-a")).status).isEqualTo(403)
        assertThat(api.post(breakGlass(salonA), token("owner-a"), mapOf("reason" to reason, "minutes" to 10)).status).isEqualTo(403)
    }

    @Test
    fun `support can still read a suspended salon, masked`() {
        admin.sql("update tenant set status = 'SUSPENDED' where id = :id").param("id", salonA).update()

        val response = api.get(members(salonA), supportToken())

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).doesNotContain("Ana Paula")
    }

    @Test
    fun `break-glass with a valid reason unmasks that salon for that support user and is audited`() {
        val opened = open()
        assertThat(opened.status).isEqualTo(201)
        val sessionId = opened.node.path("id").asString()

        val list = api.get(members(salonA), supportToken())

        assertThat(list.body).contains("Ana Paula Souza")
        val actions = fixtures.auditActions(salonA)
        assertThat(actions).containsExactly("BREAK_GLASS_OPENED", "UNMASKED_READ")
        val unmasked = admin.sql("select target_type, target_id, break_glass_session_id::text from audit_event where action = 'UNMASKED_READ'")
            .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
        assertThat(unmasked.first).isEqualTo("GET /api/v1/tenants/{tenantId}/members")
        assertThat(unmasked.second).isEqualTo(members(salonA))
        assertThat(unmasked.third).isEqualTo(sessionId)
        assertThat(admin.sql("select unmasked_reads from break_glass_session").query(Int::class.java).single()).isEqualTo(1)
    }

    @Test
    fun `other support users and other salons stay masked during a session`() {
        open()

        assertThat(api.get(members(salonA), supportToken("support-2")).body).doesNotContain("Ana Paula")
        assertThat(api.get(members(salonB), supportToken()).body).doesNotContain("Bruno Dono")
    }

    @Test
    fun `missing or short reason and too long durations are rejected`() {
        val short = open(why = "erro na tela")
        val tooLong = open(minutes = 61)

        assertThat(short.status).isEqualTo(400)
        assertThat(short.node.path("errors").has("reason")).isTrue()
        assertThat(tooLong.node.path("errors").has("minutes")).isTrue()
        assertThat(api.post(breakGlass(salonA), supportToken(), mapOf("minutes" to 10)).status).isEqualTo(400)
        assertThat(fixtures.auditActions(salonA)).isEmpty()
    }

    @Test
    fun `a second active session on the same salon is a conflict`() {
        open()
        assertThat(open().code).isEqualTo("conflict")
    }

    @Test
    fun `session expires by the database clock without any action`() {
        open()
        ageSessions()

        assertThat(api.get(members(salonA), supportToken()).body).doesNotContain("Ana Paula")
        assertThat(open().status).isEqualTo(201) // expire-on-write freed the "one active" slot
    }

    @Test
    fun `support ends the session early and masked reads resume`() {
        val id = open().node.path("id").asString()

        assertThat(api.post("${breakGlass(salonA)}/$id/end", supportToken()).status).isEqualTo(204)
        assertThat(api.get(members(salonA), supportToken()).body).doesNotContain("Ana Paula")
        assertThat(fixtures.auditActions(salonA)).containsExactly("BREAK_GLASS_OPENED", "BREAK_GLASS_ENDED")
    }

    @Test
    fun `owner sees the access history of their salon only`() {
        open()
        api.get(members(salonA), supportToken())

        val history = api.get("/api/v1/tenants/$salonA/access-history", token("owner-a")).node
        assertThat(history.size()).isEqualTo(1)
        assertThat(history[0].path("reason").asString()).isEqualTo(reason)
        assertThat(history[0].path("status").asString()).isEqualTo("ACTIVE")
        assertThat(history[0].path("unmaskedReads").asInt()).isEqualTo(1)

        assertThat(api.get("/api/v1/tenants/$salonB/access-history", token("owner-b")).node.size()).isZero()
        assertThat(api.get("/api/v1/tenants/$salonA/access-history", token("manager-a")).status).isEqualTo(403)
    }

    @Test
    fun `owner sees an active session indicator in me`() {
        open()

        val tenant = api.get("/api/v1/me", token("owner-a")).node.path("tenants")[0]
        assertThat(tenant.path("supportAccess").path("reason").asString()).isEqualTo(reason)
        assertThat(api.get("/api/v1/me", token("owner-b")).node.path("tenants")[0].path("supportAccess").isNull).isTrue()
    }

    @Test
    fun `nightly job marks expired sessions once and audits them`() {
        open()
        ageSessions()

        assertThat(expirationJob.run()).isEqualTo(1)
        assertThat(expirationJob.run()).isZero()
        assertThat(admin.sql("select status from break_glass_session").query(String::class.java).single()).isEqualTo("EXPIRED")
        assertThat(fixtures.auditActions(salonA)).containsExactly("BREAK_GLASS_OPENED", "BREAK_GLASS_EXPIRED")
    }

    /** Moves every session 2 hours into the past (keeps the 60-minute check constraint valid). */
    private fun ageSessions() {
        admin.sql("update break_glass_session set started_at = started_at - interval '2 hours', expires_at = expires_at - interval '2 hours'").update()
    }
}
