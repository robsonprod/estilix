package io.estilix.identity

import io.estilix.shared.ProblemType
import io.estilix.shared.Problems
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.json.JsonMapper

/**
 * The token proves WHO the caller is (design D6). Tenant roles live in the database; only
 * PLATFORM_SUPPORT comes from the identity provider (Cognito group), so no salon user can grant it.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfiguration(
    @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") private val issuer: String,
    @Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") private val jwkSetUri: String,
    @Value("\${estilix.security.client-id:}") private val clientId: String,
    @Value("\${estilix.security.support-group}") private val supportGroup: String,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity, currentUserFilter: CurrentUserFilter, json: JsonMapper): SecurityFilterChain {
        http
            .csrf { it.disable() } // bearer tokens only, no cookies
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                it.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                it.requestMatchers("/api/v1/public/**").permitAll()
                it.requestMatchers("/api/v1/support/**").hasRole("PLATFORM_SUPPORT")
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { rs ->
                rs.jwt { it.jwtAuthenticationConverter(authenticationConverter()) }
                rs.authenticationEntryPoint { _, response, _ ->
                    write(response, json, ProblemType.UNAUTHORIZED, "Faça login novamente")
                }
                rs.accessDeniedHandler { _, response, _ ->
                    write(response, json, ProblemType.FORBIDDEN, "Seu papel não permite esta ação")
                }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> write(response, json, ProblemType.UNAUTHORIZED, "Faça login novamente") }
                it.accessDeniedHandler { _, response, _ -> write(response, json, ProblemType.FORBIDDEN, "Seu papel não permite esta ação") }
            }
            .addFilterAfter(currentUserFilter, BearerTokenAuthenticationFilter::class.java)
        return http.build()
    }

    /**
     * JWKS from [jwkSetUri] (may be an internal address) but the issuer claim must match [issuer]
     * (the address the browser used). The app may be named in `aud`, `azp` or `client_id`.
     */
    @Bean
    fun jwtDecoder(): JwtDecoder {
        val decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
        val validators = mutableListOf<OAuth2TokenValidator<Jwt>>(JwtValidators.createDefaultWithIssuer(issuer))
        if (clientId.isNotBlank()) validators += audienceValidator(clientId)
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(validators))
        return decoder
    }

    private fun authenticationConverter() = JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter { jwt ->
            val groups = jwt.getClaimAsStringList(GROUPS_CLAIM) ?: emptyList()
            if (supportGroup in groups) listOf(SimpleGrantedAuthority("ROLE_PLATFORM_SUPPORT")) else emptyList()
        }
    }

    private fun write(response: HttpServletResponse, json: JsonMapper, type: ProblemType, detail: String) {
        response.status = type.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        json.writeValue(response.outputStream, Problems.of(type, detail))
    }

    companion object {
        const val GROUPS_CLAIM = "cognito:groups"

        fun audienceValidator(clientId: String) = OAuth2TokenValidator<Jwt> { jwt ->
            // OIDC providers name the app differently: `aud` (ID tokens), `azp` (authorized party,
            // e.g. mock-oauth2-server) or `client_id` (Cognito access tokens).
            val ok = clientId in (jwt.audience ?: emptyList()) ||
                jwt.getClaimAsString("azp") == clientId ||
                jwt.getClaimAsString("client_id") == clientId
            if (ok) OAuth2TokenValidatorResult.success()
            else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Token não emitido para o Estilix", null))
        }
    }
}
