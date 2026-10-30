package io.estilix.identity

import io.estilix.tenancy.RequestCompletionHook
import io.estilix.tenancy.RequestContext
import io.estilix.tenancy.TenantContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Runs after the JWT was validated: links the token subject to an internal user (provisioning it on
 * the first request) and opens the [RequestContext] of this request. Always clears it at the end.
 * Registered only inside the security chain (see [SecurityConfiguration]).
 */
@Component
class CurrentUserFilter(
    private val provisioning: UserProvisioning,
    private val completionHooks: List<RequestCompletionHook>,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val auth = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        if (auth == null) {
            chain.doFilter(request, response)
            return
        }
        val jwt = auth.token
        val identity = TokenIdentity(
            subject = jwt.subject ?: error("token without subject"),
            email = jwt.getClaimAsString("email"),
            name = jwt.getClaimAsString("name"),
        )
        val support = auth.authorities.any { it.authority == "ROLE_PLATFORM_SUPPORT" }
        try {
            val userId = TenantContext.runWith(RequestContext(subject = identity.subject)) { provisioning.resolve(identity) }
            TenantContext.set(RequestContext(subject = identity.subject, userId = userId, support = support))
            MDC.put("user_id", userId.toString())
            chain.doFilter(request, response)
            val finalContext = TenantContext.current()
            completionHooks.forEach { hook ->
                runCatching { hook.afterRequest(finalContext, request) }
                    .onFailure { log.error("Request completion hook failed: {}", it.javaClass.name, it) }
            }
        } finally {
            TenantContext.clear()
            MDC.remove("user_id")
            MDC.remove("tenant_id")
        }
    }
}
