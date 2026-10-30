package io.estilix.identity

import io.estilix.shared.DomainException
import io.estilix.shared.ForbiddenException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.BreakGlassLookup
import io.estilix.tenancy.TenantContext
import io.estilix.tenancy.TenantRepository
import io.estilix.tenancy.TenantStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

/**
 * Resolves the salon of `/api/v1/tenants/{tenantId}/...` and `/api/v1/support/tenants/{tenantId}/...`
 * (design D7) and puts it in the request context:
 * - member: tenant + role; suspended salon -> `tenant-suspended`;
 * - platform support: tenant (any salon) + active break-glass session, if any;
 * - anyone else: 404, so salon ids cannot be probed.
 */
@Component
class TenantAccessInterceptor(
    private val tenants: TenantRepository,
    private val memberships: MembershipRepository,
    private val breakGlass: BreakGlassLookup,
    transactionManager: PlatformTransactionManager,
) : HandlerInterceptor {

    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        @Suppress("UNCHECKED_CAST")
        val vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        val raw = vars?.get("tenantId") ?: return true
        val tenantId = runCatching { UUID.fromString(raw) }.getOrNull() ?: throw NotFoundException("Salão não encontrado")
        val ctx = TenantContext.current()
        val userId = ctx.userId ?: throw ForbiddenException()
        val supportPath = request.requestURI.startsWith("/api/v1/support/")

        if (ctx.support) {
            val session = TenantContext.runWith(ctx.copy(tenantId = tenantId)) {
                readOnly.execute {
                    tenants.find(tenantId) ?: throw NotFoundException("Salão não encontrado")
                    breakGlass.activeSessionOf(userId)
                }
            }
            TenantContext.set(ctx.copy(tenantId = tenantId, breakGlassSessionId = session?.sessionId))
        } else {
            if (supportPath) throw ForbiddenException()
            val (status, role) = readOnly.execute {
                val tenant = tenants.find(tenantId)
                val role = tenant?.let { memberships.roleOfCurrentUserIn(tenantId) }
                tenant?.status to role
            }
            if (status == null || role == null) throw NotFoundException("Salão não encontrado")
            when (status) {
                TenantStatus.ACTIVE -> Unit
                TenantStatus.SUSPENDED ->
                    throw DomainException(ProblemType.TENANT_SUSPENDED, "Fale com o suporte para reativar o salão")
                TenantStatus.PENDING_APPROVAL ->
                    throw DomainException(ProblemType.TENANT_PENDING_APPROVAL, "O suporte do Estilix está conferindo os dados do salão")
                TenantStatus.REJECTED ->
                    throw DomainException(ProblemType.TENANT_REJECTED, "Veja o motivo, corrija os dados e reenvie")
            }
            TenantContext.set(ctx.copy(tenantId = tenantId, role = role))
        }
        MDC.put("tenant_id", tenantId.toString())
        return true
    }
}

@Configuration
class TenantAccessWebConfiguration(private val interceptor: TenantAccessInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/tenants/*/**", "/api/v1/support/tenants/*/**")
    }

    /** [CurrentUserFilter] lives in the security chain only; stop Boot from also adding it as a plain filter. */
    @Bean
    fun currentUserFilterRegistration(filter: CurrentUserFilter) =
        FilterRegistrationBean(filter).apply { isEnabled = false }
}
