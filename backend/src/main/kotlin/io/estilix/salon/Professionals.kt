package io.estilix.salon

import io.estilix.privacy.PersonName
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.MembershipLookup
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class Professional(
    val id: UUID,
    /** Staff names are personal data: masked for platform support (spec professionals). */
    val displayName: PersonName,
    val userId: UUID?,
    val color: String,
    val active: Boolean,
    val ownHours: Boolean,
    /** Active services this professional performs. */
    val serviceIds: List<UUID>,
)

data class ProfessionalRequest(val displayName: String?, val color: String?, val userId: UUID?)

data class ServicesRequest(val serviceIds: List<UUID>?)

data class HoursRequest(val ownHours: Boolean?, val intervals: List<HoursInterval>?)

/** Effective hours = salon hours, or own ∩ salon; conflicts = own intervals the salon no longer covers. */
data class ProfessionalHours(
    val ownHours: Boolean,
    val own: List<HoursInterval>,
    val effective: List<HoursInterval>,
    val conflicts: List<HoursInterval>,
)

object EffectiveHours {

    fun of(ownHours: Boolean, own: List<HoursInterval>, salon: List<HoursInterval>): ProfessionalHours {
        if (!ownHours) return ProfessionalHours(false, emptyList(), salon, emptyList())
        val effective = WeeklyHours.intersect(own, salon)
        val conflicts = own.filter { o -> !fullyInside(o, salon) }
        return ProfessionalHours(true, own, effective, conflicts)
    }

    fun fullyInside(interval: HoursInterval, salon: List<HoursInterval>): Boolean =
        salon.any { it.weekday == interval.weekday && it.startMinute!! <= interval.startMinute!! && interval.endMinute!! <= it.endMinute!! }

    val PALETTE = listOf("#8b2f5c", "#2563eb", "#059669", "#d97706", "#7c3aed", "#dc2626", "#0891b2", "#4d7c0f")
}

@Repository
class ProfessionalRepository(private val jdbc: JdbcClient) {

    fun all(): List<Professional> = jdbc.sql("$SELECT order by p.active desc, p.display_name").query(::map).list()

    fun find(id: UUID): Professional? = jdbc.sql("$SELECT where p.id = :id").param("id", id).query(::map).optional().orElse(null)

    fun findByUser(userId: UUID): Professional? =
        jdbc.sql("$SELECT where p.user_id = :user").param("user", userId).query(::map).optional().orElse(null)

    fun count(): Int = jdbc.sql("select count(*) from professional").query(Int::class.java).single()

    fun insert(name: String, color: String, userId: UUID?): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("insert into professional (id, tenant_id, display_name, color, user_id) values (:id, app_tenant_id(), :name, :color, :user)")
            .param("id", id).param("name", name).param("color", color).param("user", userId).update()
        return id
    }

    fun update(id: UUID, name: String, color: String, userId: UUID?): Int =
        jdbc.sql("update professional set display_name = :name, color = :color, user_id = :user where id = :id")
            .param("id", id).param("name", name).param("color", color).param("user", userId).update()

    fun setActive(id: UUID, active: Boolean): Int =
        jdbc.sql("update professional set active = :a where id = :id").param("id", id).param("a", active).update()

    fun replaceServices(id: UUID, serviceIds: List<UUID>) {
        jdbc.sql("delete from professional_service where professional_id = :id").param("id", id).update()
        serviceIds.distinct().forEach {
            jdbc.sql("insert into professional_service (tenant_id, professional_id, service_id) values (app_tenant_id(), :p, :s)")
                .param("p", id).param("s", it).update()
        }
    }

    fun ownHours(id: UUID): List<HoursInterval> =
        jdbc.sql("select weekday, starts_minute, ends_minute from professional_hours where professional_id = :id order by weekday, starts_minute")
            .param("id", id).query { rs, _ -> HoursInterval.ofMinutes(rs.getInt(1), rs.getInt(2), rs.getInt(3)) }.list()

    fun replaceHours(id: UUID, ownHours: Boolean, intervals: List<HoursInterval>) {
        jdbc.sql("update professional set own_hours = :own where id = :id").param("id", id).param("own", ownHours).update()
        jdbc.sql("delete from professional_hours where professional_id = :id").param("id", id).update()
        intervals.forEach {
            jdbc.sql(
                "insert into professional_hours (tenant_id, professional_id, weekday, starts_minute, ends_minute) values (app_tenant_id(), :p, :d, :s, :e)",
            ).param("p", id).param("d", it.weekday).param("s", it.startMinute).param("e", it.endMinute).update()
        }
    }

    private fun map(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Professional(
        id = rs.getObject("id", UUID::class.java),
        displayName = PersonName(rs.getString("display_name")),
        userId = rs.getObject("user_id", UUID::class.java),
        color = rs.getString("color"),
        active = rs.getBoolean("active"),
        ownHours = rs.getBoolean("own_hours"),
        serviceIds = (rs.getArray("service_ids")?.array as? Array<*>)?.filterIsInstance<UUID>() ?: emptyList(),
    )

    private companion object {
        // Deactivated services stay linked (reactivation restores them) but are not listed.
        const val SELECT = """
            select p.id, p.display_name, p.user_id, p.color, p.active, p.own_hours,
                   array(select ps.service_id from professional_service ps join service s on s.id = ps.service_id
                         where ps.professional_id = p.id and s.active order by s.position) as service_ids
            from professional p
        """
    }
}

@Service
class ProfessionalService(
    private val professionals: ProfessionalRepository,
    private val salons: SalonRepository,
    private val members: MembershipLookup,
) {

    @Transactional(readOnly = true)
    fun all(): List<Professional> = professionals.all()

    @Transactional
    fun create(request: ProfessionalRequest): Professional {
        val (name, color) = validate(request, defaultColor = EffectiveHours.PALETTE[professionals.count() % EffectiveHours.PALETTE.size])
        val id = linking { professionals.insert(name, color, request.userId) }
        return professionals.find(id)!!
    }

    @Transactional
    fun update(id: UUID, request: ProfessionalRequest): Professional {
        val current = professionals.find(id) ?: throw NotFoundException("Profissional não encontrado")
        val (name, color) = validate(request, defaultColor = current.color)
        linking { professionals.update(id, name, color, request.userId) }
        return professionals.find(id)!!
    }

    @Transactional
    fun setActive(id: UUID, active: Boolean): Professional {
        if (professionals.setActive(id, active) == 0) throw NotFoundException("Profissional não encontrado")
        return professionals.find(id)!!
    }

    @Transactional
    fun replaceServices(id: UUID, serviceIds: List<UUID>): Professional {
        professionals.find(id) ?: throw NotFoundException("Profissional não encontrado")
        try {
            professionals.replaceServices(id, serviceIds)
        } catch (_: DataIntegrityViolationException) {
            // Composite foreign key: the service does not belong to this salon.
            throw DomainException(ProblemType.VALIDATION, "Algum serviço não pertence a este salão", mapOf("serviceIds" to "serviço desconhecido"))
        }
        return professionals.find(id)!!
    }

    @Transactional(readOnly = true)
    fun hours(id: UUID): ProfessionalHours {
        val p = professionals.find(id) ?: throw NotFoundException("Profissional não encontrado")
        return EffectiveHours.of(p.ownHours, professionals.ownHours(id), salons.hours())
    }

    @Transactional
    fun replaceHours(id: UUID, request: HoursRequest): ProfessionalHours {
        professionals.find(id) ?: throw NotFoundException("Profissional não encontrado")
        val own = request.ownHours ?: false
        val intervals = if (own) request.intervals ?: emptyList() else emptyList()
        val errors = WeeklyHours.validate(intervals).toMutableMap()
        if (errors.isEmpty()) {
            val salon = salons.hours()
            intervals.forEachIndexed { i, it ->
                if (!EffectiveHours.fullyInside(it, salon)) errors["intervals[$i]"] = "fora do horário de funcionamento do salão"
            }
        }
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira os horários", errors)
        professionals.replaceHours(id, own, intervals)
        return hours(id)
    }

    private fun validate(request: ProfessionalRequest, defaultColor: String): Pair<String, String> {
        val errors = buildMap {
            if (request.displayName.isNullOrBlank() || request.displayName.trim().length > 60) put("displayName", "informe um nome de até 60 caracteres")
            val color = request.color ?: defaultColor
            if (!Regex("^#[0-9a-f]{6}$").matches(color)) put("color", "use uma cor no formato #rrggbb")
            if (request.userId != null && !members.isMemberOfCurrentTenant(request.userId)) put("userId", "a pessoa precisa fazer parte da equipe")
        }
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira os dados do profissional", errors)
        return request.displayName!!.trim() to (request.color ?: defaultColor)
    }

    private fun <T> linking(block: () -> T): T =
        try {
            block()
        } catch (_: DuplicateKeyException) {
            throw DomainException(ProblemType.CONFLICT, "Esta pessoa já está ligada a outro profissional", mapOf("userId" to "já ligado"))
        }
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/professionals")
class ProfessionalsController(private val service: ProfessionalService) {

    @GetMapping
    @PreAuthorize("@access.can('VIEW_PROFESSIONALS')")
    fun list(@PathVariable tenantId: UUID): List<Professional> = service.all()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('MANAGE_PROFESSIONALS')")
    fun create(@PathVariable tenantId: UUID, @RequestBody request: ProfessionalRequest): Professional = service.create(request)

    @PutMapping("/{id}")
    @PreAuthorize("@access.can('MANAGE_PROFESSIONALS')")
    fun update(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: ProfessionalRequest): Professional =
        service.update(id, request)

    @PostMapping("/{id}/activate")
    @PreAuthorize("@access.can('MANAGE_PROFESSIONALS')")
    fun activate(@PathVariable tenantId: UUID, @PathVariable id: UUID): Professional = service.setActive(id, true)

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@access.can('MANAGE_PROFESSIONALS')")
    fun deactivate(@PathVariable tenantId: UUID, @PathVariable id: UUID): Professional = service.setActive(id, false)

    @PutMapping("/{id}/services")
    @PreAuthorize("@access.can('MANAGE_PROFESSIONALS')")
    fun services(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: ServicesRequest): Professional =
        service.replaceServices(id, request.serviceIds ?: emptyList())

    @GetMapping("/{id}/hours")
    @PreAuthorize("@access.can('VIEW_PROFESSIONALS')")
    fun hours(@PathVariable tenantId: UUID, @PathVariable id: UUID): ProfessionalHours = service.hours(id)

    @PutMapping("/{id}/hours")
    @PreAuthorize("@access.can('MANAGE_PROFESSIONALS')")
    fun replaceHours(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: HoursRequest): ProfessionalHours =
        service.replaceHours(id, request)
}

/**
 * Which professional the caller may act for (spec staff-agenda "Who sees and manages which agenda").
 * [restricted] is false for owners, managers and support; a restricted caller with a null
 * [professionalId] is a PROFESSIONAL not linked to any professional yet, who sees nothing.
 */
data class ProfessionalScope(val restricted: Boolean, val professionalId: java.util.UUID?) {
    fun allows(professional: java.util.UUID): Boolean = !restricted || professional == professionalId
}

@org.springframework.stereotype.Component
class ProfessionalScopes(private val professionals: ProfessionalRepository) {

    /** Must run inside a tenant transaction (the lookup is subject to RLS). */
    fun current(): ProfessionalScope {
        val ctx = io.estilix.tenancy.TenantContext.current()
        if (ctx.role != io.estilix.tenancy.TenantRole.PROFESSIONAL) return ProfessionalScope(false, null)
        return ProfessionalScope(true, professionals.findByUser(ctx.userId!!)?.id)
    }
}
