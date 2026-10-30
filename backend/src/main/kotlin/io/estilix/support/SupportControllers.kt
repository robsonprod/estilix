package io.estilix.support

import io.estilix.tenancy.Tenant
import io.estilix.tenancy.TenantRepository
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class OpenBreakGlassRequest(val reason: String?, val minutes: Int?)

/** Platform support area: every path under `/api/v1/support/` requires PLATFORM_SUPPORT (security configuration). */
@RestController
@RequestMapping("/api/v1/support")
class SupportController(private val tenants: TenantRepository, private val breakGlass: BreakGlassService) {

    /** Every salon: the `tenant_visible` policy lets support see all of them (no personal data here). */
    @GetMapping("/tenants")
    @Transactional(readOnly = true)
    fun tenants(): List<Tenant> = tenants.findAll()

    @PostMapping("/tenants/{tenantId}/break-glass")
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@PathVariable tenantId: UUID, @RequestBody request: OpenBreakGlassRequest): BreakGlassSession =
        breakGlass.open(request.reason, request.minutes)

    @PostMapping("/tenants/{tenantId}/break-glass/{sessionId}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun end(@PathVariable tenantId: UUID, @PathVariable sessionId: UUID) = breakGlass.end(sessionId)
}

/** Owners see every break-glass session on their salon (spec "Break-glass is visible to owners"). */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/access-history")
class AccessHistoryController(private val breakGlass: BreakGlassService) {

    @GetMapping
    @PreAuthorize("@access.can('VIEW_ACCESS_HISTORY')")
    fun history(@PathVariable tenantId: UUID): List<BreakGlassSession> = breakGlass.history()
}
