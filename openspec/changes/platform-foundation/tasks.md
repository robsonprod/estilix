# Tasks

## 1. Repository scaffolding

- [x] 1.1 Create `backend/` with Gradle Kotlin DSL, Kotlin (JVM target 25, Java 25 toolchain), Spring Boot 4 (webmvc, security, oauth2-resource-server, jdbc, validation, actuator), Spring Modulith, Flyway, PostgreSQL driver, springdoc-openapi, Testcontainers, kotest/AssertJ; verify `./gradlew build` passes on an empty application and record the effective `jvmTarget` in the README
- [x] 1.2 Add `docker-compose.yml` with PostgreSQL 17 (init script creating `estilix_owner` and `estilix_app` roles), mock-oauth2-server, the API (multi-stage Dockerfile) and the web app; verify `docker compose up` starts all four and `GET /actuator/health` returns `UP`
- [x] 1.3 Configure `local` and `test` profiles, virtual threads, ECS JSON logging with MDC (correlation id, tenant id, user id) and Flyway running as `estilix_owner` while the app connects as `estilix_app`; verify with a startup test that asserts the runtime connection user is `estilix_app` and has no `BYPASSRLS`
- [x] 1.4 Create `frontend/` with Angular latest stable (standalone, zoneless, signals), pt-BR locale, ESLint and a mobile-first empty shell; verify `npm run build` and `npm test` pass
- [ ] 1.5 Add a GitHub Actions workflow running backend `./gradlew check` and frontend lint/test/build on pull requests and pushes to `main`; verify it passes on the scaffold
- [x] 1.6 Add the Spring Modulith verification test for `tenancy`, `identity`, `support`, `audit`, `privacy`; verify it passes and fails when `tenancy` imports from `identity`
- [x] 1.7 Add `scripts/local.sh up|down|status|logs` (Git Bash, Linux, macOS) wrapping the `core` Compose profile, with `down --wipe` to remove volumes; verify each subcommand on a clean clone and document it in the README

## 2. Errors and request context

- [x] 2.1 Implement the RFC 9457 handler with stable types (`validation`, `not-found`, `forbidden`, `unauthorized`, `tenant-suspended`, `last-owner`) that never includes rejected values; verify with a web test per type
- [x] 2.2 Add the correlation-id filter (`X-Request-Id`, generated when absent, sanitized, put in MDC and echoed); verify with a web test including a log-forging attempt
- [x] 2.3 Expose liveness (`/actuator/health/liveness`, process only) and readiness (`/actuator/health/readiness`, includes the database) with no details for anonymous callers; verify readiness turns `DOWN` (503) when the Testcontainers database is paused while liveness stays `UP`

## 3. Tenancy and Row-Level Security

- [x] 3.1 Write `V1__platform.sql` (`tenant`, `app_user`, `membership`) with RLS, FORCE and the relationship-based policies from design D3, plus grants for `estilix_app`; verify migrations run in a Testcontainers test
- [x] 3.2 Write the RLS guard test first: it lists every table with a `tenant_id` column and fails if RLS, FORCE or a policy is missing; then make it pass; verify it fails when a test migration adds an unprotected table
- [x] 3.3 Write isolation tests first (query without filter, cross-tenant update/delete, insert into another tenant, no tenant context, tenant id in body ignored, tenant id immutable), then implement `RequestContext` and `TenantTransactionManager` (`set_config(..., true)` in `prepareTransactionalConnection`); verify every `tenant-isolation` scenario passes
- [x] 3.4 Verify "Context does not leak between requests" with a test that forces a pool of size 1 and alternates requests for tenants A and B
- [x] 3.5 Implement the tenant resolver for `/api/v1/tenants/{tenantId}/**` (member → context, non-member → 404, suspended → `tenant-suspended`, support → read-only context); verify the four scenarios with API tests

## 4. Authentication and access control

- [x] 4.1 Configure the OAuth2 resource server (issuer, JWKS, audience) with mock-oauth2-server in tests; verify missing, expired and foreign-issuer tokens get 401 and health stays public
- [x] 4.2 Write tests first for first-sign-in provisioning, including two concurrent first requests, then implement it with `INSERT ... ON CONFLICT DO NOTHING`; verify exactly one user exists
- [x] 4.3 Implement `GET /api/v1/me` returning tenants, roles and the permission list per tenant; verify "Current user overview" and "User without membership"
- [x] 4.4 Write unit tests first for the role → permission catalog, then implement it as a single declared table used by `@PreAuthorize` checks; verify a PROFESSIONAL gets 403 on member management
- [x] 4.5 Implement membership management (list, add existing user, change role, remove) for OWNERs with the last-owner rule; verify every membership scenario including cross-tenant 404
- [x] 4.6 Map the `platform-support` group claim to `PLATFORM_SUPPORT` and reject any attempt to grant it through the API; verify "Tenant owner cannot grant support"

## 5. Personal data protection

- [x] 5.1 Write unit tests first for masking (name, e-mail, phone, other, empty), then implement the masking functions; verify the spec examples exactly
- [x] 5.2 Implement `PersonName`, `Email`, `Phone`, `PersonalText` with redacting `toString()` and their Jackson serializers driven by `RequestContext`; verify serialization for member, support and support-with-break-glass, and that a `data class` holding them prints redacted values
- [x] 5.3 Create the reusable log-capture test helper and verify "Validation failure on personal data", "Unexpected error" and "Validation error body" with API tests that submit known personal values and assert they are absent from logs and responses

## 6. Audit log

- [x] 6.1 Write `V2__audit.sql` (`audit_event`, tenant-owned, RLS, grants SELECT/INSERT only, trigger rejecting UPDATE/DELETE); verify with a test that `estilix_app` cannot update or delete an event
- [x] 6.2 Record audit events in the same transaction for first sign-in and membership changes; verify the "Membership change audited" scenario and that a rolled-back change leaves no event
- [x] 6.3 Implement `GET /api/v1/tenants/{tenantId}/audit-events` (OWNER, newest first, paginated) and support access to any tenant's events; verify owner, manager-403 and cross-tenant-404 scenarios and that no event contains personal values

## 7. Support access and break-glass

- [x] 7.1 Implement support endpoints to list tenants and read a tenant's members (masked, read-only transaction); verify "Support lists tenants", "Support reads a member list", "Support tries to write" and "Tenant user is not support"
- [x] 7.2 Write `V3__break_glass.sql` (tenant-owned, partial unique index for one active session per support user and tenant); write tests first for open, short reason, max duration, scope to one tenant, expiration by database clock and early end, then implement; verify all `Break-glass session` scenarios
- [x] 7.3 Audit open/end/expire and one event per request with unmasked reads, incrementing `unmasked_reads`; verify "Unmasked read audited"
- [x] 7.4 Implement the owner's access history endpoint and the active-session flag in `/api/v1/me`; verify "Owner sees the session", "Other tenants' sessions hidden" and "Active session indicator" at API level
- [x] 7.5 Add the nightly job marking expired sessions and writing the expiration event; verify it is idempotent when run twice

## 8. Front-end shell

- [x] 8.1 Integrate `angular-auth-oidc-client` (Authorization Code + PKCE) against mock-oauth2-server locally, tokens in memory, HTTP interceptor adding the bearer token; verify sign-in and sign-out manually with `docker compose up`
- [x] 8.2 Implement post-login routing: single salon goes straight to its home, several salons show the picker once and remember the last choice; verify both flows with component tests
- [x] 8.3 Implement the role-aware layout (bottom navigation on phones, side menu on desktop) driven by `/me` permissions, plus the pages "Equipe" (members, OWNER only) and "Histórico de acessos" (audit + break-glass, OWNER only), all text in pt-BR; verify a PROFESSIONAL does not see them
- [x] 8.4 Show the banner "O suporte está com acesso temporário aos dados" with the reason while a break-glass session is active; verify with a component test
- [x] 8.5 Implement the support area (tenant list, masked members, open/end break-glass form with reason and duration); verify with component tests and a manual run as a `platform-support` user

## 9. Documentation and end-to-end checks

- [x] 9.1 Write the README in English: product, architecture (module diagram), how isolation works with RLS and why, roles, LGPD approach, how to run locally with the demo users, and the roadmap; verify every command runs as written on a clean clone
- [x] 9.2 Add ADRs `0001-row-level-security.md`, `0002-roles-in-database-not-token.md`, `0003-personal-data-as-types.md`; verify the README links to them
- [x] 9.3 Add a `local`-only seed with two salons (a salon and a barbershop), one owner, one manager, one professional each and a support user in mock-oauth2-server; verify an owner of one salon cannot see the other in the UI
- [ ] 9.4 Run `./gradlew check` and the frontend checks locally and in CI; verify everything is green

## Workflow follow-up

- After review, archive the change with `/opsx:archive` so the six capabilities become the project's main specs.
- Start `salon-setup` as the next change.
