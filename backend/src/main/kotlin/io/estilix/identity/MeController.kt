package io.estilix.identity

import io.estilix.privacy.Email
import io.estilix.privacy.PersonName
import io.estilix.privacy.Phone
import io.estilix.salon.ProfessionalRepository
import io.estilix.tenancy.ActiveSupportAccess
import io.estilix.tenancy.BreakGlassLookup
import io.estilix.tenancy.EstablishmentType
import io.estilix.tenancy.Permission
import io.estilix.tenancy.PermissionCatalog
import io.estilix.tenancy.TenantContext
import io.estilix.tenancy.TenantRole
import io.estilix.tenancy.TenantStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class MeView(
    val userId: UUID,
    val name: PersonName?,
    val email: Email?,
    val phone: Phone?,
    val platformSupport: Boolean,
    val tenants: List<MyTenantView>,
)

data class MyTenantView(
    val id: UUID,
    val name: String,
    val establishmentType: EstablishmentType,
    val status: TenantStatus,
    val role: TenantRole,
    val permissions: Set<Permission>,
    /** Only for owners: platform support currently has temporary access to this salon. */
    val supportAccess: ActiveSupportAccess?,
    /** The agenda profile linked to this person in this salon, if any. */
    val professional: MyProfessionalView?,
)

data class MyProfessionalView(val id: UUID, val displayName: PersonName)

data class ProfileRequest(
    @field:NotBlank @field:Size(max = 120) val name: String?,
    @field:Pattern(regexp = "^\\+?[0-9 ()-]{8,20}$", message = "telefone inválido") val phone: String?,
)

@Service
class MeService(
    private val users: UserRepository,
    private val memberships: MembershipRepository,
    private val breakGlass: BreakGlassLookup,
    private val professionals: ProfessionalRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val readWrite = TransactionTemplate(transactionManager)

    fun me(): MeView {
        val ctx = TenantContext.current()
        val userId = ctx.userId!!
        val (user, tenants) = readOnly.execute { users.findById(userId)!! to memberships.tenantsOfCurrentUser() }
        val views = tenants.map { t ->
            // Break-glass and professional rows are tenant-owned: read them inside that salon's context.
            val (access, professional) = if (t.status == TenantStatus.ACTIVE) {
                TenantContext.runWith(ctx.copy(tenantId = t.tenantId, role = t.role)) {
                    readOnly.execute {
                        val access = if (t.role == TenantRole.OWNER) breakGlass.anyActiveSession() else null
                        access to professionals.findByUser(userId)?.let { MyProfessionalView(it.id, it.displayName) }
                    }
                }
            } else {
                null to null
            }
            MyTenantView(t.tenantId, t.name, t.establishmentType, t.status, t.role, PermissionCatalog.of(t.role), access, professional)
        }
        return MeView(user.id, user.name, user.email, user.phone, ctx.support, views)
    }

    fun updateProfile(request: ProfileRequest) {
        readWrite.executeWithoutResult { users.updateProfile(TenantContext.current().userId!!, request.name!!.trim(), request.phone?.trim()) }
    }
}

@RestController
@RequestMapping("/api/v1/me")
class MeController(private val service: MeService) {

    @GetMapping
    fun me(): MeView = service.me()

    @PutMapping("/profile")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateProfile(@Valid @RequestBody request: ProfileRequest) = service.updateProfile(request)
}
