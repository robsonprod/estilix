package io.estilix.identity

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Spec identity/access-control and tenancy/tenant-isolation (API level). */
class AccessControlApiTest : IntegrationTest() {

    private lateinit var salon: UUID
    private lateinit var barbershop: UUID
    private lateinit var owner: UUID
    private lateinit var manager: UUID
    private lateinit var professional: UUID

    @BeforeEach
    fun data() {
        salon = fixtures.tenant("Salão Bela", "SALON")
        barbershop = fixtures.tenant("Barbearia do Zé", "BARBERSHOP")
        owner = fixtures.memberOf(salon, "owner", "OWNER", "Bela Dona")
        manager = fixtures.memberOf(salon, "manager", "MANAGER", "Gil Gerente")
        professional = fixtures.memberOf(salon, "pro", "PROFESSIONAL", "Paula Pro")
        fixtures.memberOf(barbershop, "ze", "OWNER", "Zé Barbeiro")
    }

    private fun members(tenant: UUID = salon) = "/api/v1/tenants/$tenant/members"

    @Test
    fun `me lists the user's salons with role and permissions`() {
        val me = api.get("/api/v1/me", token("owner")).node

        assertThat(me.path("platformSupport").asBoolean()).isFalse()
        val tenants = me.path("tenants")
        assertThat(tenants.size()).isEqualTo(1)
        assertThat(tenants[0].path("id").asString()).isEqualTo(salon.toString())
        assertThat(tenants[0].path("role").asString()).isEqualTo("OWNER")
        assertThat(tenants[0].path("permissions").toList().map { it.asString() }).contains("MANAGE_MEMBERS", "VIEW_AUDIT")
        assertThat(me.toString()).doesNotContain("Zé", "barbershop") // nothing about other users or salons
    }

    @Test
    fun `user without membership sees no salon and every tenant endpoint is not-found`() {
        val stranger = token("stranger")

        assertThat(api.get("/api/v1/me", stranger).node.path("tenants").size()).isZero()
        val response = api.get(members(), stranger)
        assertThat(response.status).isEqualTo(404)
        assertThat(response.code).isEqualTo("not-found")
    }

    @Test
    fun `member of one salon gets not-found on another salon`() {
        assertThat(api.get(members(barbershop), token("owner")).status).isEqualTo(404)
        assertThat(api.get(members(UUID.randomUUID()), token("owner")).status).isEqualTo(404)
    }

    @Test
    fun `members of the salon are listed with personal data in clear for the owner`() {
        val list = api.get(members(), token("owner")).node

        assertThat(list.toList().map { it.path("name").asString() }).containsExactlyInAnyOrder("Bela Dona", "Gil Gerente", "Paula Pro")
        assertThat(list.toList().map { it.path("email").asString() }).contains("owner@example.com")
    }

    @Test
    fun `owner of two salons sees only the members of the salon in the path`() {
        fixtures.member(barbershop, owner, "OWNER")

        val list = api.get(members(), token("owner")).node

        assertThat(list.size()).isEqualTo(3)
        assertThat(list.toList().map { it.path("name").asString() }).doesNotContain("Zé Barbeiro")
    }

    @Test
    fun `owner changes a role and it is audited`() {
        val response = api.put("${members()}/$manager", token("owner"), mapOf("role" to "PROFESSIONAL"))

        assertThat(response.status).isEqualTo(204)
        assertThat(admin.sql("select role from membership where user_id = :u").param("u", manager).query(String::class.java).single())
            .isEqualTo("PROFESSIONAL")
        assertThat(fixtures.auditActions(salon)).containsExactly("MEMBERSHIP_ROLE_CHANGED")
    }

    @Test
    fun `owner adds an existing user by e-mail, ignoring any tenant id in the body`() {
        val newcomer = fixtures.user("newcomer", email = "nova@example.com", name = "Nova Pessoa")

        val response = api.post(members(), token("owner"), mapOf("email" to "nova@example.com", "role" to "PROFESSIONAL", "tenantId" to barbershop))

        assertThat(response.status).isEqualTo(201)
        assertThat(admin.sql("select tenant_id from membership where user_id = :u").param("u", newcomer).query(UUID::class.java).list())
            .containsExactly(salon)
        assertThat(fixtures.auditActions(salon)).containsExactly("MEMBERSHIP_ADDED")
    }

    @Test
    fun `manager and professional cannot manage members`() {
        val body = mapOf("email" to "x@example.com", "role" to "PROFESSIONAL")

        assertThat(api.post(members(), token("manager"), body).status).isEqualTo(403)
        assertThat(api.post(members(), token("pro"), body).status).isEqualTo(403)
        assertThat(api.get(members(), token("pro")).status).isEqualTo(403)
        assertThat(api.delete("${members()}/$owner", token("manager")).code).isEqualTo("forbidden")
    }

    @Test
    fun `the last owner cannot remove or demote themself`() {
        val demote = api.put("${members()}/$owner", token("owner"), mapOf("role" to "MANAGER"))
        val remove = api.delete("${members()}/$owner", token("owner"))

        assertThat(demote.status).isEqualTo(409)
        assertThat(demote.code).isEqualTo("last-owner")
        assertThat(remove.code).isEqualTo("last-owner")
        assertThat(fixtures.auditActions(salon)).isEmpty()
    }

    @Test
    fun `with a second owner, an owner can step down`() {
        api.put("${members()}/$manager", token("owner"), mapOf("role" to "OWNER"))

        assertThat(api.put("${members()}/$owner", token("owner"), mapOf("role" to "MANAGER")).status).isEqualTo(204)
    }

    @Test
    fun `owner of another salon cannot change this salon's memberships`() {
        assertThat(api.put("${members()}/$manager", token("ze"), mapOf("role" to "OWNER")).status).isEqualTo(404)
    }

    @Test
    fun `nobody can grant the platform support role through the API`() {
        val response = api.put("${members()}/$manager", token("owner"), mapOf("role" to "PLATFORM_SUPPORT"))

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("validation")
    }

    @Test
    fun `members of a suspended salon get tenant-suspended and no data`() {
        admin.sql("update tenant set status = 'SUSPENDED' where id = :id").param("id", salon).update()

        val response = api.get(members(), token("owner"))

        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("tenant-suspended")
        assertThat(response.body).doesNotContain("Bela Dona")
    }
}
