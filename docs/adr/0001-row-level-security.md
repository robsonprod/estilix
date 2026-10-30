# ADR 0001 — Tenant isolation with PostgreSQL Row-Level Security

**Status:** accepted (change `platform-foundation`)

## Context

All salons share one PostgreSQL database. A single forgotten `WHERE tenant_id = ?` in any future query would leak one salon's customers to another — an LGPD incident, and the end of the product's credibility.

## Decision

Every tenant-owned table has `tenant_id`, `ENABLE` + `FORCE ROW LEVEL SECURITY` and a policy comparing it with `app_tenant_id()`. The transaction manager (`TenantTransactionManager.prepareTransactionalConnection`) runs `set_config('app.tenant_id', ?, true)` right after `BEGIN`; the setting is transaction-local, so it disappears at COMMIT/ROLLBACK and never leaks through the connection pool. The application connects as `estilix_app`, which does not own the tables and has no `BYPASSRLS`.

## Alternatives considered

| Option | Why not |
|---|---|
| Filter in application code only | One omission leaks data; nothing catches it. |
| Schema per tenant | N migrations per deploy, search_path juggling, painful support queries. |
| Database per tenant | Highest cost; breaks the "AWS idle for cents" goal. |
| Session-level `SET` in a servlet filter | Leaks to the next borrower of a pooled connection if a reset is missed. |

## Consequences

- Without a tenant, queries return nothing (fail closed). Code that runs outside a transaction sees no tenant data — intended.
- Policies with an `OR` branch widen reads: the membership read policy also shows the caller's own memberships in other salons, so queries that list "this salon's members" still add `tenant_id = app_tenant_id()` explicitly.
- Guarded by tests: `RlsGuardTest` fails if any table with `tenant_id` lacks RLS/FORCE/policy; `TenantIsolationTest` proves cross-tenant reads, writes and inserts fail even without a `WHERE`, including on a single reused connection.
- RLS protects against bugs, not against an attacker running code inside the API (who could set any tenant). Least privilege, read-only support transactions and the audit log cover that side.
