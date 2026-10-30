package io.estilix.tenancy

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class TenantStatus { PENDING_APPROVAL, REJECTED, ACTIVE, SUSPENDED }

enum class EstablishmentType { SALON, BARBERSHOP }

data class Tenant(
    val id: UUID,
    val name: String,
    val establishmentType: EstablishmentType,
    val status: TenantStatus,
    val createdAt: Instant,
)

/** Reads tenants. Which rows are visible is decided by the `tenant_visible` RLS policy. */
@Repository
class TenantRepository(private val jdbc: JdbcClient) {

    fun find(id: UUID): Tenant? =
        jdbc.sql("$SELECT where id = :id").param("id", id).query(::map).optional().orElse(null)

    fun findAll(): List<Tenant> = jdbc.sql("$SELECT order by name").query(::map).list()

    private fun map(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Tenant(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        establishmentType = EstablishmentType.valueOf(rs.getString("establishment_type")),
        status = TenantStatus.valueOf(rs.getString("status")),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    private companion object {
        const val SELECT = "select id, name, establishment_type, status, created_at from tenant"
    }
}
