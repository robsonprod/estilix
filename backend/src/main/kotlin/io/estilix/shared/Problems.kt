package io.estilix.shared

import io.estilix.shared.web.CorrelationIdFilter
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import java.net.URI

/** Builds RFC 9457 bodies. Never puts submitted values in them (spec personal-data-protection). */
object Problems {

    fun of(type: ProblemType, detail: String, fieldErrors: Map<String, String> = emptyMap()): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(type.status), detail)
        pd.type = URI.create("https://estilix.io/problems/${type.code}")
        pd.title = type.title
        pd.setProperty("code", type.code)
        if (fieldErrors.isNotEmpty()) pd.setProperty("errors", fieldErrors)
        MDC.get(CorrelationIdFilter.MDC_KEY)?.let { pd.setProperty("correlationId", it) }
        return pd
    }
}
