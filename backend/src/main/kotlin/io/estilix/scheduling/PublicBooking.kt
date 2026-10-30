package io.estilix.scheduling

import io.estilix.privacy.Phones
import io.estilix.salon.ProfessionalRepository
import io.estilix.salon.SalonRepository
import io.estilix.salon.ServiceRepository
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.RequestContext
import io.estilix.tenancy.TenantContext
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

// Public views: no other customer's data, ever (spec "Public endpoints expose no other customer's data").
data class PublicService(val id: UUID, val name: String, val category: String, val durationMinutes: Int, val priceLabel: String)

/** Display names are public by the salon's choice (modified spec salon/professionals). */
data class PublicProfessional(val id: UUID, val displayName: String, val serviceIds: List<UUID>)

data class PublicSalon(
    val name: String,
    val slug: String,
    val phone: String?,
    val city: String?,
    val daysAhead: Int,
    val cancelNoticeMinutes: Int,
    val services: List<PublicService>,
    val professionals: List<PublicProfessional>,
)

data class DaysView(val month: String, val days: List<LocalDate>)

data class TimesView(val date: LocalDate, val free: List<String>, val unavailable: List<String>)

data class PublicBookingRequest(
    val serviceId: UUID?,
    val professionalId: UUID?,
    val date: LocalDate?,
    val time: String?,
    val name: String?,
    val phone: String?,
)

data class NextServiceRequest(val serviceId: UUID?, val professionalId: UUID?)

data class RescheduleRequest(val date: LocalDate?, val time: String?, val professionalId: UUID?)

data class LookupRequest(val phone: String?, val code: String?)

data class PublicBookingItem(
    val id: UUID,
    val serviceId: UUID?,
    val professionalId: UUID,
    val serviceName: String,
    val professionalName: String,
    val date: LocalDate,
    val start: String,
    val end: String,
    val status: EntryStatus,
    val canChange: Boolean,
)

data class GroupView(
    val salonName: String,
    val salonSlug: String,
    val salonPhone: String?,
    val cancelNoticeMinutes: Int,
    val bookings: List<PublicBookingItem>,
    /** Only right after booking or a code lookup: shown once, never stored in clear. */
    val code: String? = null,
    val token: String? = null,
)

/**
 * Public booking (spec scheduling/public-booking). A slug or a manage-link token resolves the salon
 * through SECURITY DEFINER functions; the work then runs inside that salon's tenant context, so RLS
 * applies exactly as for staff (design D4).
 */
@Service
class PublicBookingService(
    private val jdbc: JdbcClient,
    private val bookings: BookingService,
    private val agenda: AgendaRepository,
    private val salons: SalonRepository,
    private val services: ServiceRepository,
    private val professionals: ProfessionalRepository,
    private val limiter: RateLimiter,
    private val limits: PublicLimits,
    transactionManager: PlatformTransactionManager,
    @Value("\${estilix.booking.code-pepper}") private val pepper: String,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun <T> inSalon(slug: String, block: () -> T): T {
        val tenant = readOnly.execute { jdbc.sql("select active_tenant_id(:s)").param("s", slug).query(UUID::class.java).optional().orElse(null) }
            ?: throw NotFoundException("Salão não encontrado")
        return TenantContext.runWith(RequestContext(tenantId = tenant)) { block() }
    }

    private fun <T> inGroup(token: String, block: (BookingGroup) -> T): T {
        val hash = BookingSecrets.tokenHash(token)
        val tenant = readOnly.execute { jdbc.sql("select booking_group_tenant(:h)").param("h", hash).query(UUID::class.java).optional().orElse(null) }
            ?: throw NotFoundException("Agendamento não encontrado")
        return TenantContext.runWith(RequestContext(tenantId = tenant)) {
            val group = readOnly.execute { agenda.groupByTokenHash(hash) } ?: throw NotFoundException("Agendamento não encontrado")
            block(group)
        }
    }

    fun salon(slug: String): PublicSalon = inSalon(slug) {
        readOnly.execute {
            val profile = salons.profile()!!
            val rules = agenda.rules()
            val active = services.all().filter { it.active }
            val activeIds = active.map { it.id }.toSet()
            PublicSalon(
                name = profile.name,
                slug = profile.slug,
                phone = profile.phone?.value,
                city = profile.address.city,
                daysAhead = rules.daysAhead,
                cancelNoticeMinutes = rules.cancelNoticeMinutes,
                services = active.map { PublicService(it.id, it.name, it.category, it.durationMinutes, it.priceLabel) },
                professionals = professionals.all().filter { it.active }
                    .map { PublicProfessional(it.id, it.displayName.value, it.serviceIds.filter(activeIds::contains)) }
                    .filter { it.serviceIds.isNotEmpty() },
            )
        }!!
    }

    fun days(slug: String, serviceId: UUID?, professionalId: UUID?, month: YearMonth) =
        inSalon(slug) { DaysView(month.toString(), bookings.days(serviceId, professionalId, month)) }

    fun times(slug: String, serviceId: UUID?, professionalId: UUID?, date: LocalDate?) = inSalon(slug) {
        val t = bookings.times(serviceId, professionalId, date, staff = false)
        TimesView(t.date, t.free, t.unavailable)
    }

    fun book(slug: String, request: PublicBookingRequest): GroupView = inSalon(slug) {
        Phones.normalize(request.phone)?.let { limiter.hit(limits.bookingsPerPhone, it) }
        val booked = bookings.book(
            BookCommand(request.serviceId, request.professionalId, request.date, request.time, request.name, request.phone, Origin.PUBLIC),
        )
        view(booked.groupId).copy(code = booked.code, token = booked.token)
    }

    fun next(token: String, request: NextServiceRequest): GroupView = inGroup(token) { group ->
        limiter.hit(limits.bookingsPerPhone, group.phone)
        bookings.next(group.id, request.serviceId, request.professionalId, Origin.PUBLIC)
        view(group.id)
    }

    fun group(token: String): GroupView = inGroup(token) { view(it.id) }

    fun cancel(token: String, bookingId: UUID): GroupView = inGroup(token) { group ->
        ownBooking(group, bookingId)
        bookings.cancel(bookingId, CancelledBy.CUSTOMER, enforceNotice = true)
        view(group.id)
    }

    fun reschedule(token: String, bookingId: UUID, request: RescheduleRequest): GroupView = inGroup(token) { group ->
        ownBooking(group, bookingId)
        bookings.reschedule(bookingId, request.date, request.time, request.professionalId, staff = false)
        view(group.id)
    }

    /**
     * Phone + code: failures are counted per phone (5 per 15 min); a match issues a fresh manage link
     * (the previous link stops working), because only token hashes are stored.
     */
    fun lookup(slug: String, request: LookupRequest): GroupView = inSalon(slug) {
        val phone = Phones.normalize(request.phone)
            ?: throw DomainException(ProblemType.VALIDATION, "Telefone inválido", mapOf("phone" to "use DDD + número"))
        limiter.check(limits.codeFailures, phone)
        val code = request.code?.trim().orEmpty()
        val tenant = TenantContext.current().tenantId!!
        val group = readOnly.execute { agenda.groupsOfPhone(phone) }!!
            .firstOrNull { BookingSecrets.matches(it.codeHash, BookingSecrets.codeHash(pepper, tenant, phone, code)) }
        if (group == null) {
            limiter.increment(limits.codeFailures, phone)
            throw DomainException(ProblemType.NOT_FOUND, "Telefone ou código não conferem")
        }
        val token = BookingSecrets.newToken()
        tx.executeWithoutResult { agenda.rotateToken(group.id, BookingSecrets.tokenHash(token)) }
        view(group.id).copy(token = token)
    }

    private fun ownBooking(group: BookingGroup, bookingId: UUID) {
        val inGroup = readOnly.execute { agenda.groupEntries(group.id).any { it.id == bookingId } }!!
        if (!inGroup) throw NotFoundException("Agendamento não encontrado")
    }

    private fun view(groupId: UUID): GroupView = readOnly.execute {
        val profile = salons.profile()!!
        val zone = ZoneId.of(profile.timeZone)
        val rules = agenda.rules()
        val names = professionals.all().associate { it.id to it.displayName.value }
        GroupView(
            salonName = profile.name,
            salonSlug = profile.slug,
            salonPhone = profile.phone?.value,
            cancelNoticeMinutes = rules.cancelNoticeMinutes,
            bookings = agenda.groupEntries(groupId).map {
                PublicBookingItem(
                    id = it.id,
                    serviceId = it.serviceId,
                    professionalId = it.professionalId,
                    serviceName = it.serviceName ?: "",
                    professionalName = names[it.professionalId] ?: "",
                    date = it.startsAt.atZone(zone).toLocalDate(),
                    start = BookingService.localTime(it.startsAt, zone),
                    end = BookingService.localTime(it.endsAt, zone),
                    status = it.status,
                    canChange = bookings.canChange(it, rules),
                )
            },
        )
    }!!
}

@RestController
@RequestMapping("/api/v1/public")
class PublicBookingController(private val service: PublicBookingService) {

    @GetMapping("/salons/{slug}")
    fun salon(@PathVariable slug: String): PublicSalon = service.salon(slug)

    @GetMapping("/salons/{slug}/days")
    fun days(@PathVariable slug: String, @RequestParam serviceId: UUID?, @RequestParam professionalId: UUID?, @RequestParam month: String): DaysView =
        service.days(slug, serviceId, professionalId, runCatching { YearMonth.parse(month) }.getOrElse {
            throw DomainException(ProblemType.VALIDATION, "Mês inválido", mapOf("month" to "use AAAA-MM"))
        })

    @GetMapping("/salons/{slug}/times")
    fun times(@PathVariable slug: String, @RequestParam serviceId: UUID?, @RequestParam professionalId: UUID?, @RequestParam date: LocalDate?): TimesView =
        service.times(slug, serviceId, professionalId, date)

    @PostMapping("/salons/{slug}/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    fun book(@PathVariable slug: String, @RequestBody request: PublicBookingRequest, response: HttpServletResponse): GroupView {
        response.setHeader("Cache-Control", "no-store")
        return service.book(slug, request)
    }

    @PostMapping("/salons/{slug}/lookup")
    fun lookup(@PathVariable slug: String, @RequestBody request: LookupRequest, response: HttpServletResponse): GroupView {
        response.setHeader("Cache-Control", "no-store")
        return service.lookup(slug, request)
    }

    @GetMapping("/bookings/{token}")
    fun group(@PathVariable token: String): GroupView = service.group(token)

    @PostMapping("/bookings/{token}/next")
    @ResponseStatus(HttpStatus.CREATED)
    fun next(@PathVariable token: String, @RequestBody request: NextServiceRequest): GroupView = service.next(token, request)

    @PostMapping("/bookings/{token}/{bookingId}/cancel")
    fun cancel(@PathVariable token: String, @PathVariable bookingId: UUID): GroupView = service.cancel(token, bookingId)

    @PostMapping("/bookings/{token}/{bookingId}/reschedule")
    fun reschedule(@PathVariable token: String, @PathVariable bookingId: UUID, @RequestBody request: RescheduleRequest): GroupView =
        service.reschedule(token, bookingId, request)
}
