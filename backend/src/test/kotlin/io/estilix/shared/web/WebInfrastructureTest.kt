package io.estilix.shared.web

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.util.UUID

/** Tasks 2.1 and 2.2: Problem Details shape and the correlation id. */
@ExtendWith(OutputCaptureExtension::class)
class WebInfrastructureTest : IntegrationTest() {

    @Test
    fun `problem details carry a stable type, a pt-BR title, the code and the correlation id`() {
        val response = api.get("/api/v1/tenants/${UUID.randomUUID()}/members", token("someone"))

        assertThat(response.status).isEqualTo(404)
        assertThat(response.node.path("type").asString()).isEqualTo("https://estilix.io/problems/not-found")
        assertThat(response.node.path("title").asString()).isEqualTo("Não encontrado")
        assertThat(response.node.path("code").asString()).isEqualTo("not-found")
        assertThat(response.node.path("correlationId").asString()).isEqualTo(response.header("X-Request-Id"))
    }

    @Test
    fun `an invalid tenant id in the path is a not-found, not a server error`() {
        assertThat(api.get("/api/v1/tenants/not-a-uuid/members", token("someone")).status).isEqualTo(404)
    }

    @Test
    fun `a safe incoming request id is reused`() {
        val response = api.get("/actuator/health", headers = mapOf("X-Request-Id" to "abc-123"))

        assertThat(response.header("X-Request-Id")).isEqualTo("abc-123")
    }

    @Test
    fun `a request id is generated when absent`() {
        assertThat(UUID.fromString(api.get("/actuator/health").header("X-Request-Id"))).isNotNull()
    }

    @Test
    fun `a log-forging attempt is replaced by a generated id`(output: CapturedOutput) {
        val forged = "x\" level=ERROR msg=fake-entry"

        val response = api.get("/api/v1/me", headers = mapOf("X-Request-Id" to forged))

        assertThat(response.header("X-Request-Id")).doesNotContain("fake-entry")
        assertThat(output.all).doesNotContain("fake-entry")
    }
}
