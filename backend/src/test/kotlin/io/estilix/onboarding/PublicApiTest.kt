package io.estilix.onboarding

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Spec marketing/public-site: public endpoints without login and without tenant data (task 6.1). */
class PublicApiTest : IntegrationTest() {

    @Test
    fun `plans are public and show the launch plan at zero`() {
        val response = api.get("/api/v1/public/plans")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.node[0].path("code").asString()).isEqualTo("launch")
        assertThat(response.node[0].path("priceLabel").asString()).isEqualTo("R$ 0,00/mês")
        assertThat(response.node[0].path("features").size()).isGreaterThan(0)
    }

    @Test
    fun `inactive plans are hidden`() {
        admin.sql("insert into plan (code, name, description, price_cents, active) values ('old', 'Antigo', 'x', 1000, false)").update()
        try {
            assertThat(api.get("/api/v1/public/plans").node.toList().map { it.path("code").asString() }).doesNotContain("old")
        } finally {
            admin.sql("delete from plan where code = 'old'").update()
        }
    }

    @Test
    fun `slug availability answers yes or no without revealing the owner`() {
        fixtures.tenant("Salão Secreto Ltda").let { id ->
            admin.sql("update tenant set slug = 'salao-secreto' where id = :id").param("id", id).update()
        }

        val taken = api.get("/api/v1/public/slugs/salao-secreto")
        val free = api.get("/api/v1/public/slugs/salao-livre")
        val reserved = api.get("/api/v1/public/slugs/admin")

        assertThat(taken.node.path("available").asBoolean()).isFalse()
        assertThat(taken.body).doesNotContain("Secreto Ltda").doesNotContain("tenantId")
        assertThat(free.node.path("available").asBoolean()).isTrue()
        assertThat(reserved.node.path("valid").asBoolean()).isFalse()
    }

    @Test
    fun `slug suggestion, presets and wizard defaults are public`() {
        assertThat(api.get("/api/v1/public/slugs?name=Salão%20Bela%20Vista").node.path("slug").asString()).isEqualTo("salao-bela-vista")
        assertThat(api.get("/api/v1/public/presets?type=BARBERSHOP").node.toList().map { it.path("name").asString() }).contains("Barba")
        val defaults = api.get("/api/v1/public/wizard-defaults").node
        assertThat(defaults.path("hours").size()).isEqualTo(6)
        assertThat(defaults.path("termsVersion").asString()).isEqualTo(Terms.VERSION)
    }

    @Test
    fun `writes are not public`() {
        assertThat(api.post("/api/v1/onboarding", null, mapOf<String, Any>()).status).isEqualTo(401)
    }
}
