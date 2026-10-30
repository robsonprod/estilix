package io.estilix.scheduling

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/**
 * Tasks 5.1 and 5.2 — per-IP limits on public endpoints, shared through Redis, and the in-memory
 * fallback when Redis is unreachable. Small limits here; the rest of the suite runs with high ones.
 */
class RateLimitApiTest {

    abstract class Base : IntegrationTest() {
        protected lateinit var s: SchedulingSetup

        @BeforeEach
        fun data() {
            s = SchedulingSetup(admin, fixtures)
        }

        /** Requests from the trusted local proxy may carry the client address (as our nginx does). */
        protected fun burst(ip: String, times: Int) = (1..times).map {
            api.get("/api/v1/public/salons/${s.slug}", headers = mapOf("X-Forwarded-For" to ip))
        }
    }

    @Nested
    @TestPropertySource(properties = ["estilix.rate-limit.public-reads-per-minute=10"])
    inner class WithRedis : Base() {
        @Test
        fun `a burst from one IP gets 429 with Retry-After, other IPs are not affected`() {
            val responses = burst("203.0.113.7", 15)

            assertThat(responses.take(10).map { it.status }).containsOnly(200)
            assertThat(responses.drop(10).map { it.status }).containsOnly(429)
            assertThat(responses.last().header("Retry-After")!!.toLong()).isBetween(1, 61)
            assertThat(responses.last().code).isEqualTo("rate-limited")
            assertThat(burst("203.0.113.8", 1).single().status).isEqualTo(200)
        }

        @Test
        fun `the counter lives in Redis, shared by every instance`() {
            burst("203.0.113.9", 3)

            val keys = REDIS.execInContainer("redis-cli", "--scan", "--pattern", "rl:pub-read:203.0.113.9:*").stdout
            assertThat(keys).contains("rl:pub-read:203.0.113.9:")
        }
    }

    @Nested
    @TestPropertySource(properties = ["estilix.rate-limit.public-reads-per-minute=10", "spring.data.redis.port=1"])
    inner class RedisDown : Base() {
        @Test
        fun `booking keeps working and limits still apply per instance`() {
            val booking = api.post(
                "/api/v1/public/salons/${s.slug}/bookings", null,
                mapOf("serviceId" to s.corte, "professionalId" to s.paula, "date" to s.dayAfterTomorrow(), "time" to "15:00", "name" to "Ana", "phone" to "(11) 98888-7777"),
            )
            assertThat(booking.status).isEqualTo(201)

            val responses = burst("198.51.100.4", 12)
            assertThat(responses.take(10).map { it.status }).containsOnly(200)
            assertThat(responses.drop(10).map { it.status }).containsOnly(429)
        }

        @Test
        fun `readiness stays UP without Redis`() {
            assertThat(api.get("/actuator/health/readiness").status).isEqualTo(200)
        }
    }
}
