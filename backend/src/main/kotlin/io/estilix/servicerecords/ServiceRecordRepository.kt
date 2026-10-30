package io.estilix.servicerecords

import io.estilix.customers.CustomerDataHolder
import io.estilix.customers.CustomerVisits
import io.estilix.customers.Visit
import io.estilix.customers.VisitItem
import io.estilix.privacy.PersonName
import io.estilix.salon.Prices
import io.estilix.salon.ProfessionalScope
import io.estilix.salon.SalonRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class RecordRow(
    val id: UUID,
    val customerId: UUID,
    val status: RecordStatus,
    val paymentMethod: PaymentMethod?,
    val totalCents: Long,
    val openedAt: Instant,
    val closedAt: Instant?,
    val voidedAt: Instant?,
)

data class ItemRow(
    val id: UUID,
    val recordId: UUID,
    val serviceId: UUID,
    val serviceName: String,
    val professionalId: UUID,
    val professionalName: String,
    val priceCents: Long,
    val agendaEntryId: UUID?,
)

/** Every statement runs inside one salon's transaction; RLS keeps it there. */
@Repository
class ServiceRecordRepository(private val jdbc: JdbcClient) {

    fun insert(customerId: UUID, openedBy: UUID?): UUID = UUID.randomUUID().also {
        jdbc.sql("insert into service_record (id, tenant_id, customer_id, opened_by) values (:id, app_tenant_id(), :c, :by)")
            .param("id", it).param("c", customerId).param("by", openedBy).update()
    }

    fun openOf(customerId: UUID): UUID? =
        jdbc.sql("select id from service_record where customer_id = :c and status = 'OPEN'").param("c", customerId)
            .query(UUID::class.java).optional().orElse(null)

    fun record(id: UUID): RecordRow? = jdbc.sql("$RECORD where id = :id").param("id", id).query(::mapRecord).optional().orElse(null)

    fun items(recordId: UUID): List<ItemRow> = jdbc.sql("$ITEM where i.record_id = :r order by i.created_at, i.id").param("r", recordId).query(::mapItem).list()

    fun insertItem(recordId: UUID, serviceId: UUID, professionalId: UUID, priceCents: Long, entryId: UUID?) {
        jdbc.sql(
            """
            insert into service_record_item (id, tenant_id, record_id, service_id, professional_id, price_cents, agenda_entry_id)
            values (:id, app_tenant_id(), :r, :s, :p, :price, :e)
            """.trimIndent(),
        ).param("id", UUID.randomUUID()).param("r", recordId).param("s", serviceId).param("p", professionalId)
            .param("price", priceCents).param("e", entryId).update()
    }

    fun updatePrice(itemId: UUID, priceCents: Long): Int =
        jdbc.sql("update service_record_item set price_cents = :p where id = :id").param("id", itemId).param("p", priceCents).update()

    fun deleteItem(itemId: UUID): Int = jdbc.sql("delete from service_record_item where id = :id").param("id", itemId).update()

    fun recomputeTotal(recordId: UUID) {
        jdbc.sql(
            "update service_record set total_cents = (select coalesce(sum(price_cents), 0) from service_record_item where record_id = :id) where id = :id and status = 'OPEN'",
        ).param("id", recordId).update()
    }

    /** Conditional on OPEN: 0 rows means someone else closed or voided it first (design D2). */
    fun close(id: UUID, method: PaymentMethod): Int =
        jdbc.sql("update service_record set status = 'CLOSED', payment_method = :m, closed_at = now() where id = :id and status = 'OPEN'")
            .param("id", id).param("m", method.name).update()

    fun void(id: UUID, reason: String, by: UUID?): Int =
        jdbc.sql(
            "update service_record set status = 'VOIDED', void_reason = :r, voided_at = now(), voided_by = :by where id = :id and status in ('OPEN', 'CLOSED')",
        ).param("id", id).param("r", reason).param("by", by).update()

    fun recordOfEntry(entryId: UUID): UUID? =
        jdbc.sql("select record_id from service_record_item where agenda_entry_id = :e").param("e", entryId).query(UUID::class.java).optional().orElse(null)

    fun idsOpenedBetween(from: Instant, to: Instant, scope: ProfessionalScope): List<UUID> =
        jdbc.sql(
            """
            select r.id from service_record r
            where r.opened_at >= :from and r.opened_at < :to
              and (not :restricted or exists (
                  select 1 from service_record_item i where i.record_id = r.id and i.professional_id = cast(:scope as uuid)))
            order by r.opened_at, r.id
            """.trimIndent(),
        ).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
            .param("restricted", scope.restricted).param("scope", scope.professionalId)
            .query(UUID::class.java).list().filterNotNull()

    fun closedOfCustomer(customerId: UUID): List<RecordRow> =
        jdbc.sql("$RECORD where customer_id = :c and status = 'CLOSED' order by closed_at desc, id").param("c", customerId).query(::mapRecord).list()

    fun allOfCustomer(customerId: UUID): List<RecordRow> =
        jdbc.sql("$RECORD where customer_id = :c order by opened_at, id").param("c", customerId).query(::mapRecord).list()

    private fun mapRecord(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = RecordRow(
        rs.getObject("id", UUID::class.java), rs.getObject("customer_id", UUID::class.java), RecordStatus.valueOf(rs.getString("status")),
        rs.getString("payment_method")?.let(PaymentMethod::valueOf), rs.getLong("total_cents"), rs.getTimestamp("opened_at").toInstant(),
        rs.getTimestamp("closed_at")?.toInstant(), rs.getTimestamp("voided_at")?.toInstant(),
    )

    private fun mapItem(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = ItemRow(
        rs.getObject("id", UUID::class.java), rs.getObject("record_id", UUID::class.java), rs.getObject("service_id", UUID::class.java),
        rs.getString("service_name"), rs.getObject("professional_id", UUID::class.java), rs.getString("professional_name"),
        rs.getLong("price_cents"), rs.getObject("agenda_entry_id", UUID::class.java),
    )

    private companion object {
        const val RECORD = "select id, customer_id, status, payment_method, total_cents, opened_at, closed_at, voided_at from service_record"
        const val ITEM = """
            select i.id, i.record_id, i.service_id, s.name as service_name, i.professional_id, p.display_name as professional_name,
                   i.price_cents, i.agenda_entry_id
            from service_record_item i join service s on s.id = i.service_id join professional p on p.id = i.professional_id
        """
    }
}

/** Customer history, as the customers module asks for it (design D1, D8). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class RecordVisits(private val records: ServiceRecordRepository, private val salons: SalonRepository) : CustomerVisits {

    override fun visitDays(customerId: UUID): List<LocalDate> {
        val zone = ZoneId.of(salons.profile()!!.timeZone)
        return records.closedOfCustomer(customerId).mapNotNull { it.closedAt?.atZone(zone)?.toLocalDate() }
    }

    override fun history(customerId: UUID, professionalId: UUID?): List<Visit> =
        records.closedOfCustomer(customerId).mapNotNull { r ->
            val items = records.items(r.id)
            if (professionalId != null && items.none { it.professionalId == professionalId }) return@mapNotNull null
            Visit(
                r.id, r.closedAt!!, Prices.fromCents(r.totalCents).toPlainString(), r.paymentMethod!!.name,
                items.map { VisitItem(it.serviceName, PersonName(it.professionalName), Prices.fromCents(it.priceCents).toPlainString()) },
            )
        }
}

/** Records keep only the customer id, so anonymisation has nothing to erase here: amounts stay (design D10). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class RecordCustomerData(private val records: ServiceRecordRepository) : CustomerDataHolder {

    override val section = "serviceRecords"

    override fun export(customerId: UUID): Any =
        records.allOfCustomer(customerId).map { r ->
            mapOf(
                "id" to r.id, "status" to r.status.name, "openedAt" to r.openedAt.toString(), "closedAt" to r.closedAt?.toString(),
                "paymentMethod" to r.paymentMethod?.name, "total" to Prices.fromCents(r.totalCents).toPlainString(),
                "items" to records.items(r.id).map {
                    mapOf("service" to it.serviceName, "professional" to it.professionalName, "price" to Prices.fromCents(it.priceCents).toPlainString())
                },
            )
        }

    override fun anonymisationBlockers(customerId: UUID): List<String> =
        if (records.openOf(customerId) != null) listOf("Conclua ou anule a comanda aberta desta cliente antes de anonimizar.") else emptyList()

    override fun anonymise(customerId: UUID) = Unit
}
