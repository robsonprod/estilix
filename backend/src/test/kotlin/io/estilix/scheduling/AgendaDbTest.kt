package io.estilix.scheduling

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Task 1.2 — the agenda rules the database guarantees by itself (design D1). */
class AgendaDbTest : IntegrationTest() {

    private lateinit var salon: UUID
    private lateinit var other: UUID
    private lateinit var paula: UUID
    private lateinit var corte: UUID
    private lateinit var group: UUID

    @BeforeEach
    fun data() {
        salon = fixtures.tenant("Salão")
        other = fixtures.tenant("Outro", status = "PENDING_APPROVAL")
        paula = professional(salon, "Paula")
        corte = service(salon, "Corte")
        group = UUID.randomUUID()
        admin.sql("insert into booking_group (id, tenant_id, phone, code_hash, token_hash) values (:id, :t, '+5585999990000', 'c', :tk)")
            .param("id", group).param("t", salon).param("tk", UUID.randomUUID().toString()).update()
    }

    private fun professional(tenant: UUID, name: String): UUID = UUID.randomUUID().also {
        admin.sql("insert into professional (id, tenant_id, display_name, color) values (:id, :t, :n, '#123456')")
            .param("id", it).param("t", tenant).param("n", name).update()
    }

    private fun service(tenant: UUID, name: String): UUID = UUID.randomUUID().also {
        admin.sql(
            "insert into service (id, tenant_id, name, name_key, category, duration_minutes, price_cents, price_type) values (:id, :t, :n, :k, 'Cabelo', 30, 4500, 'FIXED')",
        ).param("id", it).param("t", tenant).param("n", name).param("k", name.lowercase()).update()
    }

    private fun booking(start: String, end: String, status: String = "CONFIRMED", pro: UUID = paula, svc: UUID = corte, tenant: UUID = salon) {
        admin.sql(
            """
            insert into agenda_entry (id, tenant_id, kind, professional_id, service_id, group_id, starts_at, ends_at, status,
                                      customer_name, customer_phone, origin)
            values (:id, :t, 'BOOKING', :p, :s, :g, :start::timestamptz, :end::timestamptz, :status, 'Ana', '+5585999990000', 'PUBLIC')
            """.trimIndent(),
        ).param("id", UUID.randomUUID()).param("t", tenant).param("p", pro).param("s", svc).param("g", group)
            .param("start", start).param("end", end).param("status", status).update()
    }

    private fun block(start: String, end: String) {
        admin.sql(
            "insert into agenda_entry (id, tenant_id, kind, professional_id, starts_at, ends_at) values (:id, :t, 'BLOCK', :p, :s::timestamptz, :e::timestamptz)",
        ).param("id", UUID.randomUUID()).param("t", salon).param("p", paula).param("s", start).param("e", end).update()
    }

    @Test
    fun `overlapping bookings of the same professional fail with an exclusion violation`() {
        booking("2026-11-03 15:00-03", "2026-11-03 15:30-03")

        assertThatThrownBy { booking("2026-11-03 15:15-03", "2026-11-03 15:45-03") }.rootCause().hasMessageContaining("agenda_entry_no_overlap")
    }

    @Test
    fun `back-to-back bookings are fine`() {
        booking("2026-11-03 15:00-03", "2026-11-03 15:30-03")
        booking("2026-11-03 15:30-03", "2026-11-03 16:00-03")
    }

    @Test
    fun `a block cannot overlap a booking, and vice versa`() {
        booking("2026-11-03 15:00-03", "2026-11-03 15:30-03")
        assertThatThrownBy { block("2026-11-03 14:00-03", "2026-11-03 15:15-03") }.rootCause().hasMessageContaining("agenda_entry_no_overlap")

        block("2026-11-03 18:00-03", "2026-11-03 19:00-03")
        assertThatThrownBy { booking("2026-11-03 18:30-03", "2026-11-03 19:00-03") }.rootCause().hasMessageContaining("agenda_entry_no_overlap")
    }

    @Test
    fun `a cancelled booking frees its time`() {
        booking("2026-11-03 15:00-03", "2026-11-03 15:30-03", status = "CANCELLED")
        booking("2026-11-03 15:00-03", "2026-11-03 15:30-03")
    }

    @Test
    fun `a booking cannot reference another salon's professional or service`() {
        val foreignPro = professional(other, "Zé")
        val foreignSvc = service(other, "Barba")

        assertThatThrownBy { booking("2026-11-03 10:00-03", "2026-11-03 10:30-03", pro = foreignPro) }.rootCause().hasMessageContaining("foreign key")
        assertThatThrownBy { booking("2026-11-03 10:00-03", "2026-11-03 10:30-03", svc = foreignSvc) }.rootCause().hasMessageContaining("foreign key")
    }

    @Test
    fun `a block cannot carry customer data`() {
        assertThatThrownBy {
            admin.sql(
                "insert into agenda_entry (id, tenant_id, kind, professional_id, starts_at, ends_at, customer_name) values (:id, :t, 'BLOCK', :p, now(), now() + interval '1 hour', 'Ana')",
            ).param("id", UUID.randomUUID()).param("t", salon).param("p", paula).update()
        }.rootCause().hasMessageContaining("check")
    }

    @Test
    fun `in service and done hold the time, no-show frees it`() {
        booking("2026-11-03 15:00-03", "2026-11-03 16:00-03", status = "IN_SERVICE")
        booking("2026-11-03 17:00-03", "2026-11-03 18:00-03", status = "DONE")
        booking("2026-11-03 19:00-03", "2026-11-03 20:00-03", status = "NO_SHOW")

        assertThatThrownBy { booking("2026-11-03 15:30-03", "2026-11-03 16:00-03") }.rootCause().hasMessageContaining("agenda_entry_no_overlap")
        assertThatThrownBy { booking("2026-11-03 17:30-03", "2026-11-03 18:00-03") }.rootCause().hasMessageContaining("agenda_entry_no_overlap")
        booking("2026-11-03 19:00-03", "2026-11-03 20:00-03")
    }

    @Test
    fun `a walk-in has no booking group, other bookings still need one`() {
        fun insert(origin: String, group: UUID?) = admin.sql(
            """
            insert into agenda_entry (id, tenant_id, kind, professional_id, service_id, group_id, starts_at, ends_at, status,
                                      customer_name, customer_phone, origin)
            values (:id, :t, 'BOOKING', :p, :s, :g, now(), now() + interval '30 minutes', 'IN_SERVICE', 'Ana', '+5585999990000', :o)
            """.trimIndent(),
        ).param("id", UUID.randomUUID()).param("t", salon).param("p", paula).param("s", corte).param("g", group).param("o", origin).update()

        insert("WALK_IN", null)
        assertThatThrownBy { insert("STAFF", null) }.rootCause().hasMessageContaining("check")
    }

    @Test
    fun `the upgrade backfill links existing bookings to one customer per phone, named after the latest booking`() {
        booking("2026-11-03 10:00-03", "2026-11-03 10:30-03")
        admin.sql("update agenda_entry set customer_name = 'Ana Souza', created_at = now() - interval '1 day'").update()
        booking("2026-11-04 10:00-03", "2026-11-04 10:30-03") // same phone, typed "Ana" later

        // Re-run the two backfill statements exactly as written in V8.
        val v8 = javaClass.getResource("/db/migration/V8__customers_and_service_records.sql")!!.readText()
        val insert = Regex("insert into customer \\(id, tenant_id, phone, name\\)[^;]+;").find(v8)!!.value
        val link = Regex("update agenda_entry e set customer_id = c.id[^;]+;").find(v8)!!.value
        admin.sql(insert).update()
        admin.sql(link).update()

        val customers = admin.sql("select name from customer where tenant_id = :t").param("t", salon).query(String::class.java).list()
        assertThat(customers).containsExactly("Ana")
        val unlinked = admin.sql("select count(*) from agenda_entry where kind = 'BOOKING' and customer_id is null").query(Long::class.java).single()
        assertThat(unlinked).isZero()
    }

    @Test
    fun `active_tenant_id resolves only active salons`() {
        val activeSlug = admin.sql("select slug from tenant where id = :id").param("id", salon).query(String::class.java).single()
        val pendingSlug = admin.sql("select slug from tenant where id = :id").param("id", other).query(String::class.java).single()

        assertThat(admin.sql("select active_tenant_id(:s)").param("s", activeSlug).query(UUID::class.java).single()).isEqualTo(salon)
        assertThat(admin.sql("select active_tenant_id(:s)").param("s", pendingSlug).query(UUID::class.java).list()).containsExactly(null)
    }
}
