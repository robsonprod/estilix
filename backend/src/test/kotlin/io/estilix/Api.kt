package io.estilix

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Minimal HTTP client for API tests: real HTTP through the whole filter chain. */
class Api(private val baseUrl: String, private val json: JsonMapper) {

    private val http = HttpClient.newHttpClient()

    data class Response(val status: Int, val body: String, val headers: Map<String, List<String>>, private val json: JsonMapper) {
        val node: JsonNode by lazy { if (body.isBlank()) json.createObjectNode() else json.readTree(body) }
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        val code: String? get() = node.path("code").asString(null)
    }

    fun get(path: String, token: String? = null, headers: Map<String, String> = emptyMap()) = send("GET", path, token, null, headers)
    fun post(path: String, token: String?, body: Any? = null) = send("POST", path, token, body)
    fun put(path: String, token: String?, body: Any? = null) = send("PUT", path, token, body)
    fun delete(path: String, token: String?) = send("DELETE", path, token, null)

    fun send(method: String, path: String, token: String?, body: Any?, headers: Map<String, String> = emptyMap()): Response {
        val payload = when (body) {
            null -> HttpRequest.BodyPublishers.noBody()
            is String -> HttpRequest.BodyPublishers.ofString(body)
            else -> HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
        }
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).method(method, payload)
            .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body(), response.headers().map(), json)
    }
}
