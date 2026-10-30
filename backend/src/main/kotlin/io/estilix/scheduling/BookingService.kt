package io.estilix.scheduling

import io.estilix.salon.EffectiveHours
import io.estilix.salon.Professional
import io.estilix.salon.ProfessionalRepository
import io.estilix.salon.SalonRepository
import io.estilix.salon.ServiceRepository
import io.estilix.salon.ServiceView
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Everything availability needs about one salon, loaded once per request. */
data class SalonSnapshot(val zone: ZoneId, val rules: BookingRules, val phone: String?)

data class BookCommand(
    val serviceId: UUID?,
    val professionalId: UUID?,
    val date: LocalDate?,
    val time: String?,
    val customerName: String?,
    val customerPhone: String?,
    val origin: Origin,
    val groupId: UUID? = null,
)

data class Booked(val groupId: UUID, val code: String?, val token: String?, val entry: AgendaEntry)

/**
 * Availability and booking use cases (specs scheduling/availability and scheduling/bookings).
 * Callers set the salon's tenant context; RLS keeps every statement inside that salon.
 */
@Service
class BookingService(
    private val agenda: AgendaRepository,
    private val salons: SalonRepository,
    private val services: ServiceRepository,
    private val professionals: ProfessionalRepository,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
    @Value("\${estilix.booking.code-pepper}") private val pepper: String,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val savepoint = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }

    fun snapshot(): SalonSnapshot = readOnly.execute {
        val profile = salons.profile()!!
        SalonSnapshot(ZoneId.of(profile.timeZone), agenda.rules(), profile.phone?.value)
    }!!

    // Availability ------------------------------------------------------------------------------

    fun times(serviceId: UUID?, professionalId: UUID?, date: LocalDate?, staff: Boolean): DayTimes = readOnly.execute {
        val salon = snapshot()
        val service = activeService(serviceId)
        val day = date ?: throw DomainException(ProblemType.VALIDATION, "Escolha o dia", mapOf("date" to "obrigatório"))
        if (!staff && !Availability.inWindow(day, salon.zone, clock.instant(), salon.rules)) {
            throw DomainException(ProblemType.VALIDATION, "Este dia ainda não está aberto para agendamento", mapOf("date" to "fora da janela"))
        }
        dayTimes(salon, service, professionalId, day, staff)
    }!!

    /** Days of [month] inside the window with at least one free time (spec "Days with free times"). */
    fun days(serviceId: UUID?, professionalId: UUID?, month: YearMonth): List<LocalDate> = readOnly.execute {
        val salon = snapshot()
        val service = activeService(serviceId)
        val now = clock.instant()
        val candidates = candidates(service, professionalId)
        val from = month.atDay(1).atStartOfDay(salon.zone).toInstant()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(salon.zone).toInstant()
        val busy = agenda.busy(from, to)
        (1..month.lengthOfMonth()).map(month::atDay)
            .filter { Availability.inWindow(it, salon.zone, now, salon.rules) }
            .filter { Availability.day(it, salon.zone, now, salon.rules, service.durationMinutes, candidates, busy).free.isNotEmpty() }
    }!!

    // Bookings ----------------------------------------------------------------------------------

    fun book(cmd: BookCommand): Booked = tx.execute { bookInTransaction(cmd) }!!

    /** "Adicionar mais um serviço": same customer and group, starting when the group's last booking ends. */
    fun next(groupId: UUID, serviceId: UUID?, professionalId: UUID?, origin: Origin): Booked = tx.execute {
        val group = agenda.groupEntries(groupId).filter { it.kind == EntryKind.BOOKING && it.status == EntryStatus.CONFIRMED }
        val last = group.maxByOrNull { it.endsAt } ?: throw NotFoundException("Agendamento não encontrado")
        val local = last.endsAt.atZone(snapshot().zone)
        try {
            bookInTransaction(
                BookCommand(
                    serviceId, professionalId, local.toLocalDate(), Availability.format(local.hour * 60 + local.minute),
                    last.customerName!!.value, last.customerPhone!!.value, origin, groupId,
                ),
            )
        } catch (e: DomainException) {
            if (e.type != ProblemType.SLOT_TAKEN) throw e
            throw DomainException(ProblemType.SLOT_TAKEN, "Ninguém está livre para esse serviço logo em seguida, às ${Availability.format(local.hour * 60 + local.minute)}")
        }
    }!!

    private fun bookInTransaction(cmd: BookCommand): Booked {
        val salon = snapshot()
        val staff = cmd.origin == Origin.STAFF
        val name = cmd.customerName?.trim().orEmpty()
        val phone = Phones.normalize(cmd.customerPhone)
        val minute = cmd.time?.let(Availability::parse)
        val errors = buildMap {
            if (cmd.groupId == null && name.length !in 2..80) put("customerName", "informe o nome (2 a 80 letras)")
            if (cmd.groupId == null && phone == null) put("customerPhone", "telefone inválido: use DDD + número")
            if (cmd.date == null) put("date", "escolha o dia")
            if (minute == null || minute % Availability.GRID != 0) put("time", "escolha um horário da lista")
        }
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira os dados do agendamento", errors)
        val service = activeService(cmd.serviceId)
        val date = cmd.date!!
        if (!staff && !Availability.inWindow(date, salon.zone, clock.instant(), salon.rules)) {
            throw DomainException(ProblemType.VALIDATION, "Este dia ainda não está aberto para agendamento", mapOf("date" to "fora da janela"))
        }
        val times = dayTimes(salon, service, cmd.professionalId, date, staff)
        val time = Availability.format(minute!!)
        val free = times.freeBy[time].orEmpty()
        if (free.isEmpty()) {
            if (time !in times.unavailable) {
                throw DomainException(ProblemType.VALIDATION, "Esse horário está fora do atendimento", mapOf("time" to "fora do horário"))
            }
            throw slotTaken()
        }

        val start = date.atStartOfDay(salon.zone).plusMinutes(minute.toLong()).toInstant()
        val end = start.plusSeconds(service.durationMinutes * 60L)
        val (customerName, customerPhone) = if (cmd.groupId != null) {
            val first = agenda.groupEntries(cmd.groupId).first()
            first.customerName!!.value to first.customerPhone!!.value
        } else {
            name to phone!!
        }
        if (!staff) limitPerPhone(customerPhone, date, salon.zone)

        var code: String? = null
        var token: String? = null
        val groupId = cmd.groupId ?: run {
            code = BookingSecrets.newCode()
            token = BookingSecrets.newToken()
            agenda.insertGroup(customerPhone, BookingSecrets.codeHash(pepper, currentTenant(), customerPhone, code), BookingSecrets.tokenHash(token))
        }

        // Fairness: fewer bookings that day first. Each attempt runs in a savepoint (design D3).
        val load = agenda.bookingsPerProfessional(date.atStartOfDay(salon.zone).toInstant(), date.plusDays(1).atStartOfDay(salon.zone).toInstant())
        val ordered = free.sortedWith(compareBy({ load[it] ?: 0 }, { it.toString() }))
        for (professionalId in ordered) {
            val id = try {
                savepoint.execute { agenda.insertBooking(professionalId, service.id, groupId, start, end, customerName, customerPhone, cmd.origin) }
            } catch (e: DataAccessException) {
                if (isOverlap(e)) continue else throw e
            }
            return Booked(groupId, code, token, agenda.entry(id!!)!!)
        }
        throw slotTaken()
    }

    /** Spec "Cancel": idempotent; customers only until the salon's cancel notice. */
    fun cancel(entryId: UUID, by: CancelledBy, enforceNotice: Boolean): AgendaEntry = tx.execute {
        val entry = booking(entryId)
        if (entry.status == EntryStatus.CANCELLED) return@execute entry
        if (enforceNotice) requireBeforeNotice(entry)
        agenda.cancel(entryId, by)
        agenda.entry(entryId)!!
    }!!

    /** Spec "Reschedule": atomic; on a taken target the booking stays where it was (the transaction rolls back). */
    fun reschedule(entryId: UUID, date: LocalDate?, time: String?, professionalId: UUID?, staff: Boolean): Pair<AgendaEntry, AgendaEntry> = tx.execute {
        val before = booking(entryId)
        if (before.status != EntryStatus.CONFIRMED) throw DomainException(ProblemType.CONFLICT, "Este agendamento foi cancelado")
        if (!staff) requireBeforeNotice(before)
        val minute = time?.let(Availability::parse)
        if (date == null || minute == null || minute % Availability.GRID != 0) {
            throw DomainException(ProblemType.VALIDATION, "Escolha o novo dia e horário", mapOf("time" to "escolha um horário da lista"))
        }
        val salon = snapshot()
        if (!staff && !Availability.inWindow(date, salon.zone, clock.instant(), salon.rules)) {
            throw DomainException(ProblemType.VALIDATION, "Este dia ainda não está aberto para agendamento", mapOf("date" to "fora da janela"))
        }
        val service = services.find(before.serviceId!!)!!
        val duration = ((before.endsAt.epochSecond - before.startsAt.epochSecond) / 60).toInt()
        val target = professionalId ?: before.professionalId
        val candidates = candidates(service, target)
        val dayStart = date.atStartOfDay(salon.zone).toInstant()
        val busy = agenda.busy(dayStart, date.plusDays(1).atStartOfDay(salon.zone).toInstant(), excludeId = entryId)
        val times = Availability.day(date, salon.zone, clock.instant(), salon.rules, duration, candidates, busy, ignoreNotice = staff)
        val label = Availability.format(minute)
        if (target !in times.freeBy[label].orEmpty()) {
            if (label !in times.unavailable) throw DomainException(ProblemType.VALIDATION, "Esse horário está fora do atendimento", mapOf("time" to "fora do horário"))
            throw slotTaken()
        }
        val start = dayStart.plusSeconds(minute * 60L)
        try {
            agenda.move(entryId, target, start, start.plusSeconds(duration * 60L))
        } catch (e: DataAccessException) {
            if (isOverlap(e)) throw slotTaken() else throw e
        }
        before to agenda.entry(entryId)!!
    }!!

    fun canChange(entry: AgendaEntry, rules: BookingRules): Boolean =
        entry.status == EntryStatus.CONFIRMED && !clock.instant().plusSeconds(rules.cancelNoticeMinutes * 60L).isAfter(entry.startsAt)

    // Helpers -------------------------------------------------------------------------------------

    private fun dayTimes(salon: SalonSnapshot, service: ServiceView, professionalId: UUID?, date: LocalDate, staff: Boolean): DayTimes {
        val candidates = candidates(service, professionalId)
        val from = date.atStartOfDay(salon.zone).toInstant()
        val to = date.plusDays(1).atStartOfDay(salon.zone).toInstant()
        return Availability.day(date, salon.zone, clock.instant(), salon.rules, service.durationMinutes, candidates, agenda.busy(from, to), ignoreNotice = staff)
    }

    /** Active professionals who perform the active service; one of them when [professionalId] is given. */
    fun candidates(service: ServiceView, professionalId: UUID?): List<Candidate> {
        val salonHours = salons.hours()
        val all = professionals.all().filter { it.active && service.id in it.serviceIds }
        val chosen = if (professionalId == null) all else all.filter { it.id == professionalId }
        if (professionalId != null && chosen.isEmpty()) {
            throw DomainException(ProblemType.VALIDATION, "Este profissional não faz esse serviço", mapOf("professionalId" to "não faz este serviço"))
        }
        return chosen.map { Candidate(it.id, effectiveHours(it, salonHours)) }
    }

    fun effectiveHours(p: Professional, salonHours: List<io.estilix.salon.HoursInterval> = salons.hours()) =
        EffectiveHours.of(p.ownHours, if (p.ownHours) professionals.ownHours(p.id) else emptyList(), salonHours).effective

    private fun activeService(id: UUID?): ServiceView {
        val service = id?.let(services::find)
        if (service == null || !service.active) {
            throw DomainException(ProblemType.VALIDATION, "Escolha um serviço disponível", mapOf("serviceId" to "serviço indisponível"))
        }
        return service
    }

    private fun booking(id: UUID): AgendaEntry =
        agenda.entry(id)?.takeIf { it.kind == EntryKind.BOOKING } ?: throw NotFoundException("Agendamento não encontrado")

    private fun requireBeforeNotice(entry: AgendaEntry) {
        val rules = agenda.rules()
        if (!canChange(entry, rules)) {
            val hours = rules.cancelNoticeMinutes / 60.0
            val notice = if (hours == hours.toInt().toDouble()) "${hours.toInt()}h" else "${rules.cancelNoticeMinutes} min"
            throw DomainException(ProblemType.TOO_LATE, "Alterações só até $notice antes do horário. Fale com o salão.")
        }
    }

    private fun limitPerPhone(phone: String, date: LocalDate, zone: ZoneId) {
        agenda.lockPhone(phone)
        val count = agenda.activeBookingsOfPhone(phone, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        if (count >= MAX_PER_PHONE_PER_DAY) {
            throw DomainException(
                ProblemType.VALIDATION, "Este telefone já tem $MAX_PER_PHONE_PER_DAY agendamentos nesse dia",
                mapOf("customerPhone" to "limite de agendamentos no dia"),
            )
        }
    }

    private fun currentTenant(): UUID = io.estilix.tenancy.TenantContext.current().tenantId!!

    private fun slotTaken() = DomainException(ProblemType.SLOT_TAKEN, "Esse horário acabou de ser preenchido. Escolha outro horário.")

    companion object {
        const val MAX_PER_PHONE_PER_DAY = 3

        /** PostgreSQL exclusion_violation (23P01): another confirmed entry already holds the time. */
        fun isOverlap(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any { it is java.sql.SQLException && it.sqlState == "23P01" }

        fun localTime(instant: Instant, zone: ZoneId): String = instant.atZone(zone).let { Availability.format(it.hour * 60 + it.minute) }
    }
}
