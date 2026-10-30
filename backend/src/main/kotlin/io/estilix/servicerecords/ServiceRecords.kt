package io.estilix.servicerecords

import io.estilix.audit.AuditAction
import io.estilix.audit.AuditLog
import io.estilix.customers.CustomerDirectory
import io.estilix.customers.CustomerRef
import io.estilix.customers.CustomerRules
import io.estilix.privacy.PersonName
import io.estilix.privacy.Phone
import io.estilix.privacy.Phones
import io.estilix.salon.Prices
import io.estilix.salon.ProfessionalRepository
import io.estilix.salon.ProfessionalScopes
import io.estilix.salon.SalonRepository
import io.estilix.salon.ServiceRepository
import io.estilix.salon.ServiceView
import io.estilix.scheduling.BookingLifecycle
import io.estilix.scheduling.Origin
import io.estilix.shared.DomainException
import io.estilix.shared.ForbiddenException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.TenantContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class RecordStatus { OPEN, CLOSED, VOIDED }

enum class PaymentMethod { PIX, CASH, DEBIT, CREDIT }

data class RecordItemView(
    val id: UUID,
    val serviceId: UUID,
    val serviceName: String,
    val professionalId: UUID,
    val professionalName: PersonName,
    val price: String,
    /** True when the item came from a booking; walk-in items and extras occupy the agenda themselves. */
    val fromBooking: Boolean,
    val agendaEntryId: UUID?,
)

data class RecordView(
    val id: UUID,
    val customerId: UUID,
    val customerName: PersonName,
    val customerPhone: Phone?,
    val status: RecordStatus,
    val paymentMethod: PaymentMethod?,
    val total: String,
    val openedAt: Instant,
    val closedAt: Instant?,
    val voidedAt: Instant?,
    val items: List<RecordItemView>,
)

data class DayRecords(val date: LocalDate, val records: List<RecordView>, val total: String)

data class WalkInRequest(val phone: String?, val name: String?, val serviceId: UUID?, val professionalId: UUID?)

data class ItemRequest(val serviceId: UUID?, val professionalId: UUID?, val price: BigDecimal?)

data class PriceRequest(val price: BigDecimal?)

data class CloseRequest(val paymentMethod: PaymentMethod?)

data class VoidRequest(val reason: String?)

/**
 * The comanda (spec service-records/service-records). Agenda changes go through [BookingLifecycle]
 * in the same transaction, so a record and its bookings never disagree (design D1, D2).
 */
@Service
class ServiceRecordService(
    private val records: ServiceRecordRepository,
    private val lifecycle: BookingLifecycle,
    private val customers: CustomerDirectory,
    private val services: ServiceRepository,
    private val professionals: ProfessionalRepository,
    private val salons: SalonRepository,
    private val scopes: ProfessionalScopes,
    private val audit: AuditLog,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    // Starting ------------------------------------------------------------------------------------

    /** "Iniciar atendimento": the booking (and same-code bookings that day) become items of the customer's open record. */
    fun startFromBooking(entryId: UUID): RecordView = tx.execute {
        val scope = scopes.current()
        val entry = lifecycle.entry(entryId) ?: throw NotFoundException("Agendamento não encontrado")
        if (!scope.allows(entry.professionalId)) throw ForbiddenException("Você só pode iniciar os seus atendimentos")
        val started = lifecycle.start(entryId)
        val recordId = openRecordOf(entry.customerId)
        started.sortedBy { it.startsAt }.forEach { records.insertItem(recordId, it.serviceId, it.professionalId, catalogPrice(service(it.serviceId)), it.entryId) }
        records.recomputeTotal(recordId)
        view(recordId)
    }!!

    /** "Novo atendimento": a customer without a booking, starting now and occupying the agenda (design D4). */
    fun walkIn(request: WalkInRequest): RecordView {
        val phone = Phones.normalize(request.phone)
        val errors = buildMap {
            if (phone == null) put("phone", "telefone inválido: use DDD + número")
            if (request.serviceId == null) put("serviceId", "escolha o serviço")
            if (request.professionalId == null) put("professionalId", "escolha o profissional")
        }
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira o atendimento", errors)
        return tx.execute {
            val scope = scopes.current()
            if (!scope.allows(request.professionalId!!)) throw ForbiddenException("Você só pode registrar os seus atendimentos")
            val service = performedService(request.serviceId!!, request.professionalId)
            val existing = customers.findByPhone(phone!!)
            val name = existing?.name?.value ?: request.name?.trim()
            if (name == null || name.length !in CustomerRules.NAME_MIN..CustomerRules.NAME_MAX) {
                throw DomainException(ProblemType.VALIDATION, "Informe o nome da cliente", mapOf("name" to "informe o nome (2 a 80 letras)"))
            }
            val customerId = existing?.id ?: customers.linkOrCreate(phone, name)
            val recordId = openRecordOf(customerId)
            occupyAndAdd(recordId, CustomerRef(customerId, PersonName(name), Phone(phone)), service, request.professionalId, catalogPrice(service))
            view(recordId)
        }!!
    }

    // Items ---------------------------------------------------------------------------------------

    fun addItem(recordId: UUID, request: ItemRequest): RecordView = tx.execute {
        val record = openRecord(recordId)
        val professionalId = request.professionalId
            ?: throw DomainException(ProblemType.VALIDATION, "Escolha o profissional", mapOf("professionalId" to "obrigatório"))
        if (!scopes.current().allows(professionalId)) throw ForbiddenException("Você só pode adicionar serviços feitos por você")
        val service = performedService(
            request.serviceId ?: throw DomainException(ProblemType.VALIDATION, "Escolha o serviço", mapOf("serviceId" to "obrigatório")),
            professionalId,
        )
        val price = request.price?.let(::cents) ?: catalogPrice(service)
        val customer = customers.find(record.customerId)!!
        occupyAndAdd(recordId, customer, service, professionalId, price)
        view(recordId)
    }!!

    fun updatePrice(recordId: UUID, itemId: UUID, request: PriceRequest): RecordView = tx.execute {
        openRecord(recordId)
        val item = records.items(recordId).firstOrNull { it.id == itemId } ?: throw NotFoundException("Item não encontrado")
        if (!scopes.current().allows(item.professionalId)) throw ForbiddenException("Você só pode alterar os seus serviços")
        val price = request.price?.let(::cents) ?: throw DomainException(ProblemType.VALIDATION, "Informe o valor", mapOf("price" to "obrigatório"))
        records.updatePrice(itemId, price)
        records.recomputeTotal(recordId)
        view(recordId)
    }!!

    fun removeItem(recordId: UUID, itemId: UUID): RecordView = tx.execute {
        openRecord(recordId)
        val items = records.items(recordId)
        val item = items.firstOrNull { it.id == itemId } ?: throw NotFoundException("Item não encontrado")
        if (!scopes.current().allows(item.professionalId)) throw ForbiddenException("Você só pode remover os seus serviços")
        if (items.size == 1) {
            throw DomainException(ProblemType.VALIDATION, "A comanda precisa de pelo menos um serviço. Para desfazer, anule a comanda.", mapOf("items" to "último item"))
        }
        records.deleteItem(itemId)
        item.agendaEntryId?.let { lifecycle.release(listOf(it)) }
        records.recomputeTotal(recordId)
        view(recordId)
    }!!

    // Closing and voiding -------------------------------------------------------------------------

    fun close(recordId: UUID, request: CloseRequest): RecordView {
        val method = request.paymentMethod
            ?: throw DomainException(ProblemType.VALIDATION, "Escolha a forma de pagamento", mapOf("paymentMethod" to "obrigatório"))
        return tx.execute {
            openRecord(recordId)
            records.recomputeTotal(recordId)
            if (records.close(recordId, method) == 0) throw changedMeanwhile()
            lifecycle.finish(records.items(recordId).mapNotNull { it.agendaEntryId })
            view(recordId)
        }!!
    }

    fun void(recordId: UUID, request: VoidRequest): RecordView {
        val reason = request.reason?.trim().orEmpty()
        if (reason.length !in 5..200) {
            throw DomainException(ProblemType.VALIDATION, "Explique por que a comanda foi anulada", mapOf("reason" to "de 5 a 200 caracteres"))
        }
        return tx.execute {
            val record = visibleRecord(recordId)
            if (record.status == RecordStatus.VOIDED) throw DomainException(ProblemType.VALIDATION, "Esta comanda já foi anulada")
            if (records.void(recordId, reason, TenantContext.current().userId) == 0) throw changedMeanwhile()
            if (record.status == RecordStatus.OPEN) lifecycle.release(records.items(recordId).mapNotNull { it.agendaEntryId })
            // No reason text in the audit: staff may have written the customer's name in it.
            audit.record(AuditAction.SERVICE_RECORD_VOIDED, "service_record", recordId, mapOf("amountCents" to record.totalCents, "wasStatus" to record.status.name))
            view(recordId)
        }!!
    }

    // Reading -------------------------------------------------------------------------------------

    fun get(recordId: UUID): RecordView = readOnly.execute { visibleRecord(recordId); view(recordId) }!!

    fun byAgendaEntry(entryId: UUID): RecordView = readOnly.execute {
        val recordId = records.recordOfEntry(entryId) ?: throw NotFoundException("Comanda não encontrada")
        visibleRecord(recordId)
        view(recordId)
    }!!

    fun day(date: LocalDate): DayRecords = readOnly.execute {
        val zone = zone()
        val ids = records.idsOpenedBetween(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant(), scopes.current())
        val views = ids.map(::view)
        val total = views.filter { it.status == RecordStatus.CLOSED }.sumOf { cents(BigDecimal(it.total)) }
        DayRecords(date, views, Prices.fromCents(total).toPlainString())
    }!!

    // Helpers -------------------------------------------------------------------------------------

    private fun occupyAndAdd(recordId: UUID, customer: CustomerRef, service: ServiceView, professionalId: UUID, priceCents: Long) {
        // Same professional, same record: the next service starts when the previous one ends.
        val previousEnd = records.items(recordId).filter { it.professionalId == professionalId }
            .mapNotNull { item -> item.agendaEntryId?.let(lifecycle::entry)?.endsAt }.maxOrNull()
        val now = lifecycle.now()
        val start = previousEnd?.takeIf { it.isAfter(now) } ?: now
        lifecycle.requireFree(professionalId, start, service.durationMinutes)
        val entry = lifecycle.occupy(professionalId, service.id, customer.id, customer.name.value, customer.phone?.value.orEmpty(), start, service.durationMinutes)
        records.insertItem(recordId, service.id, professionalId, priceCents, entry.entryId)
        records.recomputeTotal(recordId)
    }

    private fun openRecordOf(customerId: UUID): UUID =
        records.openOf(customerId) ?: try {
            records.insert(customerId, TenantContext.current().userId)
        } catch (e: DuplicateKeyException) {
            throw changedMeanwhile()
        }

    /** Not-found when outside the caller's scope (design D7: existence is not revealed). */
    private fun visibleRecord(recordId: UUID): RecordRow {
        val record = records.record(recordId) ?: throw NotFoundException("Comanda não encontrada")
        val scope = scopes.current()
        if (scope.restricted && records.items(recordId).none { scope.allows(it.professionalId) }) throw NotFoundException("Comanda não encontrada")
        return record
    }

    private fun openRecord(recordId: UUID): RecordRow {
        val record = visibleRecord(recordId)
        if (record.status != RecordStatus.OPEN) {
            throw DomainException(ProblemType.VALIDATION, "Esta comanda já foi ${if (record.status == RecordStatus.CLOSED) "concluída" else "anulada"}", mapOf("status" to record.status.name))
        }
        return record
    }

    private fun service(id: UUID): ServiceView = services.find(id) ?: throw NotFoundException("Serviço não encontrado")

    private fun performedService(serviceId: UUID, professionalId: UUID): ServiceView {
        val service = services.find(serviceId)?.takeIf { it.active }
            ?: throw DomainException(ProblemType.VALIDATION, "Escolha um serviço disponível", mapOf("serviceId" to "serviço indisponível"))
        val professional = professionals.find(professionalId)?.takeIf { it.active }
            ?: throw DomainException(ProblemType.VALIDATION, "Escolha um profissional ativo", mapOf("professionalId" to "indisponível"))
        if (service.id !in professional.serviceIds) {
            throw DomainException(ProblemType.VALIDATION, "Este profissional não faz esse serviço", mapOf("professionalId" to "não faz este serviço"))
        }
        return service
    }

    private fun catalogPrice(service: ServiceView): Long = Prices.toCents(BigDecimal(service.price)) ?: 0

    private fun cents(price: BigDecimal): Long {
        val value = Prices.toCents(price)
        if (value == null || value > MAX_PRICE_CENTS) {
            throw DomainException(ProblemType.VALIDATION, "Valor inválido", mapOf("price" to "de R$ 0,00 a R$ 10.000,00, com até 2 casas"))
        }
        return value
    }

    private fun zone(): ZoneId = ZoneId.of(salons.profile()!!.timeZone)

    private fun view(recordId: UUID): RecordView {
        val r = records.record(recordId)!!
        val customer = customers.find(r.customerId)!!
        return RecordView(
            r.id, r.customerId, customer.name, customer.phone, r.status, r.paymentMethod, Prices.fromCents(r.totalCents).toPlainString(),
            r.openedAt, r.closedAt, r.voidedAt,
            records.items(recordId).map {
                RecordItemView(
                    it.id, it.serviceId, it.serviceName, it.professionalId, PersonName(it.professionalName),
                    Prices.fromCents(it.priceCents).toPlainString(),
                    it.agendaEntryId?.let(lifecycle::entry)?.let { e -> e.origin != Origin.WALK_IN } ?: false, it.agendaEntryId,
                )
            },
        )
    }

    private fun changedMeanwhile() = DomainException(ProblemType.VALIDATION, "Esta comanda acabou de mudar. Atualize a tela.")

    companion object {
        const val MAX_PRICE_CENTS = 1_000_000L
    }
}
