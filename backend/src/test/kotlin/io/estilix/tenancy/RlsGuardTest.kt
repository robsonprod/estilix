package io.estilix.tenancy

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Task 3.2 — every table with a `tenant_id` column must have RLS enabled, FORCED and at least one
 * policy. A future migration that forgets it fails this test, not production.
 */
class RlsGuardTest : IntegrationTest() {

    @Test
    fun `every tenant-owned table is protected by row-level security`() {
        assertThat(unprotectedTenantTables(admin)).isEmpty()
    }

    @Test
    fun `the guard catches an unprotected table`() {
        admin.sql("create table rls_probe (id int primary key, tenant_id uuid)").update()
        try {
            assertThat(unprotectedTenantTables(admin)).containsExactly("rls_probe")
        } finally {
            admin.sql("drop table rls_probe").update()
        }
    }

    companion object {
        fun unprotectedTenantTables(jdbc: JdbcClient): List<String> =
            jdbc.sql(
                """
                select c.relname
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'
                where c.relkind = 'r'
                  and exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped)
                  and (not c.relrowsecurity
                       or not c.relforcerowsecurity
                       or not exists (select 1 from pg_policies p where p.schemaname = 'public' and p.tablename = c.relname))
                order by c.relname
                """.trimIndent(),
            ).query(String::class.java).list().filterNotNull()
    }
}
