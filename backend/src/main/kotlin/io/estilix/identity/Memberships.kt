package io.estilix.identity

import io.estilix.audit.AuditAction
import io.estilix.audit.AuditLog
import io.estilix.privacy.Email
import io.estilix.privacy.PersonName
import io.estilix.shared.DomainException
import io.estilix.shared.NotFoundException
import io.estilix.shared.ProblemType
import io.estilix.tenancy.EstablishmentType
import io.estilix.tenancy.TenantRole
import io.estilix.tenancy.TenantStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** A salon of the current user, as listed by `/me`. */
data class UserTenant(
    val tenantId: UUID,
    val name: String,
    val establishmentType: EstablishmentType,
    val status: TenantStatus,
    val role: TenantRole,
)

data class Member(val userId: UUID, val name: PersonName?, val email: Email?, val role: TenantRole, val since: Instant)

@Repository
class MembershipRepository(private val jdbc: JdbcClient) : io.estilix.tenancy.MembershipLookup {

    override fun isMemberOfCurrentTenant(userId: UUID): Boolean = roleOf(userId) != null

    /** Uses the `user_id = app_user_id()` branch of the policies: works before a tenant is chosen. */
    fun tenantsOfCurrentUser(): List<UserTenant> =
        jdbc.sql(
            """
            select t.id, t.name, t.establishment_type, t.status, m.role
            from membership m join tenant t on t.id = m.tenant_id
            where m.user_id = app_user_id()
            order by t.name
            """.trimIndent(),
        ).query { rs, _ ->
            UserTenant(
                tenantId = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                establishmentType = EstablishmentType.valueOf(rs.getString("establishment_type")),
                status = TenantStatus.valueOf(rs.getString("status")),
                role = TenantRole.valueOf(rs.getString("role")),
            )
        }.list()

    fun roleOfCurrentUserIn(tenantId: UUID): TenantRole? =
        jdbc.sql("select role from membership where tenant_id = :tenant and user_id = app_user_id()")
            .param("tenant", tenantId)
            .query(String::class.java).optional().map(TenantRole::valueOf).orElse(null)

    /** Members of the current tenant (RLS); no tenant filter needed. */
    fun members(): List<Member> =
        jdbc.sql(
            """
            select u.id, u.display_name, u.email, m.role, m.created_at
            from membership m join app_user u on u.id = m.user_id
            where m.tenant_id = app_tenant_id() -- the read policy also shows the caller's other salons
            order by u.display_name nulls last, u.id
            """.trimIndent(),
        ).query { rs, _ ->
            Member(
                userId = rs.getObject("id", UUID::class.java),
                name = rs.getString("display_name")?.let(::PersonName),
                email = rs.getString("email")?.let(::Email),
                role = TenantRole.valueOf(rs.getString("role")),
                since = rs.getTimestamp("created_at").toInstant(),
            )
        }.list()

    fun roleOf(userId: UUID): TenantRole? =
        jdbc.sql("select role from membership where tenant_id = app_tenant_id() and user_id = :user").param("user", userId)
            .query(String::class.java).optional().map(TenantRole::valueOf).orElse(null)

    /** Locks the owner rows of the current tenant so two owners cannot demote each other at once. */
    fun lockOwners(): List<UUID> =
        jdbc.sql("select user_id from membership where tenant_id = app_tenant_id() and role = 'OWNER' order by user_id for update")
            .query(UUID::class.java).list().filterNotNull()

    fun add(tenantId: UUID, userId: UUID, role: TenantRole) {
        jdbc.sql("insert into membership (tenant_id, user_id, role) values (:tenant, :user, :role)")
            .param("tenant", tenantId).param("user", userId).param("role", role.name).update()
    }

    fun changeRole(userId: UUID, role: TenantRole): Int =
        jdbc.sql("update membership set role = :role where tenant_id = app_tenant_id() and user_id = :user")
            .param("user", userId).param("role", role.name).update()

    fun remove(userId: UUID): Int =
        jdbc.sql("delete from membership where tenant_id = app_tenant_id() and user_id = :user").param("user", userId).update()
}

/** Membership management, OWNER only (spec "Only owners manage memberships"). */
@Service
class MembershipService(
    private val memberships: MembershipRepository,
    private val users: UserRepository,
    private val audit: AuditLog,
) {

    @Transactional(readOnly = true)
    fun members(): List<Member> = memberships.members()

    @Transactional
    fun add(tenantId: UUID, email: String, role: TenantRole): Member {
        val userId = users.findIdByEmail(email) ?: throw NotFoundException("Nenhum usuário cadastrado com este e-mail")
        try {
            memberships.add(tenantId, userId, role)
        } catch (_: DuplicateKeyException) {
            throw DomainException(ProblemType.CONFLICT, "Esta pessoa já faz parte da equipe")
        }
        audit.record(AuditAction.MEMBERSHIP_ADDED, "user", userId, mapOf("role" to role.name))
        return memberships.members().first { it.userId == userId }
    }

    @Transactional
    fun changeRole(userId: UUID, role: TenantRole) {
        val current = memberships.roleOf(userId) ?: throw NotFoundException("Membro não encontrado")
        if (current == role) return
        if (current == TenantRole.OWNER) ensureAnotherOwner()
        memberships.changeRole(userId, role)
        audit.record(AuditAction.MEMBERSHIP_ROLE_CHANGED, "user", userId, mapOf("from" to current.name, "to" to role.name))
    }

    @Transactional
    fun remove(userId: UUID) {
        val current = memberships.roleOf(userId) ?: throw NotFoundException("Membro não encontrado")
        if (current == TenantRole.OWNER) ensureAnotherOwner()
        memberships.remove(userId)
        audit.record(AuditAction.MEMBERSHIP_REMOVED, "user", userId, mapOf("role" to current.name))
    }

    private fun ensureAnotherOwner() {
        if (memberships.lockOwners().size <= 1) {
            throw DomainException(ProblemType.LAST_OWNER, "Promova outra pessoa a dona antes de sair ou mudar de papel")
        }
    }
}
