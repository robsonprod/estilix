package io.estilix.privacy

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.util.UUID

/**
 * Spec platform/personal-data-protection: "No personal data in logs" and "No personal data in error
 * responses". Each test submits known personal values and then searches every captured log line and
 * the response body for them. Reusable recipe for every future change that accepts personal data.
 */
@ExtendWith(OutputCaptureExtension::class)
class LogHygieneTest : IntegrationTest() {

    private val name = "Ana Paula Souza"
    private val badPhone = "85-9-NOT-A-PHONE"
    private val badEmail = "ana.souza@@gmail"
    private lateinit var salon: UUID

    @BeforeEach
    fun data() {
        salon = fixtures.tenant()
        fixtures.memberOf(salon, "owner", "OWNER")
    }

    private fun assertClean(output: CapturedOutput, body: String, vararg secrets: String) {
        secrets.forEach {
            assertThat(output.all).`as`("log output").doesNotContain(it)
            assertThat(body).`as`("response body").doesNotContain(it)
        }
    }

    @Test
    fun `validation failure on personal data leaks nothing`(output: CapturedOutput) {
        val response = api.put("/api/v1/me/profile", token("owner"), mapOf("name" to name, "phone" to badPhone))

        assertThat(response.status).isEqualTo(400)
        assertClean(output, response.body, name, badPhone)
    }

    @Test
    fun `validation error body names the field and the rule, not the value`(output: CapturedOutput) {
        val response = api.put("/api/v1/me/profile", token("owner"), mapOf("name" to name, "phone" to badPhone))

        assertThat(response.node.path("errors").path("phone").asString()).isEqualTo("telefone inválido")
        assertThat(response.node.path("correlationId").asString()).isNotBlank()
    }

    @Test
    fun `invalid e-mail when adding a member leaks nothing`(output: CapturedOutput) {
        val response = api.post("/api/v1/tenants/$salon/members", token("owner"), mapOf("email" to badEmail, "role" to "MANAGER"))

        assertThat(response.status).isEqualTo(400)
        assertClean(output, response.body, badEmail, "ana.souza")
    }

    @Test
    fun `malformed body is not echoed`(output: CapturedOutput) {
        val response = api.put("/api/v1/me/profile", token("owner"), """{"name": "$name", "phone": [1, 2""")

        assertThat(response.status).isEqualTo(400)
        assertClean(output, response.body, name, "Souza")
    }

    @Test
    fun `unexpected error logs type and correlation id but no body values`(output: CapturedOutput) {
        val response = api.post(
            "/api/v1/test/boom",
            token("owner"),
            mapOf("name" to name, "email" to "ana.souza@gmail.com", "phone" to "+55 85 99876-5432"),
        )

        assertThat(response.status).isEqualTo(500)
        assertThat(output.all).contains("java.lang.IllegalStateException", response.node.path("correlationId").asString())
        assertClean(output, response.body, name, "ana.souza@gmail.com", "99876-5432")
    }

    @Test
    fun `successful requests with personal data log nothing about it`(output: CapturedOutput) {
        val response = api.put("/api/v1/me/profile", token("owner"), mapOf("name" to name, "phone" to "+55 85 99876-5432"))

        assertThat(response.status).isEqualTo(204)
        assertClean(output, "", name, "99876")
    }
}
