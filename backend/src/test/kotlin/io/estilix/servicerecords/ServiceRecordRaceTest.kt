package io.estilix.servicerecords

import io.estilix.IntegrationTest
import io.estilix.scheduling.SchedulingSetup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Task 4.4 — two people tap "Iniciar atendimento" on the same booking at once: exactly one record
 * opens (design D2, conditional update). The negative control removes the guards the race relies on
 * and shows the duplicate the test would otherwise catch.
 */
class ServiceRecordRaceTest : IntegrationTest() {

    private lateinit var s: SchedulingSetup
    private lateinit var v: Visits
    private lateinit var staff: List<String>

    @BeforeEach
    fun data() {
        s = SchedulingSetup(admin, fixtures)
        v = Visits(api, admin, s) { token(it) }
        staff = listOf(s.owner(), s.manager())
    }

    private fun race(): List<Int> {
        val entry = v.book(staff[0])
        v.moveToToday(entry)
        val start = CountDownLatch(1)
        return Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (0 until 10).map { i ->
                pool.submit<Int> {
                    start.await()
                    v.start(staff[i % 2], entry).status
                }
            }.also { start.countDown() }.map { it.get() }
        }
    }

    private fun records() = admin.sql("select count(*) from service_record").query(Long::class.java).single()

    private fun items() = admin.sql("select count(*) from service_record_item").query(Long::class.java).single()

    @RepeatedTest(20)
    fun `ten concurrent starts of one booking open exactly one record`() {
        val statuses = race()

        assertThat(statuses.count { it == 201 }).isEqualTo(1)
        assertThat(statuses.filter { it != 201 }).containsOnly(400)
        assertThat(records()).isEqualTo(1)
        assertThat(items()).isEqualTo(1)
    }

    @Test
    fun `negative control - when the status guard always matches, the race duplicates items`() {
        // A trigger keeps the booking CONFIRMED, so `where status = 'CONFIRMED'` matches for every request;
        // the one-open-record index and the one-item-per-entry key are dropped too.
        admin.sql(
            """
            create function keep_confirmed() returns trigger language plpgsql as $$
            begin if new.status = 'IN_SERVICE' then new.status := 'CONFIRMED'; end if; return new; end $$
            """.trimIndent(),
        ).update()
        admin.sql("create trigger keep_confirmed before update on agenda_entry for each row execute function keep_confirmed()").update()
        admin.sql("drop index service_record_one_open").update()
        admin.sql("alter table service_record_item drop constraint service_record_item_agenda_entry_id_key").update()
        try {
            race()
            assertThat(items()).isGreaterThan(1)
        } finally {
            admin.sql("drop trigger keep_confirmed on agenda_entry").update()
            admin.sql("drop function keep_confirmed()").update()
            admin.sql("truncate service_record_item, service_record").update()
            admin.sql("create unique index service_record_one_open on service_record (tenant_id, customer_id) where status = 'OPEN'").update()
            admin.sql("alter table service_record_item add constraint service_record_item_agenda_entry_id_key unique (agenda_entry_id)").update()
        }
    }
}
