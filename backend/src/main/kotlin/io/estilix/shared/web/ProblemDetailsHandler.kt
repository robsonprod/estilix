package io.estilix.shared.web

import io.estilix.shared.DomainException
import io.estilix.shared.ProblemType
import io.estilix.shared.Problems
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authorization.AuthorizationDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Translates errors into RFC 9457 Problem Details with a stable `code`.
 * Rule: field errors name the field and the rule, never the rejected value, and nothing from the
 * request body is logged (spec platform/personal-data-protection).
 */
@RestControllerAdvice
class ProblemDetailsHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun domain(ex: DomainException): ProblemDetail {
        log.info("Request rejected: {}", ex.type.code)
        return Problems.of(ex.type, ex.message ?: ex.type.title, ex.fieldErrors)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(ex: MethodArgumentNotValidException): ProblemDetail {
        val errors = linkedMapOf<String, String>()
        ex.bindingResult.fieldErrors.forEach { errors.putIfAbsent(it.field, it.defaultMessage ?: "inválido") }
        return Problems.of(ProblemType.VALIDATION, "Um ou mais campos são inválidos", errors)
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun invalidParameters(ex: HandlerMethodValidationException): ProblemDetail {
        val errors = linkedMapOf<String, String>()
        ex.parameterValidationResults.forEach {
            errors.putIfAbsent(
                it.methodParameter.parameterName ?: "parameter",
                it.resolvableErrors.firstOrNull()?.defaultMessage ?: "inválido",
            )
        }
        return Problems.of(ProblemType.VALIDATION, "Um ou mais parâmetros são inválidos", errors)
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(ex: MissingServletRequestParameterException): ProblemDetail =
        Problems.of(ProblemType.VALIDATION, "Parâmetro obrigatório ausente", mapOf(ex.parameterName to "obrigatório"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail =
        Problems.of(ProblemType.VALIDATION, "Formato inválido", mapOf(ex.name to "formato inválido"))

    // The default message of this exception quotes part of the body, so it is replaced entirely.
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(ex: HttpMessageNotReadableException): ProblemDetail =
        Problems.of(ProblemType.VALIDATION, "Corpo da requisição ilegível ou com campo inválido")

    @ExceptionHandler(AccessDeniedException::class, AuthorizationDeniedException::class)
    fun forbidden(ex: Exception): ProblemDetail =
        Problems.of(ProblemType.FORBIDDEN, "Seu papel não permite esta ação")

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResource(ex: NoResourceFoundException): ProblemDetail =
        Problems.of(ProblemType.NOT_FOUND, "Recurso não encontrado")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    fun methodNotAllowed(ex: HttpRequestMethodNotSupportedException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Método não suportado")
        pd.title = "Método não suportado"
        return pd
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ProblemDetail {
        // Type + stack trace for diagnosis; the request body is never logged.
        log.error("Unexpected error: {}", ex.javaClass.name, ex)
        return Problems.of(ProblemType.INTERNAL, "Algo deu errado. Informe o código de correlação ao suporte.")
    }
}
