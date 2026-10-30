package io.estilix.salon

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Spec salon/service-catalog (task 3.2). */
class ServicesApiTest : IntegrationTest() {

    private lateinit var salon: UUID
    private lateinit var barbershop: UUID

    @BeforeEach
    fun data() {
        salon = fixtures.tenant("Salão", "SALON")
        barbershop = fixtures.tenant("Barbearia", "BARBERSHOP")
        fixtures.memberOf(salon, "owner", "OWNER")
        fixtures.memberOf(salon, "manager", "MANAGER")
        fixtures.memberOf(salon, "pro", "PROFESSIONAL")
        fixtures.memberOf(barbershop, "ze", "OWNER")
    }

    private fun services(tenant: UUID = salon) = "/api/v1/tenants/$tenant/services"

    private fun service(name: String = "Corte masculino", minutes: Int = 30, price: String = "45.00", type: String = "FIXED") =
        mapOf("name" to name, "category" to "Cabelo", "durationMinutes" to minutes, "price" to price, "priceType" to type)

    @Test
    fun `manager creates a service, prices travel as exact decimal strings with a pt-BR label`() {
        val created = api.post(services(), token("manager"), service())

        assertThat(created.status).isEqualTo(201)
        assertThat(created.node.path("price").asString()).isEqualTo("45.00")
        assertThat(created.node.path("priceLabel").asString()).isEqualTo("R$ 45,00")
        assertThat(created.node.path("active").asBoolean()).isTrue()
        assertThat(api.post(services(), token("manager"), service("Coloração", 120, "150", "FROM")).node.path("priceLabel").asString())
            .isEqualTo("a partir de R$ 150,00")
    }

    @Test
    fun `invalid duration and extra decimals are rejected, never rounded`() {
        assertThat(api.post(services(), token("owner"), service(minutes = 40)).node.path("errors").has("durationMinutes")).isTrue()
        assertThat(api.post(services(), token("owner"), service(price = "45.999")).node.path("errors").has("price")).isTrue()
    }

    @Test
    fun `duplicate name in the same salon conflicts, but another salon may use it`() {
        api.post(services(), token("owner"), service("Corte Masculino"))

        assertThat(api.post(services(), token("owner"), service("corte masculino")).status).isEqualTo(409)
        assertThat(api.post(services(barbershop), token("ze"), service("Corte masculino")).status).isEqualTo(201)
    }

    @Test
    fun `professional lists but cannot edit, other salons get not-found`() {
        val id = api.post(services(), token("owner"), service()).node.path("id").asString()

        assertThat(api.get(services(), token("pro")).node.size()).isEqualTo(1)
        assertThat(api.put("${services()}/$id", token("pro"), service(price = "50.00")).status).isEqualTo(403)
        assertThat(api.put("${services(barbershop)}/$id", token("ze"), service(price = "50.00")).status).isEqualTo(404)
    }

    @Test
    fun `deactivate and reactivate instead of delete`() {
        val id = api.post(services(), token("manager"), service("Pezinho", 15, "15.00")).node.path("id").asString()

        assertThat(api.post("${services()}/$id/deactivate", token("manager")).node.path("active").asBoolean()).isFalse()
        assertThat(api.get(services(), token("manager")).node[0].path("active").asBoolean()).isFalse()
        assertThat(api.post("${services()}/$id/activate", token("manager")).node.path("active").asBoolean()).isTrue()
    }

    @Test
    fun `reorder changes the display order`() {
        val a = api.post(services(), token("owner"), service("A")).node.path("id").asString()
        val b = api.post(services(), token("owner"), service("B")).node.path("id").asString()

        val list = api.put("${services()}/order", token("owner"), mapOf("ids" to listOf(b, a))).node

        assertThat(list.toList().map { it.path("name").asString() }).containsExactly("B", "A")
    }

    @Test
    fun `suggestions add only the presets the salon does not have`() {
        api.post(services(barbershop), token("ze"), service("barba", 30, "30.00"))

        val list = api.post("${services(barbershop)}/suggestions", token("ze")).node

        val names = list.toList().map { it.path("name").asString() }
        assertThat(names).contains("Corte masculino", "Corte + barba")
        assertThat(names.count { nameKey(it) == "barba" }).isEqualTo(1)
    }
}
