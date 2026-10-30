# Proposal

## Why

Estilix has a secure foundation but no way for a salon to arrive, and nothing a salon would recognize as its own: no services, no professionals, no opening hours. The agenda (`scheduling`) and service records cannot exist without them. This change opens the sales funnel — a public landing page and a plans page that the platform owner can share with salons and barbershops — and turns a sign-up into a configured salon that platform support approves before it goes live.

## What Changes

- **Public site (no login)** in pt-BR: landing page explaining the product for salons and barbershops, and a plans page driven by a plan catalog. During the launch the only plan costs R$ 0,00; "Assinar" starts the sign-up for free. Features that are not built yet are labelled "Em breve", never sold as available.
- **Self-service sign-up and onboarding**: after creating an account (Cognito self sign-up; any username locally), the owner completes a 3-step wizard — establishment type, salon data and opening hours, suggested services — accepts the terms, and submits. The salon is created as **PENDING_APPROVAL** with the person as OWNER. Until approval the owner sees only a waiting screen.
- **Approval by platform support**: a queue in the support console with the data needed to verify the salon; support approves (salon becomes ACTIVE) or rejects with a reason the owner sees and can fix and resubmit. Every decision is audited.
- **BREAKING (spec-level)**: tenant statuses grow from ACTIVE/SUSPENDED to PENDING_APPROVAL/REJECTED/ACTIVE/SUSPENDED; members of a pending or rejected salon get a dedicated problem instead of data.
- **Salon profile**: name, establishment type, business phone, address, time zone, weekly opening hours with split shifts, and a unique public slug (used later by the public booking page).
- **Service catalog**: services with category, duration on the 15-minute grid, fixed or "a partir de" price, active flag and display order; presets per establishment type offered in the wizard.
- **Professionals**: people who appear in the agenda, registered by name; optionally linked to a member who has a login; which services each one performs; weekly hours that default to the salon's opening hours.

Assumptions recorded from the product decisions (2026-10-29):
- No invitations: people reach Estilix through the landing page. A professional who wants to sign in creates an account on the site and the owner adds them to the team by e-mail (existing `platform-foundation` feature) and links them to their professional entry.
- A professional does not need a login to appear in the agenda.
- Service prices are the salon's own prices (fixed or "a partir de"); plan prices are the platform's and are R$ 0 during launch.

Out of scope (roadmap change that covers it):
- Charging for plans, trials with expiry, invoices → `subscription-billing`.
- Public booking page using the slug, availability → `scheduling`.
- E-mail to support on a new sign-up or to the owner on approval → `notifications` (this change shows them in the app).
- Customers and service records → `customers-and-service-records`.
- Rate limiting of public endpoints with Redis → `scheduling` (public endpoints here are read-only and cacheable; sign-up requires an authenticated account).
- Support suspending/reactivating an active salon (status exists since the foundation; the screen comes later).

Usability goal:
- Landing → plans → account → wizard → submitted in **at most 3 wizard screens**, every field prefilled when possible (type-based service suggestions, Mon–Sat 09:00–19:00 hours, time zone America/Sao_Paulo).
- Support approves a salon in **2 taps** from the queue (open, approve).
- Adding a service or a professional is **one short form**; marking which services a professional performs is a checklist.

Security / LGPD / cost impact:
- Security: public endpoints expose only the plan catalog and slug availability (a yes/no, nothing about the salon owning it). Every new tenant-owned table uses the foundation's Row-Level Security; tenant creation sets the new tenant's context before inserting, so the existing policies apply. A user may have only one salon waiting for approval at a time.
- LGPD: the owner accepts the terms (version and time stored). Onboarding contact data (owner name and e-mail, salon phone) is shown **unmasked to platform support only in the approval queue and only while the salon is pending or rejected** — purpose: verify the salon before contract (pre-contractual procedures). Professional names are personal data of staff: masked for support like any other personal field.
- Cost: none in AWS terms (local only). The public pages are prerendered static HTML, cheap to serve from S3/CloudFront later.

## Capabilities

### New Capabilities
- `marketing/public-site`: public landing page and plans page, plan catalog, honest "em breve" labelling, entry points to sign in and sign up.
- `tenancy/tenant-onboarding`: sign-up wizard, terms acceptance, pending salon, one pending salon per user, support approval queue, approve/reject/resubmit, audit.
- `salon/salon-profile`: salon data, time zone, weekly opening hours with split shifts, public slug.
- `salon/service-catalog`: services, categories, 15-minute durations, fixed/"a partir de" prices, presets per establishment type, activation and order.
- `salon/professionals`: agenda professionals, optional link to a member, services performed, weekly hours defaulting to opening hours.

### Modified Capabilities
- `tenancy/tenant-isolation`: requirement "Tenant lifecycle status" gains PENDING_APPROVAL and REJECTED with their access rules. Requires `platform-foundation` to be archived first so this capability exists in the main specs.

## Impact

- Back-end: new module `salon` (profile, hours, services, professionals) and onboarding in `tenancy`/`identity`; migrations for `plan`, tenant columns (plan, slug, phone, address, terms, status reasons), `opening_hours`, `service`, `professional`, `professional_service`, `professional_hours`; new public endpoints under `/api/v1/public/`, onboarding under `/api/v1/onboarding`, support queue under `/api/v1/support/approvals`, salon endpoints under `/api/v1/tenants/{tenantId}/...`.
- Front-end: public routes (`/`, `/planos`) prerendered and outside the login guard; sign-in callback moves to `/entrar`; new wizard, waiting screen, settings pages (Salão, Serviços, Profissionais) and the support approval queue.
- Permissions catalog grows (`MANAGE_SALON`, `VIEW_SERVICES`, `MANAGE_SERVICES`, `VIEW_PROFESSIONALS`, `MANAGE_PROFESSIONALS`).
- `openspec/config.yaml` roadmap entry for this change is updated to include the public site and approval.
