package io.estilix.tenancy

import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import java.util.UUID

/** Active break-glass access of platform support on one salon. */
data class ActiveSupportAccess(val sessionId: UUID, val reason: String, val expiresAt: Instant)

/**
 * Implemented by the support module. Kept here so request resolution (identity) can ask for it
 * without depending on support, which keeps the module graph acyclic.
 */
interface BreakGlassLookup {
    /** Session of [supportUserId] on the current tenant; must run inside a tenant transaction. */
    fun activeSessionOf(supportUserId: UUID): ActiveSupportAccess?

    /** Any active session on the current tenant (shown to owners); must run in a tenant transaction. */
    fun anyActiveSession(): ActiveSupportAccess?
}

/** Called once per request after the response body was written, with the final context. */
interface RequestCompletionHook {
    fun afterRequest(context: RequestContext, request: HttpServletRequest)
}

/** Implemented by identity; lets the salon module check membership without depending on identity. */
interface MembershipLookup {
    /** Whether [userId] is a member of the current tenant; must run in a tenant transaction. */
    fun isMemberOfCurrentTenant(userId: UUID): Boolean
}
