package io.estilix.tenancy

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Spec tenancy/tenant-isolation, "Database-enforced isolation": the queries below have NO tenant
 * condition on purpose — PostgreSQL must still keep each salon's rows apart.
 */
class TenantIsolationTest : IntegrationTest() {

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private lateinit var tx: TransactionTemplate
    private lateinit var tenantA: UUID
    private lateinit var tenantB: UUID
    private lateinit var userA: UUID
    private lateinit var userB: UUID

    @BeforeEach
    fun data() {
        tx = TransactionTemplate(transactionManager)
        tenantA = fixtures.tenant("Salão A")
        tenantB = fixtures.tenant("Barbearia B", type = "BARBERSHOP")
        userA = fixtures.memberOf(tenantA, "owner-a", "OWNER")
        userB = fixtures.memberOf(tenantB, "owner-b", "OWNER")
    }

    private fun <T> inTenant(tenant: UUID?, block: () -> T): T =
        TenantContext.runWith(RequestContext(tenantId = tenant)) { tx.execute { block() } as T }

    private fun membershipTenants(): List<UUID> =
        jdbc.sql("select tenant_id from membership").query(UUID::class.java).list().filterNotNull()

    @Test
    fun `query without tenant filter returns only the current tenant rows`() {
        assertThat(inTenant(tenantA) { membershipTenants() }).containsExactly(tenantA)
        assertThat(inTenant(tenantB) { membershipTenants() }).containsExactly(tenantB)
    }

    @Test
    fun `cross-tenant update and delete change nothing`() {
        val updated = inTenant(tenantA) {
            jdbc.sql("update membership set role = 'MANAGER' where user_id = :u").param("u", userB).update()
        }
        val deleted = inTenant(tenantA) { jdbc.sql("delete from membership where user_id = :u").param("u", userB).update() }

        assertThat(updated).isZero()
        assertThat(deleted).isZero()
        assertThat(admin.sql("select role from membership where user_id = :u").param("u", userB).query(String::class.java).single())
            .isEqualTo("OWNER")
    }

    @Test
    fun `insert into another tenant is rejected by the database`() {
        assertThatThrownBy {
            inTenant(tenantA) {
                jdbc.sql("insert into membership (tenant_id, user_id, role) values (:t, :u, 'MANAGER')")
                    .param("t", tenantB).param("u", userA).update()
            }
        }.isInstanceOf(DataAccessException::class.java).rootCause().hasMessageContaining("violates row-level security policy")
    }

    @Test
    fun `tenant of a record cannot be changed`() {
        assertThatThrownBy {
            inTenant(tenantA) {
                jdbc.sql("update membership set tenant_id = :b where user_id = :u").param("b", tenantB).param("u", userA).update()
            }
        }.isInstanceOf(DataAccessException::class.java).rootCause().hasMessageContaining("violates row-level security policy")
        assertThat(admin.sql("select tenant_id from membership where user_id = :u").param("u", userA).query(UUID::class.java).single())
            .isEqualTo(tenantA)
    }

    @Test
    fun `without tenant context nothing is visible and nothing can be written`() {
        assertThat(inTenant(null) { membershipTenants() }).isEmpty()
        assertThat(inTenant(null) { jdbc.sql("select count(*) from audit_event").query(Long::class.java).single() }).isZero()
        assertThatThrownBy {
            inTenant(null) {
                jdbc.sql("insert into membership (tenant_id, user_id, role) values (:t, :u, 'MANAGER')")
                    .param("t", tenantA).param("u", userB).update()
            }
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `outside a transaction the tenant is never set (fail closed)`() {
        TenantContext.runWith(RequestContext(tenantId = tenantA)) {
            assertThat(membershipTenants()).isEmpty()
        }
    }

    /** Task 3.4 — one physical connection reused by alternating tenants, like a pool of size 1. */
    @Test
    fun `context does not leak between transactions on the same connection`() {
        val single = SingleConnectionDataSource(POSTGRES.jdbcUrl, "estilix_app", "estilix_app", true)
        try {
            val oneConnTx = TransactionTemplate(TenantTransactionManager(single))
            val oneConnJdbc = JdbcClient.create(single)
            fun tenantsSeenAs(tenant: UUID?) = TenantContext.runWith(RequestContext(tenantId = tenant)) {
                oneConnTx.execute { oneConnJdbc.sql("select tenant_id from membership").query(UUID::class.java).list() }
            }

            repeat(3) {
                assertThat(tenantsSeenAs(tenantA)).containsExactly(tenantA)
                assertThat(tenantsSeenAs(tenantB)).containsExactly(tenantB)
                assertThat(tenantsSeenAs(null)).isEmpty() // a stale value would show A or B here
            }
            // Also outside any transaction, after settings were used on this session.
            assertThat(oneConnJdbc.sql("select tenant_id from membership").query(UUID::class.java).list()).isEmpty()
        } finally {
            single.destroy()
        }
    }
}
