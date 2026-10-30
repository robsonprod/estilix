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
        assertThat(manager).contains(Permission.VIEW_MEMBERS, Permission.MANAGE_SERVICES, Permission.MANAGE_PROFESSIONALS)
        assertThat(manager).doesNotContain(Permission.MANAGE_MEMBERS, Permission.MANAGE_SALON, Permission.VIEW_AUDIT)
    }

    @Test
    fun `professional only reads the salon, services and professionals`() {
        assertThat(PermissionCatalog.of(TenantRole.PROFESSIONAL))
            .containsExactlyInAnyOrder(Permission.VIEW_SALON, Permission.VIEW_SERVICES, Permission.VIEW_PROFESSIONALS)
    }

    @Test
    fun `support only reads`() {
        val support = PermissionCatalog.of(RequestContext(support = true))
        assertThat(support).doesNotContain(Permission.MANAGE_MEMBERS, Permission.MANAGE_SALON, Permission.MANAGE_SERVICES, Permission.MANAGE_PROFESSIONALS)
        assertThat(support).contains(Permission.VIEW_MEMBERS, Permission.VIEW_AUDIT, Permission.VIEW_ACCESS_HISTORY)
    }

    @Test
    fun `no context means no permission`() {
        assertThat(PermissionCatalog.of(RequestContext.EMPTY)).isEmpty()
    }
}
