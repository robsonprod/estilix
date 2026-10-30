package io.estilix.onboarding

import io.estilix.audit.AuditAction
import io.estilix.audit.AuditLog
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.EstablishmentType
import io.estilix.tenancy.TenantContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * A salon waiting for approval as platform support sees it. Contact fields are plain strings on
 * purpose (design D3): support needs them unmasked to verify the salon before the contract, and
 * this view only ever lists PENDING_APPROVAL salons. Everywhere else the same data is masked.
 */
data class PendingSalon(
    val tenantId: UUID,
    val name: String,
    val establishmentType: EstablishmentType,
    val city: String?,
    val state: String?,
    val phone: String?,
    val contactName: String?,
    val contactEmail: String?,
    val planCode: String?,
    val termsVersion: String?,
    val termsAcceptedAt: Instant?,
    val submittedAt: Instant?,
)

@Service
class ApprovalService(
    private val jdbc: JdbcClient,
    private val audit: AuditLog,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    /** Oldest first; viewing the queue is audited because it shows contact data unmasked. */
    fun queue(): List<PendingSalon> = TenantContext.withWrites {
        tx.execute {
            val pending = jdbc.sql(
                """
                select id, name, establishment_type, address_city, address_state, phone, contact_name, contact_email,
                       plan_code, terms_version, terms_accepted_at, submitted_at
                from tenant where status = 'PENDING_APPROVAL'
                order by submitted_at nulls last, created_at
                """.trimIndent(),
            ).query { rs, _ ->
                PendingSalon(
                    tenantId = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    establishmentType = EstablishmentType.valueOf(rs.getString("establishment_type")),
                    city = rs.getString("address_city"),
                    state = rs.getString("address_state"),
                    phone = rs.getString("phone"),
                    contactName = rs.getString("contact_name"),
                    contactEmail = rs.getString("contact_email"),
                    planCode = rs.getString("plan_code"),
                    termsVersion = rs.getString("terms_version"),
                    termsAcceptedAt = rs.getTimestamp("terms_accepted_at")?.toInstant(),
                    submittedAt = rs.getTimestamp("submitted_at")?.toInstant(),
                )
            }.list()
            audit.record(AuditAction.APPROVAL_QUEUE_VIEWED, details = mapOf("count" to pending.size), tenantId = null)
            pending
        }!!
    }

    fun approve(tenantId: UUID) = decide(tenantId, AuditAction.TENANT_APPROVED) {
        // Contact snapshot was only for verification: cleared once the salon is live (data minimization).
        jdbc.sql(
            """
            update tenant set status = 'ACTIVE', decided_by = app_user_id(), decided_at = now(),
                              contact_name = null, contact_email = null
            where id = app_tenant_id() and status = 'PENDING_APPROVAL'
            """.trimIndent(),
        ).update()
    }

    fun reject(tenantId: UUID, reason: String?) {
        val text = reason?.trim().orEmpty()
        if (text.length !in 10..500) {
            throw DomainException(ProblemType.VALIDATION, "Explique o motivo", mapOf("reason" to "use de 10 a 500 caracteres"))
        }
        decide(tenantId, AuditAction.TENANT_REJECTED) {
            jdbc.sql(
                """
                update tenant set status = 'REJECTED', decided_by = app_user_id(), decided_at = now(), rejection_reason = :reason
                where id = app_tenant_id() and status = 'PENDING_APPROVAL'
                """.trimIndent(),
            ).param("reason", text).update()
        }
    }

    /**
     * Conditional UPDATE (design D1): when two support users decide at once, the second waits for the
     * first's row lock, re-reads the status and matches zero rows -> conflict, nothing applied twice.
     */
    private fun decide(tenantId: UUID, action: AuditAction, update: () -> Int) {
        TenantContext.runWith(TenantContext.current().copy(tenantId = tenantId, allowWrites = true)) {
            tx.executeWithoutResult {
                if (update() == 0) {
                    val exists = jdbc.sql("select exists (select 1 from tenant where id = :id)").param("id", tenantId)
                        .query(Boolean::class.java).single()
                    if (!exists) throw NotFoundException("Salão não encontrado")
                    throw DomainException(ProblemType.CONFLICT, "Este salão não está mais aguardando aprovação")
                }
                audit.record(action, "tenant", tenantId)
            }
        }
    }
}
