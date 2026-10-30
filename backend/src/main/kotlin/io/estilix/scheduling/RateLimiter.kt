package io.estilix.scheduling

import io.estilix.shared.DomainException
import io.estilix.shared.ProblemType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A limit: at most [max] events per [window]. */
data class Limit(val name: String, val max: Int, val window: Duration)

class RateLimitedException(val retryAfterSeconds: Long) :
    DomainException(ProblemType.RATE_LIMITED, "Muitas tentativas. Tente de novo em alguns minutos.")

/**
 * Fixed-window counters shared through Redis (`INCR` + `EXPIRE`), so every API instance sees the same
 * count (design D7). When Redis fails, a simple circuit breaker switches to in-memory counters for
 * 30 seconds: limits then apply per instance — weaker, but never "no limit", and booking keeps working.
 */
@Component
class RateLimiter(private val redis: StringRedisTemplate, private val clock: Clock) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val local = ConcurrentHashMap<String, Pair<Long, AtomicInteger>>()
    private val redisDownUntil = AtomicLong(0)

    /** Counts one event; throws [RateLimitedException] when the limit is exceeded. */
    fun hit(limit: Limit, subject: String) {
        val count = increment(limit, subject)
        if (count > limit.max) throw RateLimitedException(retryAfter(limit))
    }

    /** Throws when the limit is already reached, without counting (used before checking a code). */
    fun check(limit: Limit, subject: String) {
        if (current(limit, subject) >= limit.max) throw RateLimitedException(retryAfter(limit))
    }

    fun increment(limit: Limit, subject: String): Long {
        val key = key(limit, subject)
        if (clock.millis() >= redisDownUntil.get()) {
            try {
                val count = redis.opsForValue().increment(key) ?: 1
                if (count == 1L) redis.expire(key, limit.window)
                return count
            } catch (e: Exception) {
                redisDownUntil.set(clock.millis() + BREAKER_OPEN.toMillis())
                log.warn("Rate limiter store unavailable, using in-memory limits: {}", e.javaClass.simpleName)
            }
        }
        return localIncrement(key, limit).toLong()
    }

    private fun current(limit: Limit, subject: String): Long {
        val key = key(limit, subject)
        if (clock.millis() >= redisDownUntil.get()) {
            try {
                return redis.opsForValue().get(key)?.toLong() ?: 0
            } catch (e: Exception) {
                redisDownUntil.set(clock.millis() + BREAKER_OPEN.toMillis())
            }
        }
        return local[key]?.takeIf { it.first > clock.millis() }?.second?.get()?.toLong() ?: 0
    }

    private fun localIncrement(key: String, limit: Limit): Int {
        val now = clock.millis()
        val entry = local.compute(key) { _, old -> if (old == null || old.first <= now) (now + limit.window.toMillis()) to AtomicInteger() else old }!!
        if (local.size > 50_000) local.entries.removeIf { it.value.first <= now }
        return entry.second.incrementAndGet()
    }

    private fun key(limit: Limit, subject: String): String {
        val window = clock.millis() / limit.window.toMillis()
        return "rl:${limit.name}:$subject:$window"
    }

    private fun retryAfter(limit: Limit): Long {
        val ms = limit.window.toMillis()
        return (ms - clock.millis() % ms) / 1000 + 1
    }

    private companion object {
        val BREAKER_OPEN: Duration = Duration.ofSeconds(30)
    }
}

/** Configured limits (design D7); the test profile raises them so unrelated tests never trip. */
@Component
class PublicLimits(
    @Value("\${estilix.rate-limit.public-reads-per-minute}") reads: Int,
    @Value("\${estilix.rate-limit.public-writes-per-minute}") writes: Int,
    @Value("\${estilix.rate-limit.bookings-per-phone-per-hour}") perPhone: Int,
    @Value("\${estilix.rate-limit.code-failures-per-15-minutes}") codeFailures: Int,
) {
    val reads = Limit("pub-read", reads, Duration.ofMinutes(1))
    val writes = Limit("pub-write", writes, Duration.ofMinutes(1))
    val bookingsPerPhone = Limit("phone-book", perPhone, Duration.ofHours(1))
    val codeFailures = Limit("code-fail", codeFailures, Duration.ofMinutes(15))
}

/**
 * Per-IP limits on every public endpoint. The client IP is `remoteAddr`, which Tomcat fills from
 * `X-Forwarded-For` only when the request comes from a trusted internal proxy (our nginx / gateway).
 */
@Component
class PublicRateLimitInterceptor(private val limiter: RateLimiter, private val limits: PublicLimits) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val limit = if (request.method == "GET") limits.reads else limits.writes
        try {
            limiter.hit(limit, request.remoteAddr)
        } catch (e: RateLimitedException) {
            response.setHeader("Retry-After", e.retryAfterSeconds.toString())
            throw e
        }
        return true
    }
}

@Configuration
class PublicRateLimitConfiguration(private val interceptor: PublicRateLimitInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/public/**")
    }
}
