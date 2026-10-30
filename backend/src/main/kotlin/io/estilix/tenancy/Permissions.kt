package io.estilix.tenancy

import org.springframework.stereotype.Component

/** Tenant actions. Each feature change adds its own entries here. */
enum class Permission {
    VIEW_MEMBERS,
    MANAGE_MEMBERS,
    VIEW_AUDIT,
    VIEW_ACCESS_HISTORY,
    VIEW_SALON,
    MANAGE_SALON,
    VIEW_SERVICES,
    MANAGE_SERVICES,
    VIEW_PROFESSIONALS,
    MANAGE_PROFESSIONALS,
    VIEW_AGENDA,
    MANAGE_AGENDA,
    MANAGE_BOOKING_RULES,
    VIEW_CUSTOMERS,
    MANAGE_CUSTOMERS,
    MANAGE_MARKETING_CONSENT,
    CUSTOMER_DATA_RIGHTS,
    VIEW_SERVICE_RECORDS,
    MANAGE_SERVICE_RECORDS,
    VOID_SERVICE_RECORDS,
}

/**
 * The single declaration of what each role may do (spec "Role permissions are declared once").
 * The API enforces it with `@PreAuthorize("@access.can('...')")` and `/me` sends it to the web app,
 * which only shows allowed actions.
 */
object PermissionCatalog {

    private val byRole: Map<TenantRole, Set<Permission>> = mapOf(
        TenantRole.OWNER to Permission.entries.toSet(),
        TenantRole.MANAGER to setOf(
            Permission.VIEW_MEMBERS,
            Permission.VIEW_SALON,
            Permission.VIEW_SERVICES,
            Permission.MANAGE_SERVICES,
            Permission.VIEW_PROFESSIONALS,
            Permission.MANAGE_PROFESSIONALS,
            Permission.VIEW_AGENDA,
            Permission.MANAGE_AGENDA,
            Permission.VIEW_CUSTOMERS,
            Permission.MANAGE_CUSTOMERS,
            Permission.MANAGE_MARKETING_CONSENT,
            Permission.VIEW_SERVICE_RECORDS,
            Permission.MANAGE_SERVICE_RECORDS,
            Permission.VOID_SERVICE_RECORDS,
        ),
        // A professional manages only their own agenda; the scheduling module narrows it further.
        TenantRole.PROFESSIONAL to setOf(
            Permission.VIEW_SALON,
            Permission.VIEW_SERVICES,
            Permission.VIEW_PROFESSIONALS,
            Permission.VIEW_AGENDA,
            Permission.MANAGE_AGENDA,
            // Narrowed to the customers and records of their own professional (design D7).
            Permission.VIEW_CUSTOMERS,
            Permission.MANAGE_CUSTOMERS,
            Permission.VIEW_SERVICE_RECORDS,
            Permission.MANAGE_SERVICE_RECORDS,
        ),
    )

    /** Platform support reads (masked); it never manages a salon. */
    val SUPPORT: Set<Permission> = setOf(
        Permission.VIEW_MEMBERS,
        Permission.VIEW_AUDIT,
        Permission.VIEW_ACCESS_HISTORY,
        Permission.VIEW_SALON,
        Permission.VIEW_SERVICES,
        Permission.VIEW_PROFESSIONALS,
        Permission.VIEW_AGENDA,
        Permission.VIEW_CUSTOMERS,
        Permission.VIEW_SERVICE_RECORDS,
    )

    fun of(role: TenantRole): Set<Permission> = byRole.getValue(role)

    fun of(context: RequestContext): Set<Permission> = when {
        context.support -> SUPPORT
        context.role != null -> of(context.role)
        else -> emptySet()
    }
}

/** Bean used in `@PreAuthorize` expressions. */
@Component("access")
class Access {
    fun can(permission: String): Boolean = Permission.valueOf(permission) in PermissionCatalog.of(TenantContext.current())
}
