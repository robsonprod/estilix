# ADR 0004 — Tenant lifecycle guarded by the database; self-service onboarding with approval

**Status:** accepted (change `salon-setup`)

## Context

Salons arrive through the public site: plans page (R$ 0 during launch) → account → 3-step wizard. The platform owner wants to verify every salon before it goes live. The application must be able to create salons, but must never be able to make one live by itself.

## Decision

- **Status column + state machine in a trigger.** `tenant.status` is `PENDING_APPROVAL → ACTIVE | REJECTED`, `REJECTED → PENDING_APPROVAL` (resubmission by the creator), `ACTIVE ↔ SUSPENDED`. A `BEFORE UPDATE` trigger rejects any other transition coming from the application role, and allows the support-only ones only when the transaction runs as platform support.
- **Creation under RLS without new privileges.** The onboarding service generates the tenant id first and runs the whole submission inside that id's context. The `tenant_insert` policy only accepts `status = 'PENDING_APPROVAL'`, `id = app_tenant_id()` and `created_by = app_user_id()`. Membership, hours and services use the existing tenant policies, all in one transaction.
- **One open application per person** via a partial unique index on `tenant (created_by) where status in ('PENDING_APPROVAL', 'REJECTED')`. Two concurrent submissions: the second one gets a conflict.
- **Decisions as conditional UPDATEs** (`... where status = 'PENDING_APPROVAL'`). Two support users deciding at once: one row matches, the other gets a conflict. Each decision is audited.
- **Purpose-bound contact data.** The approval queue shows the owner's name, e-mail and the salon phone unmasked, because verifying the salon before the contract needs them (LGPD pre-contractual procedures). Every queue view is audited. After approval the contact snapshot is cleared, and these fields are masked for support everywhere else.

## Alternatives considered

| Option | Why not |
|---|---|
| Separate `tenant_application` table, copied into `tenant` on approval | Duplicates every salon field; a copy step that can fail halfway. |
| `SECURITY DEFINER` function `create_tenant(...)` | Business validation moves to PL/pgSQL, away from tested Kotlin code. |
| Status checks only in Kotlin | One wrong `UPDATE` could approve a salon; the trigger makes it impossible. |
| Break-glass to see contact data during approval | Approval is routine, not an emergency; it would make break-glass a daily habit. |

## Consequences

- The database refuses "owner approves own salon" and "app creates an active salon" — proven by `TenantLifecycleDbTest`.
- Composite foreign keys `(tenant_id, service_id)` and `(tenant_id, professional_id)` make cross-salon links impossible to store, beyond what RLS hides.
- The trigger binds the application role only; migrations and database operators (trusted, like for RLS) can still fix data.
