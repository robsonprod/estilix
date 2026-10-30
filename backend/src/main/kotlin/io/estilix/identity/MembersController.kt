package io.estilix.identity

import io.estilix.tenancy.TenantRole
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// TenantRole has no PLATFORM_SUPPORT value, so a request trying to grant it fails to parse (400).
data class AddMemberRequest(
    @field:NotBlank @field:Email(message = "e-mail inválido") val email: String?,
    @field:NotNull val role: TenantRole?,
)

data class ChangeRoleRequest(@field:NotNull val role: TenantRole?)

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/members")
class MembersController(private val service: MembershipService) {

    @GetMapping
    @PreAuthorize("@access.can('VIEW_MEMBERS')")
    fun list(@PathVariable tenantId: UUID): List<Member> = service.members()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('MANAGE_MEMBERS')")
    fun add(@PathVariable tenantId: UUID, @Valid @RequestBody request: AddMemberRequest): Member =
        service.add(tenantId, request.email!!.trim(), request.role!!)

    @PutMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.can('MANAGE_MEMBERS')")
    fun changeRole(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @Valid @RequestBody request: ChangeRoleRequest) =
        service.changeRole(userId, request.role!!)

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.can('MANAGE_MEMBERS')")
    fun remove(@PathVariable tenantId: UUID, @PathVariable userId: UUID) = service.remove(userId)
}
