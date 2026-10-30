# Tasks

## 0. Prerequisite

- [ ] 0.1 Archive `platform-foundation` (after its CI tasks 1.5 and 9.4 are green) so `tenancy/tenant-isolation` exists in the main specs; verify `openspec validate salon-setup --strict` no longer reports that the MODIFIED delta would be refused

## 1. Tenant lifecycle and plans (database first)

- [x] 1.1 Write `V4__onboarding_and_plans.sql`: tenant columns (slug, phone, address, plan, terms, created_by, submitted/decided fields, rejection reason), status check with PENDING_APPROVAL and REJECTED, `tenant_insert` and update policies with column grants, partial unique index "one pending or rejected salon per creator", slugs for existing tenants, `plan` table with the `launch` plan at R$ 0; verify migrations run and `RlsGuardTest` stays green
- [x] 1.2 Write tests first for the database rules: the app role can insert only a PENDING_APPROVAL tenant whose id equals the current tenant, cannot set status ACTIVE itself, and two concurrent submissions by the same user leave one salon; then adjust policies until they pass
- [x] 1.3 Add `tenant-pending-approval` and `tenant-rejected` problem types and map them in `TenantAccessInterceptor`; verify the modified "Tenant lifecycle status" scenarios (pending, rejected, suspended, support still reads) with API tests

## 2. Salon module: profile and opening hours

- [x] 2.1 Write unit tests first for weekly intervals (15-minute grid, start before end, no overlap within a day, closed day, split shift) and for slug rules (suggestion from name without accents, allowed characters, reserved list); then implement them in the new `salon` module; verify all pass
- [x] 2.2 Write `V5__salon_catalog.sql` part 1: `opening_hours` with the per-day exclusion constraint and RLS; verify with a Testcontainers test that overlapping rows fail in the database
- [x] 2.3 Implement `GET/PUT /api/v1/tenants/{tenantId}/salon` (profile) and `GET/PUT .../salon/hours` (replace the whole week), slug change with conflict on duplicates, time-zone allow-list; add VIEW_SALON/MANAGE_SALON to the catalog; verify every `salon-profile` scenario including manager 403, cross-salon 404 and the masked phone for support

## 3. Service catalog

- [x] 3.1 Write unit tests first for service validation (name and `name_key`, duration grid and range, price as BigDecimal with at most two decimals, FIXED/FROM display) and for the presets per establishment type; then implement; verify all pass
- [x] 3.2 Add `service` to `V5` with checks, `unique (tenant_id, name_key)` and RLS; implement list (active and "Inativos"), create, update, reorder, activate/deactivate and "Adicionar sugestões"; add VIEW_SERVICES/MANAGE_SERVICES; verify every `service-catalog` scenario including duplicate name, same name in another salon and professional 403

## 4. Professionals

- [x] 4.1 Add `professional`, `professional_service` and `professional_hours` to `V5` with composite tenant foreign keys, `unique (tenant_id, user_id)` and RLS; verify with a database test that a link to another salon's service cannot be stored even with RLS context of the right salon
- [x] 4.2 Write unit tests first for effective hours (default = salon, own ∩ salon, conflicts listed when the salon shrinks); then implement them as the single function `scheduling` will reuse; verify all pass
- [x] 4.3 Implement professionals CRUD, member link through the `MembershipLookup` interface (identity implements it), services set replacement and own hours; add VIEW_PROFESSIONALS/MANAGE_PROFESSIONALS; verify every `professionals` scenario, the masked names for support and that `/me` shows a member's linked professional

## 5. Onboarding and approval

- [x] 5.1 Write tests first for the onboarding use case (complete submission in one transaction, nothing saved on invalid data, terms required, presets by type, one pending salon per user), then implement `POST /api/v1/onboarding` with the `SalonInitializer` interface implemented by `salon`; verify the scenarios and the TENANT_SIGNED_UP audit event
- [x] 5.2 Implement the owner's status endpoint and resubmission (`GET /api/v1/onboarding/status`, `PUT /api/v1/onboarding/{tenantId}` for REJECTED salons) with TENANT_RESUBMITTED; verify "Resubmission" and "Another user cannot resubmit"
- [x] 5.3 Implement the support queue (`GET /api/v1/support/approvals`, oldest first, purpose-bound unmasked contact, APPROVAL_QUEUE_VIEWED audit) and decisions (`POST .../{id}/approve`, `POST .../{id}/reject` with reason ≥ 10 chars) as conditional updates; verify approve, reject, not-pending conflict, tenant user 403, contact masked after approval, and a concurrent approve/reject test with exactly one decision applied
- [x] 5.4 Verify the log-hygiene recipe on onboarding: submit known owner, phone and address values with an invalid field and assert they appear in neither logs nor the error body

## 6. Public site and plans (front-end)

- [x] 6.1 Implement `GET /api/v1/public/plans` and `GET /api/v1/public/slugs/{slug}` (available yes/no), permitted without login; verify with API tests that no tenant data is returned and inactive plans are hidden
- [x] 6.2 Switch the Angular build to hybrid rendering: prerender `/` and `/planos`, client-render the app routes, move the OIDC callback to `/entrar`; verify the built `index.html` of `/` contains the landing text and that `/` never redirects to sign-in
- [x] 6.3 Build the landing page (hero, problems solved, for salons and barbershops, how it works in 3 steps, security and LGPD, FAQ, "Em breve" labels driven by one feature list) and the plans page from the catalog with "Assinar"; verify component tests for the "Em breve" labels and for "Abrir o Estilix" when signed in, and a manual check at phone width

## 7. Onboarding and settings screens (front-end)

- [x] 7.1 Build the 3-step wizard (type → salon data and hours with Mon–Sat 09:00–19:00 prefilled and slug suggestion → suggested services with editable price/duration and terms), sending one request at the end; verify component tests for prefills, slug suggestion and field errors from the API
- [x] 7.2 Build the status screens: "em análise" with the summary, "recusado" with the reason and "Corrigir e reenviar"; update `landingFor` for pending/rejected salons; verify with unit and component tests
- [x] 7.3 Build the settings pages "Salão" (profile, hours editor with split shifts), "Serviços" (list by category, add, edit, reorder, inactive tab, "Adicionar sugestões") and "Profissionais" (add, link member, services checklist, hours following the salon or own, conflicts warning); menu items by permission; verify component tests and a manual run as owner, manager and professional
- [x] 7.4 Build the support approval queue (list, details, approve, reject with reason) and a pending counter on the support home; verify component tests and a manual run approving and rejecting a demo salon

## 8. Documentation, seed and end-to-end

- [x] 8.1 Extend the local seed: services, hours and professionals for both demo salons, and one pending salon for the support queue; verify the demo flows listed in the README work after `./scripts/local.sh down --wipe` and `up`
- [x] 8.2 Update the README (public site, onboarding flow, demo steps) and add ADR `0004-tenant-lifecycle-and-onboarding.md`; verify every command in the README runs as written
- [x] 8.3 Update the study guide PDF with the onboarding flow, the composite-FK and exclusion-constraint techniques and the new interview questions; verify all diagrams render
- [ ] 8.4 Run `./gradlew check`, frontend lint/test/build and `openspec validate salon-setup --strict`; verify everything is green locally and in CI

## Workflow follow-up

- After review, archive with `/opsx:archive` (only after `platform-foundation` is archived).
- Start `scheduling` as the next change.
