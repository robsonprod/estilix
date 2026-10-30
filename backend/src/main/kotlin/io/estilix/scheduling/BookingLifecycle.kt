package io.estilix.scheduling

import io.estilix.customers.CustomerDataHolder
import io.estilix.customers.CustomerRules
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** A booking (or walk-in entry) as the service-records module sees it. */
data class AttendedEntry(
    val entryId: UUID,
    val serviceId: UUID,
    val professionalId: UUID,
    val customerId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val origin: Origin,
)

/**
 * The attendance part of a booking's life (design D2, D4, D5), published for the service-records
 * module. Every method runs in the caller's transaction, so starting a record and moving its
 * bookings either both happen or neither does.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class BookingLifecycle(private val agenda: AgendaRepository, private val bookings: BookingService, private val clock: Clock) {

    fun entry(id: UUID): AttendedEntry? = agenda.entry(id)?.takeIf { it.kind == EntryKind.BOOKING }?.let(::attended)

    /**
     * "Iniciar atendimento": the booking and the CONFIRMED bookings of the same code that day become
     * IN_SERVICE. Returns the started entries, the requested one first.
     */
    fun start(entryId: UUID): List<AttendedEntry> {
        val entry = agenda.entry(entryId)?.takeIf { it.kind == EntryKind.BOOKING } ?: throw NotFoundException("Agendamento não encontrado")
        if (entry.status != EntryStatus.CONFIRMED) {
            throw DomainException(ProblemType.VALIDATION, "Este agendamento não está mais confirmado", mapOf("status" to entry.status.name))
        }
        val zone = bookings.snapshot().zone
        val today = clock.instant().atZone(zone).toLocalDate()
        if (entry.startsAt.atZone(zone).toLocalDate() != today) {
            throw DomainException(ProblemType.VALIDATION, "Só dá para iniciar atendimentos de hoje", mapOf("date" to "não é hoje"))
        }
        val siblings = entry.groupId?.let(agenda::groupEntries).orEmpty()
            .filter { it.id != entry.id && it.status == EntryStatus.CONFIRMED && it.startsAt.atZone(zone).toLocalDate() == today }
        val started = (listOf(entry) + siblings).filter { agenda.transition(it.id, EntryStatus.CONFIRMED, EntryStatus.IN_SERVICE) == 1 }
        if (started.firstOrNull()?.id != entry.id) {
            throw DomainException(ProblemType.VALIDATION, "Este atendimento acabou de ser iniciado por outra pessoa", mapOf("status" to "IN_SERVICE"))
        }
        return started.map { attended(it.copy(status = EntryStatus.IN_SERVICE)) }
    }

    /**
     * Walk-in or extra service: occupies the professional from [start] for [durationMinutes]
     * (design D4). A taken time fails with slot-taken naming the booking in the way.
     */
    fun occupy(professionalId: UUID, serviceId: UUID, customerId: UUID, name: String, phone: String, start: Instant, durationMinutes: Int): AttendedEntry {
        val end = start.plus(durationMinutes.toLong(), ChronoUnit.MINUTES)
        val id = try {
            agenda.insertBooking(professionalId, serviceId, null, start, end, name, phone, Origin.WALK_IN, customerId, EntryStatus.IN_SERVICE)
        } catch (e: DataAccessException) {
            if (!BookingService.isOverlap(e)) throw e
            throw slotTakenBy(professionalId, start, end)
        }
        return attended(agenda.entry(id)!!)
    }

    /** Checked before occupying, so the whole request can fail cleanly without an aborted transaction. */
    fun requireFree(professionalId: UUID, start: Instant, durationMinutes: Int) {
        val end = start.plus(durationMinutes.toLong(), ChronoUnit.MINUTES)
        if (agenda.firstHeldOverlap(professionalId, start, end) != null) throw slotTakenBy(professionalId, start, end)
    }

    /** Record closed: IN_SERVICE -> DONE, freeing time planned after the next 15-minute boundary. */
    fun finish(entryIds: Collection<UUID>) {
        agenda.finish(entryIds, ceilToQuarter(clock.instant()))
    }

    /** Item removed or open record voided: bookings go back to CONFIRMED, walk-ins free their time. */
    fun release(entryIds: Collection<UUID>) {
        agenda.release(entryIds)
    }

    fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MINUTES)

    private fun slotTakenBy(professionalId: UUID, start: Instant, end: Instant): DomainException {
        val zone = bookings.snapshot().zone
        val busy = agenda.firstHeldOverlap(professionalId, start, end)
        val detail = when {
            busy == null -> "Este profissional está ocupado nesse horário."
            // A booking still ahead: say when it starts. A service in progress or just finished: say until when.
            busy.third == EntryStatus.CONFIRMED && busy.first.isAfter(start) ->
                "Este profissional tem um agendamento às ${BookingService.localTime(busy.first, zone)}."
            else -> "Este profissional está ocupado até ${BookingService.localTime(busy.second, zone)}."
        }
        return DomainException(ProblemType.SLOT_TAKEN, detail)
    }

    private fun attended(e: AgendaEntry) =
        AttendedEntry(e.id, e.serviceId!!, e.professionalId, e.customerId!!, e.startsAt, e.endsAt, e.origin!!)

    companion object {
        /** Brazilian offsets are whole hours, so UTC quarter-hours are the salon's quarter-hours. */
        fun ceilToQuarter(instant: Instant): Instant {
            val quarter = 15 * 60L
            val seconds = instant.epochSecond + if (instant.nano > 0) 1 else 0
            return Instant.ofEpochSecond(Math.floorDiv(seconds + quarter - 1, quarter) * quarter)
        }
    }
}

/** Bookings in a customer's export and anonymisation (spec customers/data-subject-rights). */
@Component
class BookingCustomerData(private val agenda: AgendaRepository, private val bookings: BookingService, private val clock: Clock) : CustomerDataHolder {

    override val section = "bookings"

    override fun export(customerId: UUID): Any {
        val zone = bookings.snapshot().zone
        return agenda.customerEntries(customerId).map {
            mapOf(
                "date" to it.startsAt.atZone(zone).toLocalDate().toString(),
                "start" to BookingService.localTime(it.startsAt, zone),
                "end" to BookingService.localTime(it.endsAt, zone),
                "service" to it.serviceName,
                "status" to it.status.name,
                "origin" to it.origin?.name,
                "nameOnBooking" to it.customerName?.value,
                "phoneOnBooking" to it.customerPhone?.value,
            )
        }
    }

    override fun anonymisationBlockers(customerId: UUID): List<String> {
        val now = clock.instant()
        val pending = agenda.customerEntries(customerId).any {
            it.status == EntryStatus.IN_SERVICE || (it.status == EntryStatus.CONFIRMED && it.endsAt.isAfter(now))
        }
        return if (pending) listOf("Cancele ou conclua os agendamentos futuros desta cliente antes de anonimizar.") else emptyList()
    }

    override fun anonymise(customerId: UUID) {
        agenda.anonymiseCustomer(customerId, CustomerRules.ANONYMISED_NAME) { HexFormat.of().formatHex(ByteArray(32).also(random::nextBytes)) }
    }

    private companion object {
        val random = SecureRandom()
    }
}
