package io.estilix.tenancy

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Role of a person inside one salon. PLATFORM_SUPPORT is not here on purpose: it is not a membership. */
enum class TenantRole { OWNER, MANAGER, PROFESSIONAL }

/**
 * Who is calling and on behalf of which salon, for the duration of one request.
 *
 * - [tenantId] is applied to the database by [TenantTransactionManager]; without it, tenant-owned
 *   tables return nothing (fail closed).
 * - [support] requests run read-only unless [allowWrites] is set by a support use case that must
 *   write (break-glass, audit).
 * - [unmaskedReads] counts personal-data fields serialized unmasked during break-glass.
 */
data class RequestContext(
    val subject: String? = null,
    val userId: UUID? = null,
    val tenantId: UUID? = null,
    val role: TenantRole? = null,
    val support: Boolean = false,
    val breakGlassSessionId: UUID? = null,
    val allowWrites: Boolean = false,
    val unmaskedReads: AtomicInteger = AtomicInteger(),
) {
    val breakGlassActive: Boolean get() = support && breakGlassSessionId != null

    companion object {
        val EMPTY = RequestContext()
    }
}

/**
 * Holds the [RequestContext] of the current thread. Every request runs on its own (virtual) thread
 * and the web filter always clears it in `finally`, so a context never survives a request.
 */
object TenantContext {

    private val holder = ThreadLocal<RequestContext>()

    fun current(): RequestContext = holder.get() ?: RequestContext.EMPTY

    fun set(context: RequestContext) = holder.set(context)

    fun clear() = holder.remove()

    /** Runs [block] with [context], restoring the previous one afterwards. */
    fun <T> runWith(context: RequestContext, block: () -> T): T {
        val previous = holder.get()
        holder.set(context)
        try {
            return block()
        } finally {
            if (previous == null) holder.remove() else holder.set(previous)
        }
    }

    /** Lets a support use case write (it is still bound by RLS and grants). */
    fun <T> withWrites(block: () -> T): T = runWith(current().copy(allowWrites = true), block)
}
