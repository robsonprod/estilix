package io.estilix.support

import io.estilix.tenancy.RequestCompletionHook
import io.estilix.tenancy.RequestContext
import io.estilix.tenancy.TenantContext
import io.estilix.tenancy.TenantRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.HandlerMapping

/**
 * After a support request that showed personal data unmasked, writes one audit event and bumps the
 * session counter (spec "Unmasked read audited"). Runs after the body was serialized, because only
 * then do we know whether anything was unmasked.
 */
@Component
class UnmaskedReadAuditor(private val breakGlass: BreakGlassService) : RequestCompletionHook {

    override fun afterRequest(context: RequestContext, request: HttpServletRequest) {
        val sessionId = context.breakGlassSessionId ?: return
        if (!context.support || context.unmaskedReads.get() == 0) return
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: "unknown"
        TenantContext.runWith(context.copy(allowWrites = true)) {
            breakGlass.countUnmaskedRead(sessionId, "${request.method} $pattern", request.requestURI)
        }
    }
}

/**
 * Nightly bookkeeping: marks sessions past their time as EXPIRED and audits them. Correctness does
 * not depend on it (reads compare with `now()`); it only makes the history explicit.
 */
@Component
@EnableScheduling
class BreakGlassExpirationJob(
    private val tenants: TenantRepository,
    private val breakGlass: BreakGlassService,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Scheduled(cron = "\${estilix.break-glass.expire-cron}")
    fun run(): Int {
        // RLS is per salon: list salons as platform support, then expire inside each salon's context.
        val system = RequestContext(support = true, allowWrites = true)
        val ids = TenantContext.runWith(system) { readOnly.execute { tenants.findAll().map { it.id } } }
        val expired = ids.sumOf { id -> TenantContext.runWith(system.copy(tenantId = id)) { breakGlass.expireDue() } }
        if (expired > 0) log.info("Break-glass sessions expired: {}", expired)
        return expired
    }
}
