package io.estilix.scheduling

import io.estilix.privacy.PersonName
import io.estilix.privacy.Phone
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

enum class EntryKind { BOOKING, BLOCK }

enum class EntryStatus {
    CONFIRMED, IN_SERVICE, DONE, NO_SHOW, CANCELLED;

    companion object {
        /** Statuses that hold time; must match the predicate of `agenda_entry_no_overlap` (design D3). */
        val HELD = setOf(CONFIRMED, IN_SERVICE, DONE)
        const val HELD_SQL = "('CONFIRMED', 'IN_SERVICE', 'DONE')"
    }
}

enum class Origin { PUBLIC, STAFF, WALK_IN }

enum class CancelledBy { CUSTOMER, STAFF }

/** A booking or a block. Customer fields are personal data types (masked for support, redacted in logs). */
data class AgendaEntry(
    val id: UUID,
    val kind: EntryKind,
    val professionalId: UUID,
    val serviceId: UUID?,
    val serviceName: String?,
    val groupId: UUID?,
    val startsAt: Instant,
    val endsAt: Instant,
    val status: EntryStatus,
    val customerName: PersonName?,
    val customerPhone: Phone?,
    val origin: Origin?,
    val blockReason: String?,
    val cancelledBy: CancelledBy?,
    val customerId: UUID? = null,
)

data class BookingGroup(val id: UUID, val phone: String, val codeHash: String)

/** All statements run inside one salon's context; RLS keeps them there. */
@Repository
class AgendaRepository(private val jdbc: JdbcClient) {

    fun rules(): BookingRules =
        jdbc.sql("select booking_days_ahead, booking_min_notice_minutes, booking_cancel_notice_minutes from tenant where id = app_tenant_id()")
            .query { rs, _ -> BookingRules(rs.getInt(1), rs.getInt(2), rs.getInt(3)) }.single()

    fun updateRules(rules: BookingRules) {
        jdbc.sql(
            """
            update tenant set booking_days_ahead = :d, booking_min_notice_minutes = :n, booking_cancel_notice_minutes = :c
            where id = app_tenant_id()
            """.trimIndent(),
        ).param("d", rules.daysAhead).param("n", rules.minNoticeMinutes).param("c", rules.cancelNoticeMinutes).update()
    }

    /** Confirmed bookings and blocks overlapping [from, to). */
    fun busy(from: Instant, to: Instant, excludeId: UUID? = null): List<Busy> =
        jdbc.sql(
            """
            select professional_id, starts_at, ends_at from agenda_entry
            where status in ${EntryStatus.HELD_SQL} and starts_at < :to and ends_at > :from and (cast(:exclude as uuid) is null or id <> :exclude)
            """.trimIndent(),
        ).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("exclude", excludeId)
            .query { rs, _ -> Busy(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()) }
            .list()

    fun bookingsPerProfessional(from: Instant, to: Instant): Map<UUID, Int> =
        jdbc.sql(
            """
            select professional_id, count(*) from agenda_entry
            where kind = 'BOOKING' and status in ${EntryStatus.HELD_SQL} and starts_at >= :from and starts_at < :to
            group by professional_id
            """.trimIndent(),
        ).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }.list().toMap()

    fun entries(from: Instant, to: Instant): List<AgendaEntry> =
        jdbc.sql("$SELECT where e.starts_at < :to and e.ends_at > :from order by e.starts_at")
            .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query(::map).list()

    fun entry(id: UUID): AgendaEntry? = jdbc.sql("$SELECT where e.id = :id").param("id", id).query(::map).optional().orElse(null)

    fun groupEntries(groupId: UUID): List<AgendaEntry> =
        jdbc.sql("$SELECT where e.group_id = :g order by e.starts_at").param("g", groupId).query(::map).list()

    fun insertBooking(
        professionalId: UUID, serviceId: UUID, groupId: UUID?, start: Instant, end: Instant, name: String, phone: String, origin: Origin,
        customerId: UUID, status: EntryStatus = EntryStatus.CONFIRMED,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into agenda_entry (id, tenant_id, kind, professional_id, service_id, group_id, starts_at, ends_at,
                                      customer_name, customer_phone, origin, customer_id, status)
            values (:id, app_tenant_id(), 'BOOKING', :p, :s, :g, :start, :end, :name, :phone, :origin, :c, :status)
            """.trimIndent(),
        ).param("id", id).param("p", professionalId).param("s", serviceId).param("g", groupId)
            .param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
            .param("name", name).param("phone", phone).param("origin", origin.name)
            .param("c", customerId).param("status", status.name).update()
        return id
    }

    fun insertBlock(professionalId: UUID, start: Instant, end: Instant, reason: String?): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into agenda_entry (id, tenant_id, kind, professional_id, starts_at, ends_at, block_reason)
            values (:id, app_tenant_id(), 'BLOCK', :p, :start, :end, :reason)
            """.trimIndent(),
        ).param("id", id).param("p", professionalId).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
            .param("reason", reason).update()
        return id
    }

    fun deleteBlock(id: UUID): Int = jdbc.sql("delete from agenda_entry where id = :id and kind = 'BLOCK'").param("id", id).update()

    fun cancel(id: UUID, by: CancelledBy): Int =
        jdbc.sql(
            "update agenda_entry set status = 'CANCELLED', cancelled_by = :by, cancelled_at = now() where id = :id and kind = 'BOOKING' and status = 'CONFIRMED'",
        ).param("id", id).param("by", by.name).update()

    fun move(id: UUID, professionalId: UUID, start: Instant, end: Instant): Int =
        jdbc.sql(
            "update agenda_entry set professional_id = :p, starts_at = :start, ends_at = :end where id = :id and kind = 'BOOKING' and status = 'CONFIRMED'",
        ).param("id", id).param("p", professionalId).param("start", Timestamp.from(start)).param("end", Timestamp.from(end)).update()

    /** Serializes bookings of one phone in one salon, so the per-day limit cannot be raced. */
    fun lockPhone(phone: String) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(app_tenant_id()::text || :phone))").param("phone", phone).query { _, _ -> 0 }.list()
    }

    fun activeBookingsOfPhone(phone: String, from: Instant, to: Instant): Int =
        jdbc.sql(
            """
            select count(*) from agenda_entry
            where kind = 'BOOKING' and status = 'CONFIRMED' and customer_phone = :phone and starts_at >= :from and starts_at < :to
            """.trimIndent(),
        ).param("phone", phone).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query(Int::class.java).single()

    fun insertGroup(phone: String, codeHash: String, tokenHash: String): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("insert into booking_group (id, tenant_id, phone, code_hash, token_hash) values (:id, app_tenant_id(), :p, :c, :t)")
            .param("id", id).param("p", phone).param("c", codeHash).param("t", tokenHash).update()
        return id
    }

    fun groupByTokenHash(tokenHash: String): BookingGroup? =
        jdbc.sql("select id, phone, code_hash from booking_group where token_hash = :t").param("t", tokenHash)
            .query { rs, _ -> BookingGroup(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) }.optional().orElse(null)

    fun groupsOfPhone(phone: String): List<BookingGroup> =
        jdbc.sql("select id, phone, code_hash from booking_group where phone = :p order by created_at desc").param("p", phone)
            .query { rs, _ -> BookingGroup(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) }.list()

    fun rotateToken(groupId: UUID, tokenHash: String) {
        jdbc.sql("update booking_group set token_hash = :t where id = :id").param("id", groupId).param("t", tokenHash).update()
    }

    // Attendance (change customers-and-service-records, design D2): conditional updates, 0 rows = lost the race.

    fun transition(id: UUID, from: EntryStatus, to: EntryStatus): Int =
        jdbc.sql("update agenda_entry set status = :to where id = :id and kind = 'BOOKING' and status = :from")
            .param("id", id).param("from", from.name).param("to", to.name).update()

    /** IN_SERVICE -> DONE; time planned after [boundary] is freed, keeping at least 15 minutes (design D5). */
    fun finish(ids: Collection<UUID>, boundary: Instant): Int =
        if (ids.isEmpty()) 0 else jdbc.sql(
            """
            update agenda_entry set status = 'DONE',
                ends_at = case when ends_at > :b then least(ends_at, greatest(starts_at + interval '15 minutes', :b)) else ends_at end
            where id in (:ids) and status = 'IN_SERVICE'
            """.trimIndent(),
        ).param("ids", ids).param("b", Timestamp.from(boundary)).update()

    /** IN_SERVICE back to CONFIRMED (bookings) or CANCELLED (walk-ins, which were never booked). */
    fun release(ids: Collection<UUID>): Int =
        if (ids.isEmpty()) 0 else jdbc.sql(
            """
            update agenda_entry set
                status = case when origin = 'WALK_IN' then 'CANCELLED' else 'CONFIRMED' end,
                cancelled_by = case when origin = 'WALK_IN' then 'STAFF' end,
                cancelled_at = case when origin = 'WALK_IN' then now() end
            where id in (:ids) and status = 'IN_SERVICE'
            """.trimIndent(),
        ).param("ids", ids).update()

    /** The earliest entry holding time for [professionalId] inside [from, to): its start, end and status. */
    fun firstHeldOverlap(professionalId: UUID, from: Instant, to: Instant): Triple<Instant, Instant, EntryStatus>? =
        jdbc.sql(
            """
            select starts_at, ends_at, status from agenda_entry
            where professional_id = :p and status in ${EntryStatus.HELD_SQL} and starts_at < :to and ends_at > :from
            order by starts_at limit 1
            """.trimIndent(),
        ).param("p", professionalId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
            .query { rs, _ -> Triple(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), EntryStatus.valueOf(rs.getString(3))) }
            .list().firstOrNull()

    fun customerEntries(customerId: UUID): List<AgendaEntry> =
        jdbc.sql("$SELECT where e.customer_id = :c order by e.starts_at").param("c", customerId).query(::map).list()

    /** Erases the copied name/phone and kills the group's link and code (design D10). */
    fun anonymiseCustomer(customerId: UUID, placeholder: String, randomSecret: () -> String) {
        val groups = jdbc.sql("select distinct group_id from agenda_entry where customer_id = :c and group_id is not null")
            .param("c", customerId).query(UUID::class.java).list()
        jdbc.sql("update agenda_entry set customer_name = :n, customer_phone = null where customer_id = :c")
            .param("c", customerId).param("n", placeholder).update()
        groups.forEach {
            jdbc.sql("update booking_group set phone = null, code_hash = :code, token_hash = :token where id = :id")
                .param("id", it).param("code", randomSecret()).param("token", randomSecret()).update()
        }
    }

    private fun map(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = AgendaEntry(
        id = rs.getObject("id", UUID::class.java),
        kind = EntryKind.valueOf(rs.getString("kind")),
        professionalId = rs.getObject("professional_id", UUID::class.java),
        serviceId = rs.getObject("service_id", UUID::class.java),
        serviceName = rs.getString("service_name"),
        groupId = rs.getObject("group_id", UUID::class.java),
        startsAt = rs.getTimestamp("starts_at").toInstant(),
        endsAt = rs.getTimestamp("ends_at").toInstant(),
        status = EntryStatus.valueOf(rs.getString("status")),
        customerName = rs.getString("customer_name")?.let(::PersonName),
        customerPhone = rs.getString("customer_phone")?.let(::Phone),
        origin = rs.getString("origin")?.let(Origin::valueOf),
        blockReason = rs.getString("block_reason"),
        cancelledBy = rs.getString("cancelled_by")?.let(CancelledBy::valueOf),
        customerId = rs.getObject("customer_id", UUID::class.java),
    )

    private companion object {
        const val SELECT = """
            select e.id, e.kind, e.professional_id, e.service_id, s.name as service_name, e.group_id, e.starts_at, e.ends_at,
                   e.status, e.customer_name, e.customer_phone, e.origin, e.block_reason, e.cancelled_by, e.customer_id
            from agenda_entry e left join service s on s.id = e.service_id
        """
    }
}
