package io.estilix.identity

import io.estilix.audit.AuditAction
import io.estilix.audit.AuditLog
import io.estilix.privacy.Email
import io.estilix.privacy.PersonName
import io.estilix.privacy.Phone
import io.estilix.tenancy.TenantContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class AppUser(
    val id: UUID,
    val subject: String,
    val email: Email?,
    val name: PersonName?,
    val phone: Phone?,
)

/** Identity facts taken from the token; only used to fill the profile on first sign-in. */
data class TokenIdentity(val subject: String, val email: String?, val name: String?)

@Repository
class UserRepository(private val jdbc: JdbcClient) {

    fun findBySubject(subject: String): AppUser? =
        jdbc.sql("$SELECT where subject = :subject").param("subject", subject).query(::map).optional().orElse(null)

    fun findById(id: UUID): AppUser? =
        jdbc.sql("$SELECT where id = :id").param("id", id).query(::map).optional().orElse(null)

    /** Returns the new id, or null when another request created the same subject first. */
    fun insertIfAbsent(identity: TokenIdentity): UUID? =
        jdbc.sql(
            """
            insert into app_user (id, subject, email, display_name) values (:id, :subject, :email, :name)
            on conflict (subject) do nothing
            returning id
            """.trimIndent(),
        )
            .param("id", UUID.randomUUID())
            .param("subject", identity.subject)
            .param("email", identity.email)
            .param("name", identity.name)
            .query(UUID::class.java)
            .optional().orElse(null)

    fun updateProfile(id: UUID, name: String, phone: String?) {
        jdbc.sql("update app_user set display_name = :name, phone = :phone where id = :id")
            .param("id", id).param("name", name).param("phone", phone).update()
    }

    fun findIdByEmail(email: String): UUID? =
        jdbc.sql("select find_user_id_by_email(:email)").param("email", email)
            .query(UUID::class.java).optional().orElse(null)

    private fun map(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = AppUser(
        id = rs.getObject("id", UUID::class.java),
        subject = rs.getString("subject"),
        email = rs.getString("email")?.let(::Email),
        name = rs.getString("display_name")?.let(::PersonName),
        phone = rs.getString("phone")?.let(::Phone),
    )

    private companion object {
        const val SELECT = "select id, subject, email, display_name, phone from app_user"
    }
}

/** Links an identity-provider subject to exactly one internal user (spec "Users are provisioned on first sign-in"). */
@Service
class UserProvisioning(private val users: UserRepository, private val audit: AuditLog) {

    /** Must run with the subject already in [TenantContext], which the insert policy checks. */
    @Transactional
    fun resolve(identity: TokenIdentity): UUID {
        users.findBySubject(identity.subject)?.let { return it.id }
        val created = users.insertIfAbsent(identity)
        if (created != null) {
            TenantContext.runWith(TenantContext.current().copy(userId = created)) {
                audit.record(AuditAction.USER_FIRST_SIGN_IN, "user", created, tenantId = null)
            }
            return created
        }
        // Lost the race: the other request committed first (ON CONFLICT waited for it).
        return users.findBySubject(identity.subject)?.id
            ?: error("user provisioning lost the race but cannot see the winner")
    }
}
