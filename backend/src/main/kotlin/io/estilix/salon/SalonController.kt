package io.estilix.salon

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class WeekRequest(val intervals: List<HoursInterval>?)

data class WeekView(val intervals: List<HoursInterval>)

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/salon")
class SalonController(private val service: SalonService) {

    @GetMapping
    @PreAuthorize("@access.can('VIEW_SALON')")
    fun profile(@PathVariable tenantId: UUID): SalonProfile = service.profile()

    @PutMapping
    @PreAuthorize("@access.can('MANAGE_SALON')")
    fun update(@PathVariable tenantId: UUID, @RequestBody request: SalonProfileUpdate): SalonProfile = service.updateProfile(request)

    @GetMapping("/hours")
    @PreAuthorize("@access.can('VIEW_SALON')")
    fun hours(@PathVariable tenantId: UUID): WeekView = WeekView(service.hours())

    /** Replaces the whole week; a day without intervals is closed. */
    @PutMapping("/hours")
    @PreAuthorize("@access.can('MANAGE_SALON')")
    fun replaceHours(@PathVariable tenantId: UUID, @RequestBody request: WeekRequest): WeekView =
        WeekView(service.replaceHours(request.intervals ?: emptyList()))
}
