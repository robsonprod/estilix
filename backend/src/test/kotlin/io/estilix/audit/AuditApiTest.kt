package io.estilix.audit

import io.estilix.IntegrationTest
import io.estilix.tenancy.RequestContext
import io.estilix.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Spec platform/audit-log. */
class AuditApiTest : IntegrationTest() {

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var auditLog: AuditLog

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private lateinit var salonA: UUID
    private lateinit var salonB: UUID
    private lateinit var managerId: UUID

    @BeforeEach
    fun data() {
        salonA = fixtures.tenant("Salão A")
        salonB = fixtures.tenant("Salão B")
        fixtures.memberOf(salonA, "owner-a", "OWNER", "Ana Paula Souza")
        managerId = fixtures.memberOf(salonA, "manager-a", "MANAGER", "Gil Gerente")
        fixtures.memberOf(salonB, "owner-b", "OWNER", "Bruno Dono")
    }

    private fun events(tenant: UUID) = "/api/v1/tenants/$tenant/audit-events"

    @Test
    fun `owner reads the salon's events newest first, without personal data`() {
        api.put("/api/v1/tenants/$salonA/members/$managerId", token("owner-a"), mapOf("role" to "PROFESSIONAL"))
        api.put("/api/v1/tenants/$salonA/members/$managerId", token("owner-a"), mapOf("role" to "MANAGER"))

        val page = api.get(events(salonA), token("owner-a")).node
        val items = page.path("items")

        assertThat(items.size()).isEqualTo(2)
        assertThat(items[0].path("details").path("to").asString()).isEqualTo("MANAGER")
        assertThat(items[0].path("targetId").asString()).isEqualTo(managerId.toString())
        assertThat(page.toString()).doesNotContain("Gil", "Gerente", "@example.com")
    }

    @Test
    fun `events are paginated`() {
        repeat(3) { api.put("/api/v1/tenants/$salonA/members/$managerId", token("owner-a"), mapOf("role" to if (it % 2 == 0) "PROFESSIONAL" else "MANAGER")) }

        val second = api.get("${events(salonA)}?page=1&size=2", token("owner-a")).node
        assertThat(second.path("items").size()).isEqualTo(1)
        assertThat(api.get("${events(salonA)}?size=500", token("owner-a")).status).isEqualTo(400)
    }

    @Test
    fun `manager cannot read the audit log`() {
        assertThat(api.get(events(salonA), token("manager-a")).status).isEqualTo(403)
    }

    @Test
    fun `owner of another salon gets not-found`() {
        assertThat(api.get(events(salonA), token("owner-b")).status).isEqualTo(404)
    }

    @Test
    fun `support reads any salon's events`() {
        api.put("/api/v1/tenants/$salonA/members/$managerId", token("owner-a"), mapOf("role" to "PROFESSIONAL"))

        assertThat(api.get(events(salonA), supportToken()).node.path("items").size()).isEqualTo(1)
    }

    @Test
    fun `the application role cannot update or delete events`() {
        api.put("/api/v1/tenants/$salonA/members/$managerId", token("owner-a"), mapOf("role" to "PROFESSIONAL"))
        val tx = TransactionTemplate(transactionManager)

        TenantContext.runWith(RequestContext(tenantId = salonA)) {
            assertThatThrownBy { tx.execute { jdbc.sql("update audit_event set action = 'X'").update() } }
                .isInstanceOf(DataAccessException::class.java).rootCause().hasMessageContaining("permission denied")
            assertThatThrownBy { tx.execute { jdbc.sql("delete from audit_event").update() } }
                .isInstanceOf(DataAccessException::class.java).rootCause().hasMessageContaining("permission denied")
        }
    }

    @Test
    fun `even the owner role is stopped by the append-only trigger`() {
        api.put("/api/v1/tenants/$salonA/members/$managerId", token("owner-a"), mapOf("role" to "PROFESSIONAL"))

        assertThatThrownBy { admin.sql("delete from audit_event").update() }
            .rootCause().hasMessageContaining("append-only")
    }

    @Test
    fun `a rolled-back action leaves no event`() {
        val tx = TransactionTemplate(transactionManager)

        TenantContext.runWith(RequestContext(tenantId = salonA)) {
            runCatching {
                tx.executeWithoutResult {
                    auditLog.record(AuditAction.MEMBERSHIP_REMOVED, "user", managerId)
                    error("the action failed after writing the event")
                }
            }
        }

        assertThat(fixtures.auditActions(salonA)).isEmpty()
    }
}
