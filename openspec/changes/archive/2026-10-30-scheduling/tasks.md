# Tasks

## 1. Database

- [x] 1.1 Write `V6__scheduling.sql`: booking rules columns on `tenant` with defaults and range checks, `booking_group`, `agenda_entry` with per-kind checks, composite tenant foreign keys, the exclusion constraint on `(professional_id, tstzrange(starts_at, ends_at, '[)'))` for CONFIRMED rows, RLS on both tables and the `active_tenant_id(slug)` SECURITY DEFINER function; verify migrations run and `RlsGuardTest` stays green
- [x] 1.2 Write database tests first: overlapping bookings fail with `23P01`, back-to-back succeed, a block overlapping a booking fails, a cancelled booking frees the range, a cross-salon professional or service is refused, `active_tenant_id` returns nothing for pending salons; verify all pass

## 2. Domain rules (test first)

- [x] 2.1 Write unit tests first for booking rules validation (days ahead, notices) and Brazilian phone normalization to E.164, then implement; verify all pass
- [x] 2.2 Write unit tests first for the availability function with a fixed `Clock` (fits in interval, busy professional, block, split shift, cancelled booking frees, notice, window, any professional, inactive professional, unavailable times listed, days with free times), then implement it reusing `EffectiveHours`; verify every `availability` scenario has a test
- [x] 2.3 Write unit tests first for code and token generation and verification (6 digits, HMAC with pepper, SHA-256 token, constant-time compare), then implement; verify all pass

## 3. Bookings

- [x] 3.1 Implement create (public and staff), translating `23P01` into `slot-taken`, copying the service duration, validating service/professional; verify "Successful booking", "Duration copied", "Professional who does not perform the service", "Inactive service" with API tests
- [x] 3.2 Implement "qualquer profissional" with nested transactions (savepoints) and fairness ordering; verify "Assigned to a free professional" and "Everybody taken during the race"
- [x] 3.3 Write the race test: 20 concurrent public bookings for the same professional and time from virtual threads behind a latch; verify exactly one 201, nineteen `slot-taken`, one row in the database; add a negative control that drops the constraint and shows duplicates
- [x] 3.4 Implement chained services (same group, starts at previous end), cancel (idempotent, who/when) and reschedule (atomic, stays on conflict); verify the `bookings` scenarios for chaining, cancel and reschedule
- [x] 3.5 Verify personal data: support sees customer names and phones masked in the agenda, and the log-hygiene recipe finds no customer name or phone in logs or error bodies

## 4. Public booking API

- [x] 4.1 Implement `GET /api/v1/public/salons/{slug}` (name, active services with price labels, active professionals by display name), `.../days?serviceId&professionalId&month`, `.../times?serviceId&professionalId&date`; verify active vs pending salon, closed and fully booked days disabled, and that no customer data appears in availability answers
- [x] 4.2 Implement `POST /api/v1/public/salons/{slug}/bookings` (name, phone, service, professional or any, start) returning code, manage link and summary, and `POST .../bookings/{groupToken}/next` for chained services; enforce at most 3 active bookings per phone per day with an advisory lock; verify scenarios and that the token is stored only hashed
- [x] 4.3 Implement manage endpoints by token (`GET/POST cancel/POST reschedule /api/v1/public/bookings/{token}`) and by slug + phone + code; enforce the cancel notice (`too-late` problem with the salon phone); verify "Cancel in time", "Too late", "Reschedule in time", "Open with the link", "Phone and code"

## 5. Rate limiting

- [x] 5.1 Add Redis to the `core` Compose profile and Testcontainers; add Bucket4j with the Redis proxy manager, the limits table from design D7, `rate-limited` problem with `Retry-After`, client IP from the trusted proxy header only; verify "Burst from one IP" and "Too many wrong codes"
- [x] 5.2 Add the circuit breaker and the in-memory fallback, plus a Redis health indicator outside readiness; verify "Limiter store down" by stopping the Redis container during a test, and readiness staying UP

## 6. Staff agenda API

- [x] 6.1 Add `VIEW_AGENDA`, `MANAGE_AGENDA`, `MANAGE_BOOKING_RULES`; implement booking rules endpoints and the day view (`GET /api/v1/tenants/{t}/agenda?date=`) with the professional restriction for PROFESSIONALs; verify "Owner sees everyone", "Professional sees only their agenda", "Professional without link", "Other salon", rules defaults and manager 403
- [x] 6.2 Implement staff bookings (ignore minimum notice), staff cancel/reschedule anytime with audit events without personal data, and blocks (create, remove, no overlap); verify the `staff-agenda` scenarios including "Professional edits a colleague's booking"

## 7. Public booking page (front-end)

- [x] 7.1 Build `/agendar/:slug`: service → professional or "Qualquer profissional" → month calendar with bookable days → day times (green free, faded disabled) → name and phone → confirm; handle `slot-taken` by showing the message and reloading the day; verify component tests for disabled days, faded times and the slot-taken path
- [x] 7.2 Build the confirmation (code, manage link, "Adicionar mais um serviço" at the previous end) and `/agendamento/:token` (bookings, cancel and reschedule within the notice, salon phone when too late) plus the phone + code entry on the salon page; verify component tests and a manual run at phone width

## 8. Staff agenda (front-end)

- [x] 8.1 Build "Agenda" as the first menu item: day navigation, columns per professional on desktop and a switcher on phones, tap a free slot to book, booking detail with cancel and reschedule, "Bloquear horário"; verify component tests and a manual run as owner, manager and professional
- [x] 8.2 Add booking rules to "Salão" for owners; flip "Agenda online sem ligação" to available on the landing page and update the launch plan list with `V7__plan_features.sql`; verify the landing component test and the plans endpoint

## 9. Seed, documentation and end-to-end

- [x] 9.1 Extend the local seed with bookings for today and tomorrow and one block in both demo salons; verify the demo flows after `./scripts/local.sh down --wipe` and `up`
- [x] 9.2 Update the README (public booking demo, race behaviour), add ADR `0005-agenda-entries-and-public-tenant-context.md`, and update the study guide PDF (exclusion constraint across bookings and blocks, savepoints, rate limiting with fallback, interview questions); verify commands and diagrams
- [x] 9.3 Run `./gradlew check`, frontend lint/test/build and `openspec validate scheduling --strict`; verify everything is green locally and in CI

## Workflow follow-up

- After review, archive with `/opsx:archive` so the scheduling capabilities and the modified professionals rule become main specs.
- Start `customers-and-service-records` as the next change.
