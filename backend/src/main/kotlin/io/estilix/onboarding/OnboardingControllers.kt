package io.estilix.onboarding

import io.estilix.salon.Presets
import io.estilix.salon.Prices
import io.estilix.salon.ServicePreset
import io.estilix.salon.Slugs
import io.estilix.salon.TimeZones
import io.estilix.salon.WeeklyHours
import io.estilix.tenancy.EstablishmentType
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

data class PlanView(val code: String, val name: String, val description: String, val price: String, val priceLabel: String, val features: List<String>)

data class SlugAvailability(val slug: String, val valid: Boolean, val available: Boolean, val problem: String?)

data class PresetView(val name: String, val category: String, val durationMinutes: Int, val price: String, val priceType: String)

data class WizardDefaults(
    val hours: List<io.estilix.salon.HoursInterval>,
    val timeZone: String,
    val timeZones: List<String>,
    val termsVersion: String,
    val termsText: String,
)

/**
 * Public endpoints (spec marketing/public-site): plan catalog, slug availability (yes/no only),
 * presets and wizard defaults. Nothing here reveals data of an existing salon or person.
 */
@RestController
@RequestMapping("/api/v1/public")
class PublicController(private val jdbc: JdbcClient, private val json: JsonMapper) {

    @GetMapping("/plans")
    @Transactional(readOnly = true)
    fun plans(): ResponseEntity<List<PlanView>> {
        val plans = jdbc.sql("select code, name, description, price_cents, features::text from plan where active order by position")
            .query { rs, _ ->
                val cents = rs.getLong("price_cents")
                @Suppress("UNCHECKED_CAST")
                PlanView(
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                    price = Prices.fromCents(cents).toPlainString(),
                    priceLabel = Prices.label(cents, io.estilix.salon.PriceType.FIXED) + "/mês",
                    features = json.readValue(rs.getString("features"), List::class.java) as List<String>,
                )
            }.list()
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(plans)
    }

    @GetMapping("/slugs/{slug}")
    @Transactional(readOnly = true)
    fun slug(@PathVariable slug: String): SlugAvailability {
        val problem = Slugs.problem(slug)
        val available = problem == null &&
            jdbc.sql("select slug_available(:s)").param("s", slug).query(Boolean::class.java).single()
        return SlugAvailability(slug, problem == null, available, problem)
    }

    @GetMapping("/slugs")
    fun suggest(@RequestParam name: String): SlugAvailability {
        val suggestion = Slugs.suggest(name)
        return slug(suggestion)
    }

    @GetMapping("/presets")
    fun presets(@RequestParam type: EstablishmentType): List<PresetView> = Presets.of(type).map(::view)

    @GetMapping("/wizard-defaults")
    fun defaults() = WizardDefaults(WeeklyHours.DEFAULT, "America/Sao_Paulo", TimeZones.BRAZIL, Terms.VERSION, Terms.TEXT)

    private fun view(p: ServicePreset) = PresetView(p.name, p.category, p.durationMinutes, p.price.toPlainString(), p.priceType.name)
}

/** The signed-in person's own sign-up. */
@RestController
@RequestMapping("/api/v1/onboarding")
class OnboardingController(private val onboarding: OnboardingService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@RequestBody request: OnboardingRequest): OnboardingResult = onboarding.submit(request)

    /** 204 when the person has no salon in analysis or rejected. */
    @GetMapping("/status")
    fun status(): ResponseEntity<Application> =
        onboarding.current()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @PutMapping("/{tenantId}")
    fun resubmit(@PathVariable tenantId: UUID, @RequestBody request: OnboardingRequest): OnboardingResult =
        onboarding.resubmit(tenantId, request)
}

data class RejectRequest(val reason: String?)

/** Approval queue; every path under `/api/v1/support/` already requires PLATFORM_SUPPORT. */
@RestController
@RequestMapping("/api/v1/support/approvals")
class ApprovalsController(private val approvals: ApprovalService) {

    @GetMapping
    fun queue(): List<PendingSalon> = approvals.queue()

    @PostMapping("/{tenantId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun approve(@PathVariable tenantId: UUID) = approvals.approve(tenantId)

    @PostMapping("/{tenantId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reject(@PathVariable tenantId: UUID, @RequestBody request: RejectRequest) = approvals.reject(tenantId, request.reason)
}
