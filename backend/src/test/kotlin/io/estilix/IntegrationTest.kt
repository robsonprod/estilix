package io.estilix

import io.estilix.tenancy.TenantContext
import no.nav.security.mock.oauth2.MockOAuth2Server
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.json.JsonMapper

/**
 * Base of the integration tests: real PostgreSQL 17 (never H2) and a real OIDC issuer
 * (mock-oauth2-server) that signs JWTs, both shared by the whole suite.
 *
 * The application connects as `estilix_app` (subject to RLS); Flyway runs as `estilix_owner`.
 * Fixtures and cleanup use [admin], the container's superuser, which bypasses RLS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTest {

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var json: JsonMapper

    protected val api: Api by lazy { Api("http://localhost:$port", json) }
    protected val admin: JdbcClient get() = ADMIN
    protected val fixtures: Fixtures get() = Fixtures(ADMIN)

    @BeforeEach
    fun cleanDatabase() {
        Fixtures(ADMIN).truncateAll()
    }

    @AfterEach
    fun clearContext() {
        TenantContext.clear()
    }

    /** Signed access token as the web app would send it. */
    protected fun token(
        subject: String,
        email: String? = "$subject@example.com",
        name: String? = null,
        groups: List<String> = emptyList(),
        audience: String = CLIENT_ID,
        expirySeconds: Long = 3600,
        issuer: String = "default",
    ): String {
        val claims = buildMap<String, Any> {
            email?.let { put("email", it) }
            name?.let { put("name", it) }
            if (groups.isNotEmpty()) put("cognito:groups", groups)
        }
        return OAUTH.issueToken(issuer, subject, audience, claims, expirySeconds).serialize()
    }

    protected fun supportToken(subject: String = "support-1") = token(subject, groups = listOf("platform-support"))

    companion object {
        const val CLIENT_ID = "estilix-web"

        val POSTGRES: PostgreSQLContainer = PostgreSQLContainer("postgres:17")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("db/init/01-roles.sql"),
                "/docker-entrypoint-initdb.d/01-roles.sql",
            )

        val OAUTH = MockOAuth2Server()

        val ADMIN: JdbcClient by lazy {
            JdbcClient.create(DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password))
        }

        init {
            POSTGRES.start()
            OAUTH.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl)
            registry.add("spring.flyway.url", POSTGRES::getJdbcUrl)
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { OAUTH.issuerUrl("default").toString() }
            registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri") { OAUTH.jwksUrl("default").toString() }
            registry.add("estilix.security.client-id") { CLIENT_ID }
        }
    }
}
