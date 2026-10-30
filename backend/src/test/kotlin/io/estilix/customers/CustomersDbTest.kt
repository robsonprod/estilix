package io.estilix.customers

import io.estilix.IntegrationTest
import io.estilix.tenancy.RequestContext
import io.estilix.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Task 1.1 — what the database guarantees by itself for customers and service records (design D6, D9, D13). */
class CustomersDbTest : IntegrationTest() {

    @Autowired
    private lateinit var appJdbc: JdbcClient

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var salon: UUID
    private lateinit var other: UUID
    private lateinit var paula: UUID
    private lateinit var corte: UUID

    @BeforeEach
    fun data() {
        salon = fixtures.tenant("Salão")
        other = fixtures.tenant("Outro")
        paula = professional(salon)
        corte = service(salon)
    }

    private fun professional(tenant: UUID): UUID = UUID.randomUUID().also {
        admin.sql("insert into professional (id, tenant_id, display_name, color) values (:id, :t, 'Paula', '#123456')")
            .param("id", it).param("t", tenant).update()
    }

    private fun service(tenant: UUID): UUID = UUID.randomUUID().also {
        admin.sql(
            "insert into service (id, tenant_id, name, name_key, category, duration_minutes, price_cents, price_type) values (:id, :t, 'Corte', 'corte', 'Cabelo', 30, 5000, 'FIXED')",
        ).param("id", it).param("t", tenant).update()
    }

    private fun customer(tenant: UUID = salon, phone: String? = "+5511988887777", name: String = "Ana Souza", anonymised: Boolean = false): UUID =
        UUID.randomUUID().also {
            admin.sql("insert into customer (id, tenant_id, phone, name, anonymised_at) values (:id, :t, :p, :n, case when :a then now() end)")
                .param("id", it).param("t", tenant).param("p", phone).param("n", name).param("a", anonymised).update()
        }

    private fun record(customer: UUID, status: String = "OPEN", payment: String? = null, tenant: UUID = salon): UUID = UUID.randomUUID().also {
        admin.sql(
            """
            insert into service_record (id, tenant_id, customer_id, status, payment_method, closed_at)
            values (:id, :t, :c, :s, :pm, case when :s = 'CLOSED' then now() end)
            """.trimIndent(),
        ).param("id", it).param("t", tenant).param("c", customer).param("s", status).param("pm", payment).update()
    }

    private fun item(record: UUID, price: Long = 5000, svc: UUID = corte, pro: UUID = paula) {
        admin.sql(
            "insert into service_record_item (id, tenant_id, record_id, service_id, professional_id, price_cents) values (:id, :t, :r, :s, :p, :price)",
        ).param("id", UUID.randomUUID()).param("t", salon).param("r", record).param("s", svc).param("p", pro).param("price", price).update()
    }

    @Test
    fun `one customer per phone per salon, the same phone in another salon is fine`() {
        customer()
        customer(tenant = other)

        assertThatThrownBy { customer(name = "Outra") }.rootCause().hasMessageContaining("customer_phone")
    }

    @Test
    fun `an anonymised customer releases the phone and must not keep it`() {
        customer(phone = null, name = "Cliente anonimizado", anonymised = true)
        customer()

        assertThatThrownBy { customer(phone = null, name = "Sem telefone") }.rootCause().hasMessageContaining("check")
    }

    @Test
    fun `search key ignores case and Portuguese accents`() {
        val id = customer(name = "João Conceição")

        assertThat(admin.sql("select search_key from customer where id = :id").param("id", id).query(String::class.java).single())
            .isEqualTo("joao conceicao")
    }

    @Test
    fun `at most one open record per customer`() {
        val ana = customer()
        record(ana)
        record(ana, status = "CLOSED", payment = "PIX")

        assertThatThrownBy { record(ana) }.rootCause().hasMessageContaining("service_record_one_open")
    }

    @Test
    fun `status and its fields go together`() {
        val ana = customer()

        assertThatThrownBy { record(ana, status = "CLOSED") }.rootCause().hasMessageContaining("check")
        assertThatThrownBy { record(ana, status = "OPEN", payment = "PIX") }.rootCause().hasMessageContaining("check")
        assertThatThrownBy {
            admin.sql("insert into service_record (id, tenant_id, customer_id, status, voided_at) values (:id, :t, :c, 'VOIDED', now())")
                .param("id", UUID.randomUUID()).param("t", salon).param("c", ana).update()
        }.rootCause().hasMessageContaining("check")
    }

    @Test
    fun `item price stays between zero and ten thousand reais`() {
        val r = record(customer())
        item(r, price = 0)
        item(r, price = 1_000_000)

        assertThatThrownBy { item(r, price = -1) }.rootCause().hasMessageContaining("check")
        assertThatThrownBy { item(r, price = 1_000_001) }.rootCause().hasMessageContaining("check")
    }

    @Test
    fun `records and items cannot point to another salon's customer, service or professional`() {
        val foreignCustomer = customer(tenant = other)
        val r = record(customer())

        assertThatThrownBy { record(foreignCustomer, tenant = salon) }.rootCause().hasMessageContaining("foreign key")
        assertThatThrownBy { item(r, svc = service(other)) }.rootCause().hasMessageContaining("foreign key")
        assertThatThrownBy { item(r, pro = professional(other)) }.rootCause().hasMessageContaining("foreign key")
    }

    @Test
    fun `the app role sees only its salon's customers, records and read models`() {
        val ana = customer()
        val bia = customer(tenant = other, name = "Bia")
        item(record(ana, status = "CLOSED", payment = "PIX"), price = 8000)
        record(bia, tenant = other)

        val tx = TransactionTemplate(transactionManager)
        fun <T> asSalon(block: () -> T): T = TenantContext.runWith(RequestContext(tenantId = salon)) { tx.execute { block() }!! }

        assertThat(asSalon { appJdbc.sql("select id from customer").query(UUID::class.java).list() }).containsExactly(ana)
        assertThat(asSalon { appJdbc.sql("select customer_id from service_record").query(UUID::class.java).list() }).containsExactly(ana)
        assertThat(asSalon { appJdbc.sql("select customer_id from customer_visit_stats").query(UUID::class.java).list() }).containsExactly(ana)
        assertThat(asSalon { appJdbc.sql("select customer_id from customer_professional").query(UUID::class.java).list() }).containsExactly(ana)
    }
}
