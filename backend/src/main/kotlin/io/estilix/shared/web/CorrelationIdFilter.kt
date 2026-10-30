package io.estilix.shared.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Gives every request a correlation id: the incoming `X-Request-Id` when it is safe, or a new one.
 * It goes to the MDC (every log line) and back in the response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val incoming = request.getHeader(HEADER)
        // Accepting any value would let a client inject fake lines into the logs (log forging).
        val correlationId = if (incoming != null && SAFE.matches(incoming)) incoming else UUID.randomUUID().toString()
        MDC.put(MDC_KEY, correlationId)
        response.setHeader(HEADER, correlationId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "correlation_id"
        private val SAFE = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
