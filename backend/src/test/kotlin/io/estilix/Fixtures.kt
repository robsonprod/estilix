package io.estilix

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** Test data written with the superuser connection (bypasses RLS on purpose). */
class Fixtures(private val admin: JdbcClient) {

    fun truncateAll() {
        // TRUNCATE does not fire the row-level append-only trigger of audit_event.
        admin.sql(
            "truncate service_record_item, service_record, customer_consent_event, agenda_entry, customer, booking_group, professional_hours, professional_service, professional, service, opening_hours, " +
                "break_glass_session, audit_event, membership, app_user, tenant cascade",
        ).update()
    }

    fun tenant(
        name: String = "Salão ${UUID.randomUUID().toString().take(6)}",
        type: String = "SALON",
        status: String = "ACTIVE",
        createdBy: UUID? = null,
    ): UUID {
        val id = UUID.randomUUID()
        admin.sql(
            """
            insert into tenant (id, name, establishment_type, status, slug, plan_code, created_by)
            values (:id, :name, :type, :status, :slug, 'launch', :createdBy)
            """.trimIndent(),
        )
            .param("id", id).param("name", name).param("type", type).param("status", status)
            .param("slug", "t-" + id.toString().take(8)).param("createdBy", createdBy).update()
        return id
    }

    fun user(subject: String, email: String? = "$subject@example.com", name: String? = null): UUID {
        val id = UUID.randomUUID()
        admin.sql("insert into app_user (id, subject, email, display_name) values (:id, :subject, :email, :name)")
            .param("id", id).param("subject", subject).param("email", email).param("name", name).update()
        return id
    }

    fun member(tenant: UUID, user: UUID, role: String) {
        admin.sql("insert into membership (tenant_id, user_id, role) values (:t, :u, :r)")
            .param("t", tenant).param("u", user).param("r", role).update()
    }

    /** Tenant + user + membership in one call; returns the user id. */
    fun memberOf(tenant: UUID, subject: String, role: String, name: String? = null): UUID =
        user(subject, name = name).also { member(tenant, it, role) }

    fun auditActions(tenant: UUID?): List<String> =
        if (tenant == null) {
            admin.sql("select action from audit_event where tenant_id is null order by occurred_at").query(String::class.java).list().filterNotNull()
        } else {
            admin.sql("select action from audit_event where tenant_id = :t order by occurred_at").param("t", tenant)
                .query(String::class.java).list().filterNotNull()
        }
}
