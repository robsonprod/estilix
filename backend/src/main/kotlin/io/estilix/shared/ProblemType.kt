package io.estilix.shared

/**
 * Stable API error types (RFC 9457). Clients may depend on [code]; titles are shown to users,
 * so they are in pt-BR.
 */
enum class ProblemType(val status: Int, val code: String, val title: String) {
    VALIDATION(400, "validation", "Dados inválidos"),
    UNAUTHORIZED(401, "unauthorized", "Sessão inválida ou expirada"),
    FORBIDDEN(403, "forbidden", "Você não tem permissão para esta ação"),
    NOT_FOUND(404, "not-found", "Não encontrado"),
    LAST_OWNER(409, "last-owner", "O salão precisa de pelo menos um dono"),
    CONFLICT(409, "conflict", "Conflito com o estado atual"),
    TENANT_SUSPENDED(403, "tenant-suspended", "Este salão está suspenso"),
    TENANT_PENDING_APPROVAL(403, "tenant-pending-approval", "Seu salão está em análise"),
    TENANT_REJECTED(403, "tenant-rejected", "O cadastro do salão precisa de ajustes"),
    INTERNAL(500, "internal", "Erro inesperado"),
}

/** A business rule was violated; translated into Problem Details by the web layer. */
open class DomainException(
    val type: ProblemType,
    message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
) : RuntimeException(message)

class NotFoundException(message: String = "Recurso não encontrado") : DomainException(ProblemType.NOT_FOUND, message)

class ForbiddenException(message: String = "Ação não permitida") : DomainException(ProblemType.FORBIDDEN, message)
