package io.estilix.audit

import io.estilix.tenancy.TenantContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Security-relevant actions (spec platform/audit-log). */
enum class AuditAction {
    USER_FIRST_SIGN_IN,
    MEMBERSHIP_ADDED,
    MEMBERSHIP_ROLE_CHANGED,
    MEMBERSHIP_REMOVED,
    BREAK_GLASS_OPENED,
    BREAK_GLASS_ENDED,
    BREAK_GLASS_EXPIRED,
    UNMASKED_READ,
    TENANT_SIGNED_UP,
    TENANT_RESUBMITTED,
    TENANT_APPROVED,
    TENANT_REJECTED,
    APPROVAL_QUEUE_VIEWED,
    BOOKING_CANCELLED_BY_STAFF,
    BOOKING_RESCHEDULED_BY_STAFF,
}

data class AuditEvent(
    val id: UUID,
    val occurredAt: Instant,
    val actorUserId: UUID?,
    val action: AuditAction,
    val targetType: String?,
    val targetId: String?,
    val details: Map<String, Any?>,
    val breakGlassSessionId: UUID?,
)

/**
 * Writes audit events in the caller's transaction (design D11): if the action rolls back, so does
 * its event. Details must hold ids, roles and counters only — never names, e-mails or phones.
 */
@Service
class AuditLog(private val jdbc: JdbcClient, private val json: JsonMapper) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        action: AuditAction,
        targetType: String? = null,
        targetId: Any? = null,
        details: Map<String, Any?> = emptyMap(),
        tenantId: UUID? = TenantContext.current().tenantId,
    ) {
        val ctx = TenantContext.current()
        jdbc.sql(
            """
            insert into audit_event (id, tenant_id, actor_user_id, action, target_type, target_id, details, break_glass_session_id)
            values (:id, :tenant, :actor, :action, :targetType, :targetId, cast(:details as jsonb), :session)
            """.trimIndent(),
        )
            .param("id", UUID.randomUUID())
            .param("tenant", tenantId)
            .param("actor", ctx.userId)
            .param("action", action.name)
            .param("targetType", targetType)
            .param("targetId", targetId?.toString())
            .param("details", json.writeValueAsString(details))
            .param("session", ctx.breakGlassSessionId)
            .update()
    }

    /** Newest first, one page. Which salon's events are visible is decided by RLS. */
    @Transactional(readOnly = true)
    fun page(page: Int, size: Int): List<AuditEvent> =
        jdbc.sql(
            """
            select id, occurred_at, actor_user_id, action, target_type, target_id, details::text as details, break_glass_session_id
            from audit_event
            where tenant_id = app_tenant_id()
            order by occurred_at desc, id
            limit :size offset :offset
            """.trimIndent(),
        )
            .param("size", size)
            .param("offset", page * size)
            .query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                AuditEvent(
                    id = rs.getObject("id", UUID::class.java),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                    actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                    action = AuditAction.valueOf(rs.getString("action")),
                    targetType = rs.getString("target_type"),
                    targetId = rs.getString("target_id"),
                    details = json.readValue(rs.getString("details"), Map::class.java) as Map<String, Any?>,
                    breakGlassSessionId = rs.getObject("break_glass_session_id", UUID::class.java),
                )
            }
            .list()
}
