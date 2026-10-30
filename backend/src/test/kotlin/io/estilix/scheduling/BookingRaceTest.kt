package io.estilix.scheduling

import io.estilix.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Task 3.3 — "first commit wins" under real concurrency: 20 customers press "confirm" for the same
 * professional and time at once. A negative control drops the constraint and shows the duplicates
 * the test would otherwise catch, so we know the test can fail.
 */
class BookingRaceTest : IntegrationTest() {

    private lateinit var s: SchedulingSetup

    @BeforeEach
    fun data() {
        s = SchedulingSetup(admin, fixtures)
    }

    private fun race(): List<Int> {
        val start = CountDownLatch(1)
        return Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (1..20).map { i ->
                pool.submit<Int> {
                    start.await()
                    api.post(
                        "/api/v1/public/salons/${s.slug}/bookings", null,
                        mapOf(
                            "serviceId" to s.corte, "professionalId" to s.paula, "date" to s.dayAfterTomorrow(), "time" to "15:00",
                            "name" to "Cliente $i", "phone" to "(11) 9%04d-%04d".format(1000 + i, 1000 + i),
                        ),
                    ).status
                }
            }.also { start.countDown() }.map { it.get() }
        }
    }

    private fun confirmedRows() =
        admin.sql("select count(*) from agenda_entry where status = 'CONFIRMED' and professional_id = :p").param("p", s.paula).query(Long::class.java).single()

    @Test
    fun `twenty concurrent bookings for the same time, exactly one wins`() {
        val statuses = race()

        assertThat(statuses.count { it == 201 }).isEqualTo(1)
        assertThat(statuses.count { it == 409 }).isEqualTo(19)
        assertThat(confirmedRows()).isEqualTo(1)
    }

    @Test
    fun `negative control - without the constraint the same race double-books`() {
        admin.sql("alter table agenda_entry drop constraint agenda_entry_no_overlap").update()
        try {
            race()
            assertThat(confirmedRows()).isGreaterThan(1)
        } finally {
            admin.sql("delete from agenda_entry").update()
            admin.sql(
                """
                alter table agenda_entry add constraint agenda_entry_no_overlap exclude using gist (
                    professional_id with =, tstzrange(starts_at, ends_at, '[)') with &&
                ) where (status = 'CONFIRMED')
                """.trimIndent(),
            ).update()
        }
    }
}
