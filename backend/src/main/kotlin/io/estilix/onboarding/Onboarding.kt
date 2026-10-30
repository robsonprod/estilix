package io.estilix.onboarding

import io.estilix.audit.AuditAction
import io.estilix.audit.AuditLog
import io.estilix.identity.MembershipRepository
import io.estilix.identity.UserRepository
import io.estilix.salon.Address
import io.estilix.salon.HoursInterval
import io.estilix.salon.ProfileRules
import io.estilix.salon.SalonProfileUpdate
import io.estilix.salon.SalonRepository
import io.estilix.salon.ServiceCatalog
import io.estilix.salon.ServiceRepository
import io.estilix.salon.ServiceRequest
import io.estilix.salon.ServiceRules
import io.estilix.salon.ServiceView
import io.estilix.salon.WeeklyHours
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.EstablishmentType
import io.estilix.tenancy.TenantContext
import io.estilix.tenancy.TenantRole
import io.estilix.tenancy.TenantStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/** Current terms of use; the text is a draft until the final legal text replaces it (design Open Questions). */
object Terms {
    const val VERSION = "v0-draft"
    const val TEXT = "Versão de rascunho dos Termos de Uso do Estilix. Ao continuar, você concorda que o suporte do " +
        "Estilix use o nome, o e-mail e o telefone informados para conferir e liberar o cadastro do salão."
}

/** Everything the 3-step wizard collects, sent once at the end (spec "Onboarding wizard creates a pending salon"). */
data class OnboardingRequest(
    val planCode: String?,
    val establishmentType: EstablishmentType?,
    val name: String?,
    val phone: String?,
    val address: Address?,
    val timeZone: String?,
    val slug: String?,
    val contactName: String?,
    val hours: List<HoursInterval>?,
    val services: List<ServiceRequest>?,
    val acceptTerms: Boolean?,
    val termsVersion: String?,
)

data class OnboardingResult(val tenantId: UUID, val status: TenantStatus)

/** What the owner sees while waiting (spec "Owner of a pending or rejected salon sees only its status"). */
data class Application(
    val tenantId: UUID,
    val name: String,
    val establishmentType: EstablishmentType,
    val status: TenantStatus,
    val slug: String,
    val phone: String?,
    val address: Address,
    val timeZone: String,
    val contactName: String?,
    val submittedAt: Instant?,
    val rejectionReason: String?,
    val hours: List<HoursInterval>,
    val services: List<ServiceView>,
)

@Repository
class ApplicationRepository(private val jdbc: JdbcClient) {

    fun planIsActive(code: String): Boolean =
        jdbc.sql("select exists (select 1 from plan where code = :c and active)").param("c", code).query(Boolean::class.java).single()

    /** The caller's salon waiting for approval or a fix, through the membership read policy. */
    fun openApplicationOfCurrentUser(): UUID? =
        jdbc.sql(
            "select id from tenant where created_by = app_user_id() and status in ('PENDING_APPROVAL', 'REJECTED') limit 1",
        ).query(UUID::class.java).optional().orElse(null)

    fun insertTenant(id: UUID, r: OnboardingRequest, contactEmail: String?) {
        val a = r.address ?: Address(null, null, null, null, null, null)
        jdbc.sql(
            """
            insert into tenant (id, name, establishment_type, time_zone, status, slug, phone, address_street, address_number,
                                address_district, address_city, address_state, address_postal_code, plan_code,
                                terms_version, terms_accepted_by, terms_accepted_at, created_by, contact_name, contact_email,
                                submitted_at)
            values (:id, :name, :type, :tz, 'PENDING_APPROVAL', :slug, :phone, :street, :number, :district, :city, :state,
                    :postal, :plan, :terms, app_user_id(), now(), app_user_id(), :contactName, :contactEmail, now())
            """.trimIndent(),
        )
            .param("id", id).param("name", r.name!!.trim()).param("type", r.establishmentType!!.name).param("tz", r.timeZone)
            .param("slug", r.slug).param("phone", r.phone?.trim()?.ifBlank { null })
            .param("street", a.street?.trim()).param("number", a.number?.trim()).param("district", a.district?.trim())
            .param("city", a.city?.trim()).param("state", a.state?.trim()).param("postal", a.postalCode?.trim())
            .param("plan", r.planCode).param("terms", r.termsVersion)
            .param("contactName", r.contactName!!.trim()).param("contactEmail", contactEmail)
            .update()
    }

    /** Back to the queue after a rejection; the trigger only allows it for the salon's creator. */
    fun resubmit(r: OnboardingRequest): Int =
        jdbc.sql(
            """
            update tenant set status = 'PENDING_APPROVAL', submitted_at = now(), rejection_reason = null,
                              contact_name = :contactName
            where id = app_tenant_id() and status = 'REJECTED' and created_by = app_user_id()
            """.trimIndent(),
        ).param("contactName", r.contactName!!.trim()).update()

    fun application(): Application? =
        jdbc.sql(
            """
            select id, name, establishment_type, status, slug, phone, address_street, address_number, address_district,
                   address_city, address_state, address_postal_code, time_zone, contact_name, submitted_at, rejection_reason
            from tenant where id = app_tenant_id()
            """.trimIndent(),
        ).query { rs, _ ->
            Application(
                tenantId = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                establishmentType = EstablishmentType.valueOf(rs.getString("establishment_type")),
                status = TenantStatus.valueOf(rs.getString("status")),
                slug = rs.getString("slug"),
                phone = rs.getString("phone"),
                address = Address(
                    rs.getString("address_street"), rs.getString("address_number"), rs.getString("address_district"),
                    rs.getString("address_city"), rs.getString("address_state"), rs.getString("address_postal_code"),
                ),
                timeZone = rs.getString("time_zone"),
                contactName = rs.getString("contact_name"),
                submittedAt = rs.getTimestamp("submitted_at")?.toInstant(),
                rejectionReason = rs.getString("rejection_reason"),
                hours = emptyList(),
                services = emptyList(),
            )
        }.optional().orElse(null)
}

/**
 * Sign-up of a new salon (design D2): the tenant id is generated first and the whole submission runs
 * in one transaction inside that salon's own context, so the existing RLS policies apply unchanged.
 */
@Service
class OnboardingService(
    private val applications: ApplicationRepository,
    private val memberships: MembershipRepository,
    private val users: UserRepository,
    private val salons: SalonRepository,
    private val services: ServiceRepository,
    private val catalog: ServiceCatalog,
    private val audit: AuditLog,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun submit(request: OnboardingRequest): OnboardingResult {
        val errors = validate(request, includeServices = true)
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira os dados do cadastro", errors)
        val ctx = TenantContext.current()
        val tenantId = UUID.randomUUID()
        return TenantContext.runWith(ctx.copy(tenantId = tenantId, role = TenantRole.OWNER)) {
            tx.execute {
                if (applications.openApplicationOfCurrentUser() != null) {
                    throw DomainException(ProblemType.CONFLICT, "Você já tem um salão em análise. Aguarde a liberação.")
                }
                if (!applications.planIsActive(request.planCode!!)) {
                    throw DomainException(ProblemType.VALIDATION, "Plano indisponível", mapOf("planCode" to "plano indisponível"))
                }
                val email = users.findById(ctx.userId!!)?.email?.value
                try {
                    applications.insertTenant(tenantId, request, email)
                } catch (e: DuplicateKeyException) {
                    throw if (e.message.orEmpty().contains("tenant_slug_unique")) {
                        DomainException(ProblemType.CONFLICT, "Este endereço público já está em uso", mapOf("slug" to "já está em uso"))
                    } else {
                        DomainException(ProblemType.CONFLICT, "Você já tem um salão em análise. Aguarde a liberação.")
                    }
                }
                memberships.add(tenantId, ctx.userId, TenantRole.OWNER)
                salons.replaceHours(request.hours.orEmpty())
                catalog.addPresets(request.services.orEmpty().map(ServiceCatalog::validated))
                audit.record(AuditAction.TENANT_SIGNED_UP, "tenant", tenantId, mapOf("plan" to request.planCode))
                OnboardingResult(tenantId, TenantStatus.PENDING_APPROVAL)
            }!!
        }
    }

    /** The caller's salon in analysis or rejected, with what was submitted; null when there is none. */
    fun current(): Application? {
        val ctx = TenantContext.current()
        val id = readOnly.execute { applications.openApplicationOfCurrentUser() } ?: return null
        return TenantContext.runWith(ctx.copy(tenantId = id, role = TenantRole.OWNER)) {
            readOnly.execute { applications.application()!!.copy(hours = salons.hours(), services = services.all()) }
        }
    }

    /** Fix and resubmit a REJECTED salon: profile and hours are replaced; services are edited after approval. */
    fun resubmit(tenantId: UUID, request: OnboardingRequest): OnboardingResult {
        val errors = validate(request, includeServices = false)
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira os dados do cadastro", errors)
        val ctx = TenantContext.current()
        if (readOnly.execute { applications.openApplicationOfCurrentUser() } != tenantId) throw NotFoundException("Cadastro não encontrado")
        return TenantContext.runWith(ctx.copy(tenantId = tenantId, role = TenantRole.OWNER)) {
            tx.execute {
                salons.updateProfile(SalonProfileUpdate(request.name, request.phone, request.address, request.timeZone, request.slug))
                if (applications.resubmit(request) == 0) {
                    throw DomainException(ProblemType.CONFLICT, "Só é possível reenviar um cadastro recusado")
                }
                salons.replaceHours(request.hours.orEmpty())
                audit.record(AuditAction.TENANT_RESUBMITTED, "tenant", tenantId)
                OnboardingResult(tenantId, TenantStatus.PENDING_APPROVAL)
            }!!
        }
    }

    private fun validate(r: OnboardingRequest, includeServices: Boolean): Map<String, String> = buildMap {
        if (r.planCode.isNullOrBlank()) put("planCode", "escolha um plano")
        if (r.establishmentType == null) put("establishmentType", "escolha salão ou barbearia")
        putAll(ProfileRules.validate(SalonProfileUpdate(r.name, r.phone, r.address, r.timeZone, r.slug)))
        if (r.contactName.isNullOrBlank() || r.contactName.trim().length > 120) put("contactName", "informe seu nome")
        putAll(WeeklyHours.validate(r.hours.orEmpty(), "hours"))
        if (includeServices) {
            r.services.orEmpty().forEachIndexed { i, s ->
                ServiceRules.validate(s.name, s.category, s.durationMinutes, s.price, s.priceType)
                    .forEach { (field, message) -> put("services[$i].$field", message) }
            }
        }
        if (r.acceptTerms != true || r.termsVersion != Terms.VERSION) put("acceptTerms", "aceite os termos de uso para continuar")
    }
}
