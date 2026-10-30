package io.estilix.scheduling

import io.estilix.audit.AuditAction
import io.estilix.audit.AuditLog
import io.estilix.privacy.PersonName
import io.estilix.privacy.Phone
import io.estilix.salon.HoursInterval
import io.estilix.salon.ProfessionalRepository
import io.estilix.shared.DomainException
import io.estilix.shared.ForbiddenException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.TenantContext
import io.estilix.tenancy.TenantRole
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class AgendaItem(
    val id: UUID,
    val kind: EntryKind,
    val start: String,
    val end: String,
    val status: EntryStatus,
    val serviceName: String?,
    val customerName: PersonName?,
    val customerPhone: Phone?,
    val origin: Origin?,
    val blockReason: String?,
    val cancelledBy: CancelledBy?,
)

data class AgendaColumn(val professionalId: UUID, val displayName: PersonName, val color: String, val hours: List<HoursInterval>, val items: List<AgendaItem>)

data class AgendaDay(val date: LocalDate, val timeZone: String, val professionals: List<AgendaColumn>, val linkedProfessionalMissing: Boolean)

data class StaffBookingRequest(
    val serviceId: UUID?,
    val professionalId: UUID?,
    val date: LocalDate?,
    val time: String?,
    val name: String?,
    val phone: String?,
)

data class BlockRequest(val professionalId: UUID?, val date: LocalDate?, val start: String?, val end: String?, val reason: String?)

data class StaffBooked(val groupId: UUID, val code: String?, val item: AgendaItem)

/** Staff agenda (spec scheduling/staff-agenda). */
@Service
class StaffAgendaService(
    private val agenda: AgendaRepository,
    private val bookings: BookingService,
    private val professionals: ProfessionalRepository,
    private val audit: AuditLog,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /** null = every professional; otherwise only the professional linked to the PROFESSIONAL caller. */
    private fun allowedProfessionals(): Set<UUID>? {
        val ctx = TenantContext.current()
        if (ctx.role != TenantRole.PROFESSIONAL) return null
        return setOfNotNull(readOnly.execute { professionals.findByUser(ctx.userId!!)?.id })
    }

    private fun requireAccess(professionalId: UUID) {
        val allowed = allowedProfessionals() ?: return
        if (professionalId !in allowed) throw ForbiddenException("Você só pode mexer na sua própria agenda")
    }

    fun rules(): BookingRules = readOnly.execute { agenda.rules() }!!

    fun updateRules(rules: BookingRules): BookingRules {
        val problems = rules.problems()
        if (problems.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira as regras de agendamento", problems)
        return tx.execute { agenda.updateRules(rules); agenda.rules() }!!
    }

    fun day(date: LocalDate): AgendaDay {
        val allowed = allowedProfessionals()
        return readOnly.execute {
            val zone = bookings.snapshot().zone
            val from = date.atStartOfDay(zone).toInstant()
            val to = date.plusDays(1).atStartOfDay(zone).toInstant()
            val entries = agenda.entries(from, to)
            val columns = professionals.all().filter { it.active && (allowed == null || it.id in allowed) }.map { p ->
                AgendaColumn(
                    p.id, p.displayName, p.color,
                    bookings.effectiveHours(p).filter { it.weekday == date.dayOfWeek.value },
                    entries.filter { it.professionalId == p.id }.map { item(it, zone) },
                )
            }
            AgendaDay(date, zone.id, columns, allowed != null && allowed.isEmpty())
        }!!
    }

    fun times(serviceId: UUID?, professionalId: UUID?, date: LocalDate?): DayTimes {
        professionalId?.let(::requireAccess)
        return bookings.times(serviceId, professionalId, date, staff = true)
    }

    /** Staff bookings ignore the minimum notice (walk-ins) but never overlaps. */
    fun book(request: StaffBookingRequest): StaffBooked {
        val professionalId = request.professionalId
            ?: throw DomainException(ProblemType.VALIDATION, "Escolha o profissional", mapOf("professionalId" to "obrigatório"))
        requireAccess(professionalId)
        val booked = bookings.book(
            BookCommand(request.serviceId, professionalId, request.date, request.time, request.name, request.phone, Origin.STAFF),
        )
        return StaffBooked(booked.groupId, booked.code, item(booked.entry, bookings.snapshot().zone))
    }

    fun cancel(id: UUID): AgendaItem {
        val entry = existing(id)
        requireAccess(entry.professionalId)
        return tx.execute {
            val wasConfirmed = entry.status == EntryStatus.CONFIRMED
            val after = bookings.cancel(id, CancelledBy.STAFF, enforceNotice = false)
            if (wasConfirmed) {
                audit.record(AuditAction.BOOKING_CANCELLED_BY_STAFF, "booking", id, mapOf("startsAt" to entry.startsAt.toString()))
            }
            item(after, bookings.snapshot().zone)
        }!!
    }

    fun reschedule(id: UUID, request: RescheduleRequest): AgendaItem {
        val entry = existing(id)
        requireAccess(entry.professionalId)
        request.professionalId?.let(::requireAccess)
        return tx.execute {
            val (before, after) = bookings.reschedule(id, request.date, request.time, request.professionalId, staff = true)
            audit.record(
                AuditAction.BOOKING_RESCHEDULED_BY_STAFF, "booking", id,
                mapOf(
                    "fromStartsAt" to before.startsAt.toString(), "toStartsAt" to after.startsAt.toString(),
                    "fromProfessional" to before.professionalId.toString(), "toProfessional" to after.professionalId.toString(),
                ),
            )
            item(after, bookings.snapshot().zone)
        }!!
    }

    fun block(request: BlockRequest): AgendaItem {
        val professionalId = request.professionalId
            ?: throw DomainException(ProblemType.VALIDATION, "Escolha o profissional", mapOf("professionalId" to "obrigatório"))
        requireAccess(professionalId)
        val start = request.start?.let { if (it == "24:00") 1440 else Availability.parse(it) }
        val end = request.end?.let { if (it == "24:00") 1440 else Availability.parse(it) }
        val reason = request.reason?.trim()?.ifBlank { null }
        val errors = buildMap {
            if (request.date == null) put("date", "escolha o dia")
            if (start == null || end == null || start % 15 != 0 || end % 15 != 0 || start >= end) put("start", "use horários de 15 em 15 minutos, início antes do fim")
            if (reason != null && reason.length > 80) put("reason", "até 80 caracteres")
        }
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira o bloqueio", errors)
        return tx.execute {
            val zone = bookings.snapshot().zone
            if (professionals.find(professionalId) == null) throw NotFoundException("Profissional não encontrado")
            val day = request.date!!.atStartOfDay(zone).toInstant()
            val id = try {
                agenda.insertBlock(professionalId, day.plusSeconds(start!! * 60L), day.plusSeconds(end!! * 60L), reason)
            } catch (e: DataAccessException) {
                if (BookingService.isOverlap(e)) {
                    throw DomainException(ProblemType.SLOT_TAKEN, "Já existe agendamento ou bloqueio nesse período")
                }
                throw e
            }
            item(agenda.entry(id)!!, zone)
        }!!
    }

    fun removeBlock(id: UUID) {
        val entry = existing(id)
        if (entry.kind != EntryKind.BLOCK) throw NotFoundException("Bloqueio não encontrado")
        requireAccess(entry.professionalId)
        tx.executeWithoutResult { agenda.deleteBlock(id) }
    }

    private fun existing(id: UUID): AgendaEntry = readOnly.execute { agenda.entry(id) } ?: throw NotFoundException("Item da agenda não encontrado")

    private fun item(e: AgendaEntry, zone: ZoneId) = AgendaItem(
        e.id, e.kind, BookingService.localTime(e.startsAt, zone), BookingService.localTime(e.endsAt, zone), e.status,
        e.serviceName, e.customerName, e.customerPhone, e.origin, e.blockReason, e.cancelledBy,
    )
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class StaffAgendaController(private val service: StaffAgendaService) {

    @GetMapping("/booking-rules")
    @PreAuthorize("@access.can('VIEW_AGENDA')")
    fun rules(@PathVariable tenantId: UUID): BookingRules = service.rules()

    @PutMapping("/booking-rules")
    @PreAuthorize("@access.can('MANAGE_BOOKING_RULES')")
    fun updateRules(@PathVariable tenantId: UUID, @RequestBody rules: BookingRules): BookingRules = service.updateRules(rules)

    @GetMapping("/agenda")
    @PreAuthorize("@access.can('VIEW_AGENDA')")
    fun day(@PathVariable tenantId: UUID, @RequestParam date: LocalDate): AgendaDay = service.day(date)

    @GetMapping("/agenda/times")
    @PreAuthorize("@access.can('MANAGE_AGENDA')")
    fun times(@PathVariable tenantId: UUID, @RequestParam serviceId: UUID?, @RequestParam professionalId: UUID?, @RequestParam date: LocalDate?): TimesView =
        service.times(serviceId, professionalId, date).let { TimesView(it.date, it.free, it.unavailable) }

    @PostMapping("/agenda/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('MANAGE_AGENDA')")
    fun book(@PathVariable tenantId: UUID, @RequestBody request: StaffBookingRequest): StaffBooked = service.book(request)

    @PostMapping("/agenda/bookings/{id}/cancel")
    @PreAuthorize("@access.can('MANAGE_AGENDA')")
    fun cancel(@PathVariable tenantId: UUID, @PathVariable id: UUID): AgendaItem = service.cancel(id)

    @PostMapping("/agenda/bookings/{id}/reschedule")
    @PreAuthorize("@access.can('MANAGE_AGENDA')")
    fun reschedule(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: RescheduleRequest): AgendaItem =
        service.reschedule(id, request)

    @PostMapping("/agenda/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('MANAGE_AGENDA')")
    fun block(@PathVariable tenantId: UUID, @RequestBody request: BlockRequest): AgendaItem = service.block(request)

    @DeleteMapping("/agenda/blocks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.can('MANAGE_AGENDA')")
    fun removeBlock(@PathVariable tenantId: UUID, @PathVariable id: UUID) = service.removeBlock(id)
}

/** 429 with Retry-After for limits hit inside services (per phone, per code attempts). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class RateLimitAdvice {
    @ExceptionHandler(RateLimitedException::class)
    fun limited(e: RateLimitedException, response: HttpServletResponse): ProblemDetail {
        response.setHeader("Retry-After", e.retryAfterSeconds.toString())
        return io.estilix.shared.Problems.of(ProblemType.RATE_LIMITED, e.message ?: "Muitas tentativas")
    }
}

@Configuration
class SchedulingConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
