package io.estilix.shared.web

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Test-only endpoint that fails unexpectedly while handling a body full of personal data. */
@RestController
class FailingTestController {

    data class Payload(val name: String?, val email: String?, val phone: String?)

    @PostMapping("/api/v1/test/boom")
    fun boom(@RequestBody payload: Payload): Nothing = throw IllegalStateException("database exploded")
}
