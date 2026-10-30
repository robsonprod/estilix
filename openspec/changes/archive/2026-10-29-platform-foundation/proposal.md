# Proposal

## Why

Estilix will hold personal data of many salons' customers in one shared database. If tenant isolation, access control and personal-data protection are added later, every feature built before them becomes a potential leak and must be revisited. This change builds those guarantees first, so the business features (agenda, service records, finance) inherit them for free instead of each one re-implementing them.

## What Changes

- Scaffold the back-end: Kotlin on Java 25, Spring Boot 4, Spring Modulith, Gradle Kotlin DSL, Flyway, PostgreSQL, Testcontainers; `docker compose up` runs database, API and web app.
- Health checks: liveness (process alive) separated from readiness (can serve traffic: database reachable), so orchestrators restart only what is broken and stop routing to what is not ready.
- Developer experience: `scripts/local.sh up|down|status|logs` over the `core` Docker Compose profile.
- Scaffold the front-end: Angular app in pt-BR, mobile-first shell with login, a tenant switcher for users with more than one salon, and a role-aware menu.
- Multi-tenancy: a `tenant` table and a database-enforced isolation model — every tenant-owned table has `tenant_id` and a Row-Level Security policy; the API sets the current tenant per transaction and connects with a role that cannot bypass RLS.
- Authentication with Amazon Cognito (JWT, MFA available); a local fake issuer for development and tests.
- Authorization: memberships linking a user to a tenant with one role (OWNER, MANAGER, PROFESSIONAL); a platform-level PLATFORM_SUPPORT role outside any tenant.
- Support access: support users read every tenant with personal data masked; they can open a time-boxed break-glass session with a written reason to see unmasked data. Every break-glass session is audited and listed to the tenant's owners.
- Audit log: append-only record of security-relevant actions (sign-in, membership changes, break-glass, unmasked reads), readable per tenant by owners.
- Personal-data protection: a single way to declare a field as personal data, masking for support, and log hygiene enforced by tests (no names, phones, e-mails in logs or error bodies).

Out of scope (covered later):
- Salon onboarding, establishment type presets, staff invitations by e-mail, services and hours → `salon-setup`.
- Customers, consent and data-subject rights (export/delete) → `customers-and-service-records`. This change only provides the masking and audit mechanisms they will use.
- AWS infrastructure (Cognito user pool, RDS, ECS via Terraform) → `aws-deployment`. Here Cognito is consumed only as a JWT issuer.
- E-mail notifications to owners about break-glass → `notifications`; this change shows them in the app.
- Subscription billing → `subscription-billing`.

Usability goal: a staff member signs in and lands on their salon's home in at most 2 steps (credentials, then MFA if enabled); users of a single salon never see a tenant picker.

Security / LGPD / cost impact:
- Security: isolation is enforced by PostgreSQL, not only by application filters; a missing `WHERE tenant_id` cannot leak data.
- LGPD: implements the processor-side duties — need-to-know access for the platform operator, traceability of every unmasked access, and no personal data in logs.
- Cost: none in this change (local only). Cognito is free up to the free-tier monthly active users.

## Capabilities

### New Capabilities
- `tenancy/tenant-isolation`: tenants, per-transaction tenant context and database-enforced isolation of tenant-owned data.
- `identity/access-control`: authentication via JWT, memberships, roles and the permission rules for each role.
- `identity/support-access`: platform support's masked read access and audited break-glass sessions visible to owners.
- `platform/audit-log`: append-only audit trail of security-relevant actions and who can read it.
- `platform/operational-health`: liveness and readiness signals for orchestrators, without leaking internals.
- `platform/personal-data-protection`: declaring personal data, masking rules and the guarantee that personal data never reaches logs or error responses.

### Modified Capabilities
- None (first change of the project).

## Impact

- New repository layout: `backend/` (Gradle, Kotlin), `frontend/` (Angular), `docker-compose.yml`, `openspec/`.
- New dependencies: Spring Boot 4 (webmvc, security oauth2-resource-server, jdbc, validation, actuator), Spring Modulith, Flyway, PostgreSQL driver, Testcontainers, springdoc-openapi; Angular with an OIDC client library.
- Database: Flyway migrations for `tenant`, `app_user`, `membership`, `audit_event`, `break_glass_session`, plus the database roles (`estilix_owner` for migrations, `estilix_app` for runtime).
- API: `/api/v1/me`, `/api/v1/tenants/{tenantId}/...` convention, `/api/v1/support/...` for support, `/api/v1/tenants/{tenantId}/audit-events`.
- Every future change must follow the isolation, masking and log-hygiene rules defined here.
