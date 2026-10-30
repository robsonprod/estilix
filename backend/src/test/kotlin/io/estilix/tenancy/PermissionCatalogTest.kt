package io.estilix.tenancy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Spec identity/access-control, "Role permissions are declared once". */
class PermissionCatalogTest {

    @Test
    fun `owner can do everything in the foundation`() {
        assertThat(PermissionCatalog.of(TenantRole.OWNER)).containsExactlyInAnyOrder(*Permission.entries.toTypedArray())
    }

    @Test
    fun `manager runs the salon's catalog but cannot manage the team, the salon profile nor read audit`() {
        val manager = PermissionCatalog.of(TenantRole.MANAGER)
        assertThat(manager).contains(Permission.VIEW_MEMBERS, Permission.MANAGE_SERVICES, Permission.MANAGE_PROFESSIONALS, Permission.MANAGE_AGENDA)
        assertThat(manager).doesNotContain(Permission.MANAGE_MEMBERS, Permission.MANAGE_SALON, Permission.VIEW_AUDIT)
    }

    @Test
    fun `professional reads the catalog and works on the agenda`() {
        assertThat(PermissionCatalog.of(TenantRole.PROFESSIONAL)).containsExactlyInAnyOrder(
            Permission.VIEW_SALON, Permission.VIEW_SERVICES, Permission.VIEW_PROFESSIONALS, Permission.VIEW_AGENDA, Permission.MANAGE_AGENDA,
        )
    }

    @Test
    fun `only owners change booking rules`() {
        assertThat(PermissionCatalog.of(TenantRole.MANAGER)).doesNotContain(Permission.MANAGE_BOOKING_RULES)
        assertThat(PermissionCatalog.of(TenantRole.OWNER)).contains(Permission.MANAGE_BOOKING_RULES)
    }

    @Test
    fun `support only reads`() {
        val support = PermissionCatalog.of(RequestContext(support = true))
        assertThat(support).doesNotContain(Permission.MANAGE_MEMBERS, Permission.MANAGE_SALON, Permission.MANAGE_SERVICES, Permission.MANAGE_PROFESSIONALS, Permission.MANAGE_AGENDA)
        assertThat(support).contains(Permission.VIEW_MEMBERS, Permission.VIEW_AUDIT, Permission.VIEW_ACCESS_HISTORY)
    }

    @Test
    fun `no context means no permission`() {
        assertThat(PermissionCatalog.of(RequestContext.EMPTY)).isEmpty()
    }
}
