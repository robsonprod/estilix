package io.estilix.servicerecords

import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/service-records")
class ServiceRecordsController(private val service: ServiceRecordService) {

    @GetMapping
    @PreAuthorize("@access.can('VIEW_SERVICE_RECORDS')")
    fun day(@PathVariable tenantId: UUID, @RequestParam date: LocalDate): DayRecords = service.day(date)

    @GetMapping("/{id}")
    @PreAuthorize("@access.can('VIEW_SERVICE_RECORDS')")
    fun get(@PathVariable tenantId: UUID, @PathVariable id: UUID): RecordView = service.get(id)

    @GetMapping("/by-agenda-entry/{entryId}")
    @PreAuthorize("@access.can('VIEW_SERVICE_RECORDS')")
    fun byEntry(@PathVariable tenantId: UUID, @PathVariable entryId: UUID): RecordView = service.byAgendaEntry(entryId)

    @PostMapping("/from-booking/{entryId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('MANAGE_SERVICE_RECORDS')")
    fun start(@PathVariable tenantId: UUID, @PathVariable entryId: UUID): RecordView = service.startFromBooking(entryId)

    @PostMapping("/walk-in")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('MANAGE_SERVICE_RECORDS')")
    fun walkIn(@PathVariable tenantId: UUID, @RequestBody request: WalkInRequest): RecordView = service.walkIn(request)

    @PostMapping("/{id}/items")
    @PreAuthorize("@access.can('MANAGE_SERVICE_RECORDS')")
    fun addItem(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: ItemRequest): RecordView = service.addItem(id, request)

    @PutMapping("/{id}/items/{itemId}")
    @PreAuthorize("@access.can('MANAGE_SERVICE_RECORDS')")
    fun updatePrice(@PathVariable tenantId: UUID, @PathVariable id: UUID, @PathVariable itemId: UUID, @RequestBody request: PriceRequest): RecordView =
        service.updatePrice(id, itemId, request)

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize("@access.can('MANAGE_SERVICE_RECORDS')")
    fun removeItem(@PathVariable tenantId: UUID, @PathVariable id: UUID, @PathVariable itemId: UUID): RecordView = service.removeItem(id, itemId)

    @PostMapping("/{id}/close")
    @PreAuthorize("@access.can('MANAGE_SERVICE_RECORDS')")
    fun close(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: CloseRequest): RecordView = service.close(id, request)

    @PostMapping("/{id}/void")
    @PreAuthorize("@access.can('VOID_SERVICE_RECORDS')")
    fun void(@PathVariable tenantId: UUID, @PathVariable id: UUID, @RequestBody request: VoidRequest): RecordView = service.void(id, request)
}
