package io.estilix.shared.web

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Spec platform/operational-health. */
class HealthTest : IntegrationTest() {

    private fun status(path: String) = api.get(path).let { it.status to it.node.path("status").asString() }

    @Test
    fun `liveness and readiness are UP with a healthy database`() {
        assertThat(status("/actuator/health/liveness")).isEqualTo(200 to "UP")
        assertThat(status("/actuator/health/readiness")).isEqualTo(200 to "UP")
    }

    @Test
    fun `database down makes readiness fail while liveness stays UP, and it recovers without restart`() {
        val docker = POSTGRES.dockerClient
        docker.pauseContainerCmd(POSTGRES.containerId).exec()
        try {
            assertThat(status("/actuator/health/readiness")).isEqualTo(503 to "DOWN")
            assertThat(status("/actuator/health/liveness")).isEqualTo(200 to "UP")
        } finally {
            docker.unpauseContainerCmd(POSTGRES.containerId).exec()
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (status("/actuator/health/readiness").first != 200 && System.currentTimeMillis() < deadline) Thread.sleep(250)
        assertThat(status("/actuator/health/readiness")).isEqualTo(200 to "UP")
    }

    @Test
    fun `anonymous callers get no component details`() {
        val body = api.get("/actuator/health/readiness").body

        assertThat(body).doesNotContain("components", "postgres", "PostgreSQL", "database")
    }

    @Test
    fun `platform support sees the components`() {
        assertThat(api.get("/actuator/health/readiness", supportToken()).node.has("components")).isTrue()
    }
}
