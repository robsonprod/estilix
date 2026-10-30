package io.estilix.customers

import io.estilix.privacy.PersonName
import io.estilix.privacy.PersonalText
import io.estilix.privacy.Phone
import io.estilix.privacy.Phones
import io.estilix.salon.ProfessionalScope
import io.estilix.salon.ProfessionalScopes
import io.estilix.salon.Prices
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.TenantContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class MarketingConsent { UNKNOWN, GRANTED, REVOKED }

enum class ConsentSource { STAFF, CUSTOMER }

// Published API of the module ------------------------------------------------------------------

data class CustomerRef(val id: UUID, val name: PersonName, val phone: Phone?)

/** One line of a past visit, as other modules report it (design D1: customers never reads their tables). */
data class VisitItem(val serviceName: String, val professionalName: PersonName, val price: String)

data class Visit(val recordId: UUID, val closedAt: Instant, val total: String, val paymentMethod: String, val items: List<VisitItem>)

/** Implemented by the module that owns service records. */
interface CustomerVisits {
    /** Days (salon time zone) with a closed record. */
    fun visitDays(customerId: UUID): List<LocalDate>

    /** Closed records, newest first; only those with an item of [professionalId] when given. */
    fun history(customerId: UUID, professionalId: UUID?): List<Visit>
}

/** Each module holding data about a customer answers data-subject requests for its part (design D10, D11). */
interface CustomerDataHolder {
    /** Key of this module's part in the export file. */
    val section: String

    fun export(customerId: UUID): Any

    /** pt-BR reasons that prevent anonymisation now (open record, future booking). */
    fun anonymisationBlockers(customerId: UUID): List<String>

    fun anonymise(customerId: UUID)
}

/**
 * Finds or creates the salon's customer for a phone (spec "Customers are linked automatically").
 * Runs in the caller's transaction; concurrent first bookings of one phone end on the same row.
 */
@Service
class CustomerDirectory(private val customers: CustomerRepository) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun linkOrCreate(phoneE164: String, name: String): UUID = customers.linkOrCreate(phoneE164, name.trim())

    @Transactional(propagation = Propagation.MANDATORY)
    fun find(id: UUID): CustomerRef? = customers.ref(id)

    @Transactional(propagation = Propagation.MANDATORY)
    fun findByPhone(phoneE164: String): CustomerRef? = customers.refByPhone(phoneE164)
}

// Views ------------------------------------------------------------------------------------------

data class CustomerSummary(
    val id: UUID,
    val name: PersonName,
    val phone: Phone?,
    val visits: Int,
    val totalSpent: String,
    val lastVisit: Instant?,
    val anonymised: Boolean,
)

data class CustomerPage(val items: List<CustomerSummary>, val page: Int, val hasMore: Boolean)

data class ConsentEvent(val consent: MarketingConsent, val source: ConsentSource, val actorUserId: UUID?, val occurredAt: Instant)

data class CustomerDetail(
    val id: UUID,
    val name: PersonName,
    val phone: Phone?,
    val notes: PersonalText?,
    val marketingConsent: MarketingConsent,
    val consentChangedAt: Instant?,
    val consentHistory: List<ConsentEvent>,
    val visits: Int,
    val noShows: Int,
    val totalSpent: String,
    val lastVisit: Instant?,
    val usualIntervalDays: Int?,
    val history: List<Visit>,
    val anonymised: Boolean,
)

data class CustomerRequest(val name: String?, val phone: String?, val notes: String?)

data class ConsentRequest(val consent: MarketingConsent?)

// Persistence ------------------------------------------------------------------------------------

internal data class CustomerRow(
    val id: UUID,
    val name: String,
    val phone: String?,
    val notes: String?,
    val consent: MarketingConsent,
    val consentChangedAt: Instant?,
    val anonymisedAt: Instant?,
)

internal data class Stats(val visits: Int, val totalCents: Long, val lastVisit: Instant?, val noShows: Int)

/** Every statement runs inside one salon's transaction; RLS keeps it there. */
@Repository
class CustomerRepository(private val jdbc: JdbcClient) {

    fun linkOrCreate(phone: String, name: String): UUID {
        jdbc.sql("insert into customer (id, tenant_id, phone, name) values (:id, app_tenant_id(), :p, :n) on conflict (tenant_id, phone) where phone is not null do nothing")
            .param("id", UUID.randomUUID()).param("p", phone).param("n", name).update()
        return jdbc.sql("select id from customer where phone = :p").param("p", phone).query(UUID::class.java).single()
    }

    fun ref(id: UUID): CustomerRef? =
        jdbc.sql("select id, name, phone from customer where id = :id").param("id", id).query(::mapRef).optional().orElse(null)

    fun refByPhone(phone: String): CustomerRef? =
        jdbc.sql("select id, name, phone from customer where phone = :p").param("p", phone).query(::mapRef).optional().orElse(null)

    internal fun row(id: UUID, scope: ProfessionalScope): CustomerRow? =
        jdbc.sql(
            """
            select id, name, phone, notes, marketing_consent, consent_changed_at, anonymised_at from customer c
            where c.id = :id and ${SCOPE_FILTER}
            """.trimIndent(),
        ).param("id", id).param("restricted", scope.restricted).param("scope", scope.professionalId)
            .query { rs, _ ->
                CustomerRow(
                    rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("phone"), rs.getString("notes"),
                    MarketingConsent.valueOf(rs.getString("marketing_consent")), rs.getTimestamp("consent_changed_at")?.toInstant(),
                    rs.getTimestamp("anonymised_at")?.toInstant(),
                )
            }.optional().orElse(null)

    internal fun stats(id: UUID): Stats =
        jdbc.sql("select visits, total_cents, last_visit, no_shows from customer_visit_stats where customer_id = :id").param("id", id)
            .query { rs, _ -> Stats(rs.getInt(1), rs.getLong(2), rs.getTimestamp(3)?.toInstant(), rs.getInt(4)) }.single()

    fun page(search: CustomerSearch?, scope: ProfessionalScope, page: Int, size: Int): List<CustomerSummary> {
        val filter = when (search) {
            is CustomerSearch.PhoneDigits -> "and c.phone like '%' || :term || '%'"
            is CustomerSearch.NameWord -> "and (' ' || c.search_key) like '% ' || :term || '%'"
            null -> ""
        }
        return jdbc.sql(
            """
            select c.id, c.name, c.phone, c.anonymised_at, s.visits, s.total_cents, s.last_visit
            from customer c join customer_visit_stats s on s.customer_id = c.id
            where $SCOPE_FILTER $filter
            order by s.last_visit desc nulls last, c.created_at desc, c.id
            limit :size offset :offset
            """.trimIndent(),
        ).param("restricted", scope.restricted).param("scope", scope.professionalId)
            .param("term", (search as? CustomerSearch.PhoneDigits)?.digits ?: (search as? CustomerSearch.NameWord)?.prefix)
            .param("size", size).param("offset", page * size)
            .query { rs, _ ->
                CustomerSummary(
                    rs.getObject("id", UUID::class.java), PersonName(rs.getString("name")), rs.getString("phone")?.let(::Phone),
                    rs.getInt("visits"), Prices.fromCents(rs.getLong("total_cents")).toPlainString(),
                    rs.getTimestamp("last_visit")?.toInstant(), rs.getTimestamp("anonymised_at") != null,
                )
            }.list()
    }

    fun insert(name: String, phone: String, notes: String?): UUID = UUID.randomUUID().also {
        jdbc.sql("insert into customer (id, tenant_id, phone, name, notes) values (:id, app_tenant_id(), :p, :n, :notes)")
            .param("id", it).param("p", phone).param("n", name).param("notes", notes).update()
    }

    fun update(id: UUID, name: String, phone: String, notes: String?): Int =
        jdbc.sql("update customer set name = :n, phone = :p, notes = :notes where id = :id and anonymised_at is null")
            .param("id", id).param("n", name).param("p", phone).param("notes", notes).update()

    fun setConsent(id: UUID, consent: MarketingConsent, source: ConsentSource, actor: UUID?) {
        jdbc.sql("update customer set marketing_consent = :c, consent_changed_at = now() where id = :id")
            .param("id", id).param("c", consent.name).update()
        jdbc.sql(
            "insert into customer_consent_event (id, tenant_id, customer_id, consent, source, actor_user_id) values (:id, app_tenant_id(), :cu, :c, :s, :a)",
        ).param("id", UUID.randomUUID()).param("cu", id).param("c", consent.name).param("s", source.name).param("a", actor).update()
    }

    fun consentHistory(id: UUID): List<ConsentEvent> =
        jdbc.sql("select consent, source, actor_user_id, occurred_at from customer_consent_event where customer_id = :id order by occurred_at desc, id")
            .param("id", id)
            .query { rs, _ ->
                ConsentEvent(
                    MarketingConsent.valueOf(rs.getString(1)), ConsentSource.valueOf(rs.getString(2)),
                    rs.getObject(3, UUID::class.java), rs.getTimestamp(4).toInstant(),
                )
            }.list()

    /** Erases the identity; the row stays so records keep pointing to "Cliente anonimizado" (design D10). */
    fun anonymise(id: UUID): Int =
        jdbc.sql("update customer set name = :n, phone = null, notes = null, anonymised_at = now() where id = :id and anonymised_at is null")
            .param("id", id).param("n", CustomerRules.ANONYMISED_NAME).update()

    private fun mapRef(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) =
        CustomerRef(rs.getObject("id", UUID::class.java), PersonName(rs.getString("name")), rs.getString("phone")?.let(::Phone))

    private companion object {
        /** PROFESSIONAL scope (design D7): only customers they booked or served. */
        const val SCOPE_FILTER = """
            (not :restricted or exists (
                select 1 from customer_professional cp where cp.customer_id = c.id and cp.professional_id = cast(:scope as uuid)))
        """
    }
}

// Use cases --------------------------------------------------------------------------------------

@Service
class CustomerService(
    private val customers: CustomerRepository,
    private val scopes: ProfessionalScopes,
    private val visits: List<CustomerVisits>,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun page(q: String?, page: Int, size: Int): CustomerPage = readOnly.execute {
        val clamped = size.coerceIn(1, 100)
        val rows = customers.page(CustomerRules.search(q), scopes.current(), page.coerceAtLeast(0), clamped + 1)
        CustomerPage(rows.take(clamped), page, rows.size > clamped)
    }!!

    fun lookup(phone: String?): CustomerRef = readOnly.execute {
        val normalized = Phones.normalize(phone) ?: throw NotFoundException("Cliente não encontrado")
        val ref = customers.refByPhone(normalized)
        ref?.takeIf { customers.row(it.id, scopes.current()) != null } ?: throw NotFoundException("Cliente não encontrado")
    }!!

    fun detail(id: UUID): CustomerDetail = readOnly.execute { detailInTx(id) }!!

    fun create(request: CustomerRequest): CustomerDetail = tx.execute {
        val (name, phone, notes) = validated(request)
        customers.refByPhone(phone)?.let { throw duplicatePhone(it.id) }
        val id = try {
            customers.insert(name, phone, notes)
        } catch (e: DuplicateKeyException) {
            throw duplicatePhone(null) // lost a race with a booking of the same phone
        }
        detailInTx(id)
    }!!

    fun update(id: UUID, request: CustomerRequest): CustomerDetail = tx.execute {
        val row = existing(id)
        if (row.anonymisedAt != null) throw DomainException(ProblemType.VALIDATION, "Cliente anonimizado não pode ser alterado")
        val (name, phone, notes) = validated(request)
        customers.refByPhone(phone)?.takeIf { it.id != id }?.let { throw duplicatePhone(it.id) }
        try {
            customers.update(id, name, phone, notes)
        } catch (e: DuplicateKeyException) {
            throw duplicatePhone(null)
        }
        detailInTx(id)
    }!!

    fun setConsent(id: UUID, request: ConsentRequest): CustomerDetail = tx.execute {
        val consent = request.consent?.takeIf { it != MarketingConsent.UNKNOWN }
            ?: throw DomainException(ProblemType.VALIDATION, "Escolha se a cliente aceita ou não receber mensagens", mapOf("consent" to "use GRANTED ou REVOKED"))
        val row = existing(id)
        if (row.anonymisedAt != null) throw DomainException(ProblemType.VALIDATION, "Cliente anonimizado não pode ser alterado")
        if (row.consent != consent) customers.setConsent(id, consent, ConsentSource.STAFF, TenantContext.current().userId)
        detailInTx(id)
    }!!

    private fun existing(id: UUID): CustomerRow = customers.row(id, scopes.current()) ?: throw NotFoundException("Cliente não encontrado")

    private fun detailInTx(id: UUID): CustomerDetail {
        val scope = scopes.current()
        val row = existing(id)
        val stats = customers.stats(id)
        val source = visits.firstOrNull()
        val history = source?.history(id, scope.professionalId.takeIf { scope.restricted }).orEmpty()
        return CustomerDetail(
            id = row.id,
            name = PersonName(row.name),
            phone = row.phone?.let(::Phone),
            notes = row.notes?.let(::PersonalText),
            marketingConsent = row.consent,
            consentChangedAt = row.consentChangedAt,
            consentHistory = customers.consentHistory(id),
            visits = stats.visits,
            noShows = stats.noShows,
            totalSpent = Prices.fromCents(stats.totalCents).toPlainString(),
            lastVisit = stats.lastVisit,
            usualIntervalDays = source?.let { CustomerRules.usualIntervalDays(it.visitDays(id)) },
            history = history,
            anonymised = row.anonymisedAt != null,
        )
    }

    private fun validated(request: CustomerRequest): Triple<String, String, String?> {
        val notes = request.notes?.trim()?.ifEmpty { null }
        val errors = CustomerRules.validate(request.name, request.phone, notes)
        if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira os dados da cliente", errors)
        return Triple(request.name!!.trim(), Phones.normalize(request.phone)!!, notes)
    }

    private fun duplicatePhone(existing: UUID?): DomainException =
        DomainException(
            ProblemType.VALIDATION, "Já existe uma cliente com este telefone",
            buildMap {
                put("phone", "já cadastrado")
                existing?.let { put("existingCustomerId", it.toString()) }
            },
        )
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/customers")
class CustomersController(private val service: CustomerService) {

    @GetMapping
    @PreAuthorize("@access.can('VIEW_CUSTOMERS')")
    fun page(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "30") size: Int,
    ): CustomerPage = service.page(q, page, size)

    @GetMapping("/lookup")
    @PreAuthorize("@access.can('VIEW_CUSTOMERS')")
    fun lookup(@PathVariable tenantId: UUID, @RequestParam phone: String): CustomerRef = service.lookup(phone)

    @GetMapping("/{id}")
    @PreAuthorize("@access.can('VIEW_CUSTOMERS')")
    fun detail(@PathVariable tenantId: UUID, @PathVariable id: UUID): CustomerDetail = service.detail(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('MANAGE_CUSTOMERS')")
    fun create(@PathVariable tenantId: UUID, @RequestBody request: CustomerRequest): CustomerDetail = service.create(request)

    @PutMapping("/{id}")
    @PreAuthorize("@access.can('MANAGE_CUSTOMERS')")
    fun update(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: CustomerRequest): CustomerDetail =
        service.update(id, request)

    @PutMapping("/{id}/consent")
    @PreAuthorize("@access.can('MANAGE_MARKETING_CONSENT')")
    fun consent(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: ConsentRequest): CustomerDetail =
        service.setConsent(id, request)
}
