package io.estilix.salon

import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.EstablishmentType
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
import java.math.BigDecimal
import java.util.UUID

/** A service as the API returns it. Prices travel as decimal strings ("45.00"), never floats. */
data class ServiceView(
    val id: UUID,
    val name: String,
    val category: String,
    val durationMinutes: Int,
    val price: String,
    val priceType: PriceType,
    val priceLabel: String,
    val active: Boolean,
    val position: Int,
)

data class ServiceRequest(
    val name: String?,
    val category: String?,
    val durationMinutes: Int?,
    val price: BigDecimal?,
    val priceType: PriceType?,
)

data class OrderRequest(val ids: List<UUID>?)

data class NewService(val name: String, val category: String, val durationMinutes: Int, val priceCents: Long, val priceType: PriceType)

@Repository
class ServiceRepository(private val jdbc: JdbcClient) {

    fun all(): List<ServiceView> = jdbc.sql("$SELECT order by active desc, position, name").query(::map).list()

    fun find(id: UUID): ServiceView? = jdbc.sql("$SELECT where id = :id").param("id", id).query(::map).optional().orElse(null)

    fun nameKeys(): Set<String> = jdbc.sql("select name_key from service").query(String::class.java).list().filterNotNull().toSet()

    fun insert(service: NewService): UUID {
        val id = UUID.randomUUID()
        try {
            jdbc.sql(
                """
                insert into service (id, tenant_id, name, name_key, category, duration_minutes, price_cents, price_type, position)
                values (:id, app_tenant_id(), :name, :key, :category, :duration, :price, :type,
                        coalesce((select max(position) + 1 from service), 0))
                """.trimIndent(),
            )
                .param("id", id).param("name", service.name).param("key", nameKey(service.name))
                .param("category", service.category).param("duration", service.durationMinutes)
                .param("price", service.priceCents).param("type", service.priceType.name).update()
        } catch (_: DuplicateKeyException) {
            throw duplicate()
        }
        return id
    }

    fun update(id: UUID, service: NewService): Int =
        try {
            jdbc.sql(
                """
                update service set name = :name, name_key = :key, category = :category, duration_minutes = :duration,
                       price_cents = :price, price_type = :type
                where id = :id
                """.trimIndent(),
            )
                .param("id", id).param("name", service.name).param("key", nameKey(service.name))
                .param("category", service.category).param("duration", service.durationMinutes)
                .param("price", service.priceCents).param("type", service.priceType.name).update()
        } catch (_: DuplicateKeyException) {
            throw duplicate()
        }

    fun setActive(id: UUID, active: Boolean): Int =
        jdbc.sql("update service set active = :active where id = :id").param("id", id).param("active", active).update()

    fun setPosition(id: UUID, position: Int): Int =
        jdbc.sql("update service set position = :p where id = :id").param("id", id).param("p", position).update()

    private fun duplicate() = DomainException(ProblemType.CONFLICT, "Já existe um serviço com este nome", mapOf("name" to "já existe"))

    private fun map(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): ServiceView {
        val cents = rs.getLong("price_cents")
        val type = PriceType.valueOf(rs.getString("price_type"))
        return ServiceView(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            category = rs.getString("category"),
            durationMinutes = rs.getInt("duration_minutes"),
            price = Prices.fromCents(cents).toPlainString(),
            priceType = type,
            priceLabel = Prices.label(cents, type),
            active = rs.getBoolean("active"),
            position = rs.getInt("position"),
        )
    }

    private companion object {
        const val SELECT = "select id, name, category, duration_minutes, price_cents, price_type, active, position from service"
    }
}

@Service
class ServiceCatalog(private val services: ServiceRepository) {

    @Transactional(readOnly = true)
    fun all(): List<ServiceView> = services.all()

    @Transactional
    fun create(request: ServiceRequest): ServiceView = services.find(services.insert(validated(request)))!!

    @Transactional
    fun update(id: UUID, request: ServiceRequest): ServiceView {
        val service = validated(request)
        if (services.update(id, service) == 0) throw NotFoundException("Serviço não encontrado")
        return services.find(id)!!
    }

    @Transactional
    fun setActive(id: UUID, active: Boolean): ServiceView {
        if (services.setActive(id, active) == 0) throw NotFoundException("Serviço não encontrado")
        return services.find(id)!!
    }

    /** New display order; ids of other salons are simply not found by RLS. */
    @Transactional
    fun reorder(ids: List<UUID>): List<ServiceView> {
        ids.forEachIndexed { index, id -> if (services.setPosition(id, index) == 0) throw NotFoundException("Serviço não encontrado") }
        return services.all()
    }

    /** Adds the presets whose name the salon does not have yet (spec "Add missing suggestions"). */
    @Transactional
    fun addSuggestions(type: EstablishmentType): List<ServiceView> {
        addPresets(Presets.of(type).map { NewService(it.name, it.category, it.durationMinutes, Prices.toCents(it.price)!!, it.priceType) })
        return services.all()
    }

    /** Used by onboarding and suggestions: inserts services skipping names that already exist. */
    fun addPresets(chosen: List<NewService>) {
        val existing = services.nameKeys().toMutableSet()
        chosen.forEach { if (existing.add(nameKey(it.name))) services.insert(it) }
    }

    companion object {
        fun validated(request: ServiceRequest): NewService {
            val errors = ServiceRules.validate(request.name, request.category, request.durationMinutes, request.price, request.priceType)
            if (errors.isNotEmpty()) throw DomainException(ProblemType.VALIDATION, "Confira os dados do serviço", errors)
            return NewService(
                request.name!!.trim(), request.category!!.trim(), request.durationMinutes!!,
                Prices.toCents(request.price!!)!!, request.priceType!!,
            )
        }
    }
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/services")
class ServicesController(private val catalog: ServiceCatalog, private val salons: SalonService) {

    @GetMapping
    @PreAuthorize("@access.can('VIEW_SERVICES')")
    fun list(@PathVariable tenantId: UUID): List<ServiceView> = catalog.all()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('MANAGE_SERVICES')")
    fun create(@PathVariable tenantId: UUID, @RequestBody request: ServiceRequest): ServiceView = catalog.create(request)

    @PutMapping("/{id}")
    @PreAuthorize("@access.can('MANAGE_SERVICES')")
    fun update(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: ServiceRequest): ServiceView =
        catalog.update(id, request)

    @PostMapping("/{id}/activate")
    @PreAuthorize("@access.can('MANAGE_SERVICES')")
    fun activate(@PathVariable tenantId: UUID, @PathVariable id: UUID): ServiceView = catalog.setActive(id, true)

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@access.can('MANAGE_SERVICES')")
    fun deactivate(@PathVariable tenantId: UUID, @PathVariable id: UUID): ServiceView = catalog.setActive(id, false)

    @PutMapping("/order")
    @PreAuthorize("@access.can('MANAGE_SERVICES')")
    fun reorder(@PathVariable tenantId: UUID, @RequestBody request: OrderRequest): List<ServiceView> =
        catalog.reorder(request.ids ?: emptyList())

    @PostMapping("/suggestions")
    @PreAuthorize("@access.can('MANAGE_SERVICES')")
    fun suggestions(@PathVariable tenantId: UUID): List<ServiceView> = catalog.addSuggestions(salons.profile().establishmentType)
}
