package io.estilix.identity

import io.estilix.IntegrationTest
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Spec identity/access-control: "Authenticated API" and "Users are provisioned on first sign-in". */
class AuthenticationApiTest : IntegrationTest() {

    @Test
    fun `missing token gets 401 with a problem body and no data`() {
        val response = api.get("/api/v1/me")

        assertThat(response.status).isEqualTo(401)
        assertThat(response.header("Content-Type")).contains("application/problem+json")
        assertThat(response.code).isEqualTo("unauthorized")
        assertThat(response.node.has("userId")).isFalse()
    }

    @Test
    fun `expired token gets 401`() {
        assertThat(api.get("/api/v1/me", token("ana", expirySeconds = -3600)).status).isEqualTo(401)
    }

    @Test
    fun `token from another issuer gets 401`() {
        assertThat(api.get("/api/v1/me", token("ana", issuer = "another-issuer")).status).isEqualTo(401)
    }

    @Test
    fun `token issued for another application gets 401`() {
        assertThat(api.get("/api/v1/me", token("ana", audience = "some-other-app")).status).isEqualTo(401)
    }

    @Test
    fun `token naming the app as authorized party (azp) is accepted`() {
        // Same shape as the browser login against mock-oauth2-server: aud = "default", azp = client id.
        val callback = DefaultOAuth2TokenCallback(issuerId = "default", subject = "ana", audience = listOf("default"))
        val bearer = OAUTH.issueToken("default", CLIENT_ID, callback).serialize()

        assertThat(api.get("/api/v1/me", bearer).status).isEqualTo(200)
    }

    @Test
    fun `cognito-style access token with client_id is accepted`() {
        val bearer = OAUTH.issueToken("default", "ana", "other", mapOf("client_id" to CLIENT_ID), 3600).serialize()

        assertThat(api.get("/api/v1/me", bearer).status).isEqualTo(200)
    }

    @Test
    fun `health check is public and shows the status only`() {
        val response = api.get("/actuator/health")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.node.path("status").asString()).isEqualTo("UP")
        assertThat(response.node.has("components")).isFalse()
    }

    @Test
    fun `first request provisions an internal user and audits it`() {
        val response = api.get("/api/v1/me", token("new-person", name = "Nova Pessoa"))

        assertThat(response.status).isEqualTo(200)
        val users = admin.sql("select count(*) from app_user where subject = 'new-person'").query(Long::class.java).single()
        assertThat(users).isEqualTo(1)
        assertThat(fixtures.auditActions(null)).containsExactly("USER_FIRST_SIGN_IN")

        api.get("/api/v1/me", token("new-person"))
        assertThat(fixtures.auditActions(null)).hasSize(1) // second request: no new user, no new event
    }

    @Test
    fun `concurrent first requests create exactly one user`() {
        val bearer = token("racer")
        val start = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val calls = (1..10).map { pool.submit<Int> { start.await(); api.get("/api/v1/me", bearer).status } }
            start.countDown()
            assertThat(calls.map { it.get() }).containsOnly(200)
        }
        val users = admin.sql("select count(*) from app_user where subject = 'racer'").query(Long::class.java).single()
        assertThat(users).isEqualTo(1)
    }
}
