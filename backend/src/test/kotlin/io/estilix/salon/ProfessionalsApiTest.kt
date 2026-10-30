package io.estilix.salon

import io.estilix.IntegrationTest
import io.estilix.tenancy.RequestContext
import io.estilix.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Spec salon/professionals (tasks 4.1–4.3). */
class ProfessionalsApiTest : IntegrationTest() {

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private lateinit var salon: UUID
    private lateinit var other: UUID
    private lateinit var paulaUser: UUID
    private lateinit var corte: String
    private lateinit var barba: String

    @BeforeEach
    fun data() {
        salon = fixtures.tenant("Salão")
        other = fixtures.tenant("Outro")
        fixtures.memberOf(salon, "owner", "OWNER")
        fixtures.memberOf(salon, "manager", "MANAGER")
        paulaUser = fixtures.memberOf(salon, "paula", "PROFESSIONAL", "Paula Rocha")
        fixtures.memberOf(other, "ze", "OWNER")
        corte = createService(salon, "owner", "Corte masculino")
        barba = createService(salon, "owner", "Barba")
        api.put("/api/v1/tenants/$salon/salon/hours", token("owner"), mapOf("intervals" to listOf(
            mapOf("weekday" to 2, "start" to "09:00", "end" to "19:00"),
            mapOf("weekday" to 6, "start" to "08:00", "end" to "18:00"),
        )))
    }

    private fun createService(tenant: UUID, subject: String, name: String) =
        api.post("/api/v1/tenants/$tenant/services", token(subject),
            mapOf("name" to name, "category" to "Cabelo", "durationMinutes" to 30, "price" to "40.00", "priceType" to "FIXED"))
            .node.path("id").asString()

    private fun pros(tenant: UUID = salon) = "/api/v1/tenants/$tenant/professionals"
    private fun create(name: String, userId: UUID? = null, subject: String = "manager") =
        api.post(pros(), token(subject), mapOf("displayName" to name, "userId" to userId))

    @Test
    fun `a professional does not need a login`() {
        val caio = create("Caio")

        assertThat(caio.status).isEqualTo(201)
        assertThat(caio.node.path("userId").isNull).isTrue()
        assertThat(caio.node.path("color").asString()).matches("#[0-9a-f]{6}")
    }

    @Test
    fun `link to a member, only once, and never to a stranger`() {
        assertThat(create("Paula", paulaUser).node.path("userId").asString()).isEqualTo(paulaUser.toString())
        assertThat(create("Paula de novo", paulaUser).status).isEqualTo(409)

        val stranger = fixtures.user("stranger")
        assertThat(create("Estranho", stranger).node.path("errors").has("userId")).isTrue()
    }

    @Test
    fun `the linked member sees their professional profile in me`() {
        create("Paula", paulaUser)

        val tenant = api.get("/api/v1/me", token("paula")).node.path("tenants")[0]
        assertThat(tenant.path("professional").path("displayName").asString()).isEqualTo("Paula")
    }

    @Test
    fun `professional cannot add colleagues and other salons get not-found`() {
        assertThat(create("Novo", subject = "paula").status).isEqualTo(403)
        val id = create("Caio").node.path("id").asString()
        assertThat(api.put("${pros(other)}/$id", token("ze"), mapOf("displayName" to "X")).status).isEqualTo(404)
    }

    @Test
    fun `support sees names masked`() {
        create("Caio Lima")

        val list = api.get(pros(), supportToken()).node
        assertThat(list[0].path("displayName").asString()).isEqualTo("C*** L***")
    }

    @Test
    fun `services performed are replaced as a set and cannot come from another salon`() {
        val id = create("Caio").node.path("id").asString()

        val set = api.put("${pros()}/$id/services", token("manager"), mapOf("serviceIds" to listOf(corte, barba)))
        assertThat(set.node.path("serviceIds").toList().map { it.asString() }).containsExactlyInAnyOrder(corte, barba)

        val foreign = createService(other, "ze", "Corte")
        val refused = api.put("${pros()}/$id/services", token("manager"), mapOf("serviceIds" to listOf(corte, foreign)))
        assertThat(refused.status).isEqualTo(400)
        assertThat(api.get(pros(), token("manager")).node[0].path("serviceIds").size()).isEqualTo(2)
    }

    @Test
    fun `a deactivated service disappears from the professional and comes back when reactivated`() {
        val id = create("Caio").node.path("id").asString()
        api.put("${pros()}/$id/services", token("manager"), mapOf("serviceIds" to listOf(corte, barba)))

        api.post("/api/v1/tenants/$salon/services/$barba/deactivate", token("manager"))
        assertThat(api.get(pros(), token("manager")).node[0].path("serviceIds").size()).isEqualTo(1)

        api.post("/api/v1/tenants/$salon/services/$barba/activate", token("manager"))
        assertThat(api.get(pros(), token("manager")).node[0].path("serviceIds").size()).isEqualTo(2)
    }

    @Test
    fun `the composite key refuses a cross-salon link even inside the right salon's context`() {
        val id = UUID.fromString(create("Caio").node.path("id").asString())
        val foreign = UUID.fromString(createService(other, "ze", "Corte"))

        assertThatThrownBy {
            TenantContext.runWith(RequestContext(tenantId = salon)) {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbc.sql("insert into professional_service (tenant_id, professional_id, service_id) values (:t, :p, :s)")
                        .param("t", salon).param("p", id).param("s", foreign).update()
                }
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `hours default to the salon's and follow its changes`() {
        val id = create("Caio").node.path("id").asString()

        val hours = api.get("${pros()}/$id/hours", token("manager")).node
        assertThat(hours.path("ownHours").asBoolean()).isFalse()
        assertThat(hours.path("effective").size()).isEqualTo(2)

        api.put("/api/v1/tenants/$salon/salon/hours", token("owner"), mapOf("intervals" to listOf(mapOf("weekday" to 2, "start" to "10:00", "end" to "16:00"))))
        assertThat(api.get("${pros()}/$id/hours", token("manager")).node.path("effective")[0].path("start").asString()).isEqualTo("10:00")
    }

    @Test
    fun `own hours must lie within the salon's`() {
        val id = create("Paula").node.path("id").asString()

        val own = api.put("${pros()}/$id/hours", token("manager"), mapOf("ownHours" to true, "intervals" to listOf(mapOf("weekday" to 2, "start" to "13:00", "end" to "19:00"))))
        assertThat(own.node.path("effective").toList().map { it.path("start").asString() }).containsExactly("13:00")

        val sunday = api.put("${pros()}/$id/hours", token("manager"), mapOf("ownHours" to true, "intervals" to listOf(mapOf("weekday" to 7, "start" to "09:00", "end" to "12:00"))))
        assertThat(sunday.status).isEqualTo(400)
        assertThat(sunday.node.path("errors").path("intervals[0]").asString()).contains("fora do horário")
    }

    @Test
    fun `when the salon shrinks, own hours are clipped and the conflict is listed`() {
        val id = create("Paula").node.path("id").asString()
        api.put("${pros()}/$id/hours", token("manager"), mapOf("ownHours" to true, "intervals" to listOf(mapOf("weekday" to 6, "start" to "13:00", "end" to "18:00"))))

        api.put("/api/v1/tenants/$salon/salon/hours", token("owner"), mapOf("intervals" to listOf(mapOf("weekday" to 6, "start" to "08:00", "end" to "12:00"))))

        val hours = api.get("${pros()}/$id/hours", token("manager")).node
        assertThat(hours.path("effective").size()).isZero()
        assertThat(hours.path("conflicts").size()).isEqualTo(1)
    }
}
