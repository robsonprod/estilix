package io.estilix.tenancy

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Modified requirement "Tenant lifecycle status" (salon-setup). */
class TenantStatusApiTest : IntegrationTest() {

    private fun membersAs(status: String, subject: String = "owner"): io.estilix.Api.Response {
        val tenant = fixtures.tenant(status = status)
        fixtures.memberOf(tenant, subject, "OWNER", "Dona Teste")
        return api.get("/api/v1/tenants/$tenant/members", token(subject))
    }

    @Test
    fun `pending salon gives tenant-pending-approval and no data`() {
        val response = membersAs("PENDING_APPROVAL")

        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("tenant-pending-approval")
        assertThat(response.body).doesNotContain("Dona Teste")
    }

    @Test
    fun `rejected salon gives tenant-rejected`() {
        assertThat(membersAs("REJECTED").code).isEqualTo("tenant-rejected")
    }

    @Test
    fun `suspended salon still gives tenant-suspended`() {
        assertThat(membersAs("SUSPENDED").code).isEqualTo("tenant-suspended")
    }

    @Test
    fun `support still reads a pending salon, masked`() {
        val tenant = fixtures.tenant(status = "PENDING_APPROVAL")
        fixtures.memberOf(tenant, "owner", "OWNER", "Dona Teste")

        val response = api.get("/api/v1/tenants/$tenant/members", supportToken())

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).doesNotContain("Dona Teste")
    }
}
