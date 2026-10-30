package io.estilix.support

import io.estilix.audit.AuditAction
import io.estilix.audit.AuditLog
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.ActiveSupportAccess
import io.estilix.tenancy.BreakGlassLookup
import io.estilix.tenancy.TenantContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

enum class BreakGlassStatus { ACTIVE, ENDED, EXPIRED }

data class BreakGlassSession(
    val id: UUID,
    val supportUserId: UUID,
    val reason: String,
    val startedAt: Instant,
    val expiresAt: Instant,
    val endedAt: Instant?,
    val status: BreakGlassStatus,
    val unmaskedReads: Int,
)

/**
 * Break-glass access (spec identity/support-access, design D10). Every method runs in the current
 * salon's context; RLS keeps sessions of other salons invisible. Time is always the database's.
 */
@Service
class BreakGlassService(
    private val jdbc: JdbcClient,
    private val audit: AuditLog,
    transactionManager: PlatformTransactionManager,
    @Value("\${estilix.break-glass.max-minutes}") private val maxMinutes: Int,
    @Value("\${estilix.break-glass.min-reason-length}") private val minReasonLength: Int,
) : BreakGlassLookup {

    private val tx = TransactionTemplate(transactionManager)

    fun open(reason: String?, minutes: Int?): BreakGlassSession {
        val text = reason?.trim().orEmpty()
        val errors = buildMap {
            if (text.length < minReasonLength) put("reason", "descreva o motivo com pelo menos $minReasonLength caracteres")
            if (minutes == null || minutes !in 1..maxMinutes) put("minutes", "entre 1 e $maxMinutes minutos")
        }
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Pedido de acesso inválido", errors)
        return TenantContext.withWrites { tx.execute { openValidated(text, minutes!!) } }
    }

    private fun openValidated(reason: String, minutes: Int): BreakGlassSession {
        val supportUserId = TenantContext.current().userId!!
        // Expire-on-write: an old session past its time must not block the unique "one active" index.
        expireDue()
        val id = UUID.randomUUID()
        try {
            jdbc.sql(
                """
                insert into break_glass_session (id, tenant_id, support_user_id, reason, expires_at)
                values (:id, app_tenant_id(), :support, :reason, now() + make_interval(mins => :minutes))
                """.trimIndent(),
            ).param("id", id).param("support", supportUserId).param("reason", reason).param("minutes", minutes).update()
        } catch (_: DuplicateKeyException) {
            throw DomainException(ProblemType.CONFLICT, "Você já tem um acesso emergencial ativo neste salão")
        }
        TenantContext.runWith(TenantContext.current().copy(breakGlassSessionId = id)) {
            audit.record(AuditAction.BREAK_GLASS_OPENED, "break_glass_session", id, mapOf("minutes" to minutes))
        }
        return find(id)!!
    }

    fun end(sessionId: UUID) = TenantContext.withWrites { tx.executeWithoutResult { endValidated(sessionId) } }

    private fun endValidated(sessionId: UUID) {
        val updated = jdbc.sql(
            """
            update break_glass_session set status = 'ENDED', ended_at = now()
            where id = :id and support_user_id = :support and status = 'ACTIVE' and expires_at > now()
            """.trimIndent(),
        ).param("id", sessionId).param("support", TenantContext.current().userId).update()
        if (updated == 0) throw NotFoundException("Acesso emergencial ativo não encontrado")
        TenantContext.runWith(TenantContext.current().copy(breakGlassSessionId = sessionId)) {
            audit.record(AuditAction.BREAK_GLASS_ENDED, "break_glass_session", sessionId)
        }
    }

    /** Marks sessions past their time as EXPIRED and audits each one. Idempotent. */
    @Transactional
    fun expireDue(): Int {
        val expired = jdbc.sql(
            """
            update break_glass_session set status = 'EXPIRED', ended_at = expires_at
            where status = 'ACTIVE' and expires_at <= now()
            returning id
            """.trimIndent(),
        ).query(UUID::class.java).list()
        expired.forEach { id ->
            TenantContext.runWith(TenantContext.current().copy(breakGlassSessionId = id)) {
                audit.record(AuditAction.BREAK_GLASS_EXPIRED, "break_glass_session", id)
            }
        }
        return expired.size
    }

    @Transactional(readOnly = true)
    fun history(): List<BreakGlassSession> =
        jdbc.sql("$SELECT order by started_at desc limit 200").query(::map).list()

    @Transactional
    fun countUnmaskedRead(sessionId: UUID, resourceType: String, resourceId: String) {
        jdbc.sql("update break_glass_session set unmasked_reads = unmasked_reads + 1 where id = :id")
            .param("id", sessionId).update()
        audit.record(AuditAction.UNMASKED_READ, resourceType, resourceId)
    }

    override fun activeSessionOf(supportUserId: UUID): ActiveSupportAccess? =
        jdbc.sql("$ACTIVE and support_user_id = :support").param("support", supportUserId)
            .query(::active).optional().orElse(null)

    override fun anyActiveSession(): ActiveSupportAccess? =
        jdbc.sql("$ACTIVE order by expires_at desc limit 1").query(::active).optional().orElse(null)

    private fun find(id: UUID): BreakGlassSession? =
        jdbc.sql("$SELECT where id = :id").param("id", id).query(::map).optional().orElse(null)

    private fun active(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = ActiveSupportAccess(
        sessionId = rs.getObject("id", UUID::class.java),
        reason = rs.getString("reason"),
        expiresAt = rs.getTimestamp("expires_at").toInstant(),
    )

    private fun map(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = BreakGlassSession(
        id = rs.getObject("id", UUID::class.java),
        supportUserId = rs.getObject("support_user_id", UUID::class.java),
        reason = rs.getString("reason"),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        expiresAt = rs.getTimestamp("expires_at").toInstant(),
        endedAt = rs.getTimestamp("ended_at")?.toInstant(),
        status = BreakGlassStatus.valueOf(rs.getString("effective_status")),
        unmaskedReads = rs.getInt("unmasked_reads"),
    )

    private companion object {
        // A session past its time reads as EXPIRED even before the nightly job marks it.
        const val SELECT = """
            select id, support_user_id, reason, started_at, expires_at, ended_at, unmasked_reads,
                   case when status = 'ACTIVE' and expires_at <= now() then 'EXPIRED' else status end as effective_status
            from break_glass_session
        """
        const val ACTIVE = "select id, reason, expires_at from break_glass_session where status = 'ACTIVE' and expires_at > now()"
    }
}
