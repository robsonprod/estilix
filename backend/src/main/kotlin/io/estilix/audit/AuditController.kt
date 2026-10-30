package io.estilix.audit

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/audit-events")
class AuditController(private val auditLog: AuditLog) {

    /** OWNER of the salon or platform support; newest first. */
    @GetMapping
    @PreAuthorize("@access.can('VIEW_AUDIT')")
    fun list(
        @PathVariable tenantId: UUID,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) size: Int,
    ): AuditPage = AuditPage(page, size, auditLog.page(page, size))
}

data class AuditPage(val page: Int, val size: Int, val items: List<AuditEvent>)
