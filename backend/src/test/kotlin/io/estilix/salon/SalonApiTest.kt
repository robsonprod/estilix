package io.estilix.salon

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Spec salon/salon-profile (task 2.2 and 2.3). */
class SalonApiTest : IntegrationTest() {

    private lateinit var salon: UUID
    private lateinit var other: UUID

    @BeforeEach
    fun data() {
        salon = fixtures.tenant("Salão Bela Vista")
        other = fixtures.tenant("Barbearia do Zé", "BARBERSHOP")
        fixtures.memberOf(salon, "owner", "OWNER")
        fixtures.memberOf(salon, "manager", "MANAGER")
        fixtures.memberOf(salon, "pro", "PROFESSIONAL")
        fixtures.memberOf(other, "ze", "OWNER")
    }

    private val path get() = "/api/v1/tenants/$salon/salon"

    private fun profile(slug: String = "salao-bela-vista", phone: String? = "+55 85 3333-4444", tz: String = "America/Fortaleza") = mapOf(
        "name" to "Salão Bela Vista",
        "phone" to phone,
        "timeZone" to tz,
        "slug" to slug,
        "address" to mapOf("street" to "Rua das Flores", "number" to "100", "district" to "Centro", "city" to "Fortaleza", "state" to "CE", "postalCode" to "60000-000"),
    )

    @Test
    fun `owner updates the profile and reads it back`() {
        val saved = api.put(path, token("owner"), profile())

        assertThat(saved.status).isEqualTo(200)
        val read = api.get(path, token("pro")).node
        assertThat(read.path("phone").asString()).isEqualTo("+55 85 3333-4444")
        assertThat(read.path("address").path("city").asString()).isEqualTo("Fortaleza")
        assertThat(read.path("timeZone").asString()).isEqualTo("America/Fortaleza")
    }

    @Test
    fun `manager cannot edit the profile and another salon's owner gets not-found`() {
        assertThat(api.put(path, token("manager"), profile()).status).isEqualTo(403)
        assertThat(api.get(path, token("ze")).status).isEqualTo(404)
    }

    @Test
    fun `unsupported time zone, reserved slug and slug in use are rejected`() {
        assertThat(api.put(path, token("owner"), profile(tz = "Europe/Lisbon")).node.path("errors").has("timeZone")).isTrue()
        assertThat(api.put(path, token("owner"), profile(slug = "admin")).node.path("errors").path("slug").asString())
            .isEqualTo("este endereço é reservado")

        val otherSlug = admin.sql("select slug from tenant where id = :id").param("id", other).query(String::class.java).single()
        val taken = api.put(path, token("owner"), profile(slug = otherSlug))
        assertThat(taken.status).isEqualTo(409)
        assertThat(taken.body).doesNotContain("Barbearia do Zé")
    }

    @Test
    fun `support sees the business phone masked and the city in clear`() {
        api.put(path, token("owner"), profile())

        val read = api.get(path, supportToken()).node
        assertThat(read.path("phone").asString()).isEqualTo("**********44")
        assertThat(read.path("address").path("city").asString()).isEqualTo("Fortaleza")
    }

    @Test
    fun `split shift is saved and a day without intervals is closed`() {
        val week = mapOf("intervals" to listOf(
            mapOf("weekday" to 6, "start" to "08:00", "end" to "12:00"),
            mapOf("weekday" to 6, "start" to "14:00", "end" to "18:00"),
        ))

        val saved = api.put("$path/hours", token("owner"), week).node.path("intervals")

        assertThat(saved.size()).isEqualTo(2)
        assertThat(saved.toList().map { it.path("weekday").asInt() }).containsOnly(6)
    }

    @Test
    fun `overlapping or off-grid hours are rejected and the previous week is kept`() {
        api.put("$path/hours", token("owner"), mapOf("intervals" to listOf(mapOf("weekday" to 1, "start" to "09:00", "end" to "18:00"))))

        val overlap = api.put("$path/hours", token("owner"), mapOf("intervals" to listOf(
            mapOf("weekday" to 1, "start" to "09:00", "end" to "13:00"),
            mapOf("weekday" to 1, "start" to "12:00", "end" to "18:00"),
        )))
        val offGrid = api.put("$path/hours", token("owner"), mapOf("intervals" to listOf(mapOf("weekday" to 1, "start" to "09:10", "end" to "12:00"))))

        assertThat(overlap.status).isEqualTo(400)
        assertThat(offGrid.node.path("errors").path("intervals[0]").asString()).contains("15 em 15")
        val kept = api.get("$path/hours", token("owner")).node.path("intervals")
        assertThat(kept.size()).isEqualTo(1)
        assertThat(kept[0].path("start").asString()).isEqualTo("09:00")
    }

    @Test
    fun `the database itself refuses overlapping opening hours`() {
        admin.sql("insert into opening_hours (tenant_id, weekday, starts_minute, ends_minute) values (:t, 1, 540, 780)").param("t", salon).update()

        assertThatThrownBy {
            admin.sql("insert into opening_hours (tenant_id, weekday, starts_minute, ends_minute) values (:t, 1, 720, 1080)").param("t", salon).update()
        }.rootCause().hasMessageContaining("opening_hours_no_overlap")
    }
}
