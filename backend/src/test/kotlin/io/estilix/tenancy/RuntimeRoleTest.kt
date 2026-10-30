package io.estilix.tenancy

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/** Task 1.3: the application must not be able to switch Row-Level Security off. */
class RuntimeRoleTest : IntegrationTest() {

    @Autowired
    lateinit var jdbc: JdbcClient

    @Test
    fun `application connects as estilix_app without BYPASSRLS and owns no table`() {
        assertThat(jdbc.sql("select current_user").query(String::class.java).single()).isEqualTo("estilix_app")
        assertThat(jdbc.sql("select rolbypassrls or rolsuper from pg_roles where rolname = current_user").query(Boolean::class.java).single())
            .isFalse()
        val owned = jdbc.sql("select count(*) from pg_tables where schemaname = 'public' and tableowner = current_user")
            .query(Long::class.java).single()
        assertThat(owned).isZero()
    }
}
