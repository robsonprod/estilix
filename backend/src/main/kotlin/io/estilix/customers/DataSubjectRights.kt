package io.estilix.customers

import io.estilix.audit.AuditAction
import io.estilix.audit.AuditLog
import io.estilix.salon.ProfessionalScope
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class AnonymiseRequest(val confirmation: String?)

data class AnonymiseResult(val id: UUID, val anonymised: Boolean)

/**
 * LGPD requests answered by the salon (spec customers/data-subject-rights). Each module holding
 * customer data contributes through [CustomerDataHolder]; this module never reads their tables.
 */
@Service
class DataSubjectRightsService(
    private val customers: CustomerRepository,
    private val holders: List<CustomerDataHolder>,
    private val audit: AuditLog,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    /** Audited even though it only reads: handing personal data out is a security event. */
    fun export(id: UUID): Map<String, Any?> = tx.execute {
        val row = customers.row(id, UNRESTRICTED) ?: throw NotFoundException("Cliente não encontrado")
        audit.record(AuditAction.CUSTOMER_DATA_EXPORTED, "customer", id)
        buildMap {
            put("exportedAt", Instant.now().toString())
            put(
                "customer",
                mapOf(
                    "id" to row.id, "name" to row.name, "phone" to row.phone, "notes" to row.notes,
                    "marketingConsent" to row.consent, "consentChangedAt" to row.consentChangedAt?.toString(),
                    "anonymised" to (row.anonymisedAt != null),
                ),
            )
            put("consentHistory", customers.consentHistory(id))
            holders.forEach { put(it.section, it.export(id)) }
        }
    }!!

    fun anonymise(id: UUID, request: AnonymiseRequest): AnonymiseResult = tx.execute {
        if (request.confirmation?.trim() != CONFIRMATION) {
            throw DomainException(ProblemType.VALIDATION, "Digite $CONFIRMATION para confirmar", mapOf("confirmation" to "digite $CONFIRMATION"))
        }
        val row = customers.row(id, UNRESTRICTED) ?: throw NotFoundException("Cliente não encontrado")
        if (row.anonymisedAt != null) return@execute AnonymiseResult(id, true)
        val blockers = holders.flatMap { it.anonymisationBlockers(id) }
        if (blockers.isNotEmpty()) {
            throw DomainException(ProblemType.VALIDATION, blockers.joinToString(" "), mapOf("customer" to "resolva antes de anonimizar"))
        }
        holders.forEach { it.anonymise(id) }
        customers.anonymise(id)
        audit.record(AuditAction.CUSTOMER_ANONYMISED, "customer", id)
        AnonymiseResult(id, true)
    }!!

    companion object {
        const val CONFIRMATION = "ANONIMIZAR"
        private val UNRESTRICTED = ProfessionalScope(false, null)
    }
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/customers/{id}")
class DataSubjectRightsController(private val service: DataSubjectRightsService) {

    @GetMapping("/export")
    @PreAuthorize("@access.can('CUSTOMER_DATA_RIGHTS')")
    fun export(@PathVariable tenantId: UUID, @PathVariable id: UUID): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("cliente-$id.json").build().toString())
            .cacheControl(CacheControl.noStore())
            .body(service.export(id))

    @PostMapping("/anonymise")
    @PreAuthorize("@access.can('CUSTOMER_DATA_RIGHTS')")
    fun anonymise(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: AnonymiseRequest): AnonymiseResult =
        service.anonymise(id, request)
}
