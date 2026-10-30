package io.estilix.tenancy

import io.estilix.IntegrationTest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Task 1.2 — the database itself guards the tenant lifecycle (design D1/D2): the application role
 * can only create a PENDING salon inside that salon's own context, cannot approve it, and one
 * person cannot have two salons waiting for approval.
 */
class TenantLifecycleDbTest : IntegrationTest() {

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private lateinit var tx: TransactionTemplate
    private lateinit var user: UUID

    @BeforeEach
    fun data() {
        tx = TransactionTemplate(transactionManager)
        user = fixtures.user("owner")
    }

    private fun <T> asApp(tenant: UUID?, support: Boolean = false, block: () -> T): T =
        TenantContext.runWith(RequestContext(userId = user, tenantId = tenant, support = support, allowWrites = true)) {
            tx.execute { block() } as T
        }

    private fun insertTenant(id: UUID, status: String = "PENDING_APPROVAL", createdBy: UUID = user, slug: String = "s-${id.toString().take(8)}") =
        jdbc.sql(
            "insert into tenant (id, name, establishment_type, status, slug, created_by) values (:id, 'Novo', 'SALON', :status, :slug, :by)",
        ).param("id", id).param("status", status).param("slug", slug).param("by", createdBy).update()

    private fun statusOf(id: UUID) =
        admin.sql("select status from tenant where id = :id").param("id", id).query(String::class.java).single()

    @Test
    fun `the application creates a pending salon inside its own context`() {
        val id = UUID.randomUUID()

        asApp(id) { insertTenant(id) }

        assertThat(statusOf(id)).isEqualTo("PENDING_APPROVAL")
    }

    @Test
    fun `the application cannot create an active salon`() {
        val id = UUID.randomUUID()

        assertThatThrownBy { asApp(id) { insertTenant(id, status = "ACTIVE") } }
            .isInstanceOf(DataAccessException::class.java).rootCause().hasMessageContaining("row-level security")
    }

    @Test
    fun `the application cannot create a salon outside its context or for someone else`() {
        val id = UUID.randomUUID()

        assertThatThrownBy { asApp(UUID.randomUUID()) { insertTenant(id) } }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { asApp(id) { insertTenant(id, createdBy = UUID.randomUUID()) } }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `an owner cannot approve their own salon`() {
        val id = fixtures.tenant(status = "PENDING_APPROVAL", createdBy = user)

        assertThatThrownBy {
            asApp(id) { jdbc.sql("update tenant set status = 'ACTIVE' where id = :id").param("id", id).update() }
        }.isInstanceOf(DataAccessException::class.java).rootCause().hasMessageContaining("not allowed")
        assertThat(statusOf(id)).isEqualTo("PENDING_APPROVAL")
    }

    @Test
    fun `support approves, and the creator may resubmit only after a rejection`() {
        val approved = fixtures.tenant(status = "PENDING_APPROVAL", createdBy = user)
        asApp(approved, support = true) { jdbc.sql("update tenant set status = 'ACTIVE' where id = :id").param("id", approved).update() }
        assertThat(statusOf(approved)).isEqualTo("ACTIVE")

        val rejected = fixtures.tenant(status = "REJECTED", createdBy = user)
        asApp(rejected) { jdbc.sql("update tenant set status = 'PENDING_APPROVAL' where id = :id").param("id", rejected).update() }
        assertThat(statusOf(rejected)).isEqualTo("PENDING_APPROVAL")
    }

    @Test
    fun `two concurrent submissions by the same person leave one salon`() {
        val start = CountDownLatch(1)
        val results = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (1..2).map {
                pool.submit<Boolean> {
                    val id = UUID.randomUUID()
                    start.await()
                    runCatching { asApp(id) { insertTenant(id) } }.isSuccess
                }
            }.also { start.countDown() }.map { it.get() }
        }

        assertThat(results.count { it }).isEqualTo(1)
        assertThat(admin.sql("select count(*) from tenant where created_by = :u").param("u", user).query(Long::class.java).single())
            .isEqualTo(1)
    }
}
