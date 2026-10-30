package io.estilix

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * Task 1.6 — module boundaries are checked by the build: no cycles, no access to another module's
 * internal packages. Dependency direction (design D12):
 * onboarding -> identity, salon; identity -> salon; identity/support/salon -> audit, privacy, tenancy;
 * audit/privacy -> tenancy; everyone -> shared.
 */
class ModularityTests {

    private val modules = ApplicationModules.of(EstilixApplication::class.java)

    @Test
    fun `module boundaries are respected`() {
        modules.verify()
    }

    @Test
    fun `expected modules exist`() {
        assertThat(modules.map { it.identifier.toString() })
            .containsExactlyInAnyOrder("shared", "tenancy", "identity", "support", "audit", "privacy", "salon", "onboarding")
    }

    @Test
    fun `write module documentation`() {
        Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml()
    }
}
