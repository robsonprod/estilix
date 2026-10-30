# Tasks

## 1. Database

- [x] 1.1 Write `CustomersDbTest` first:
  - unique phone per tenant, anonymised customer releasing the phone;
  - one OPEN record per customer;
  - status/field checks on `service_record`;
  - price range;
  - composite FKs refusing another tenant's service or professional;
  - RLS hiding another tenant's rows;
  - views not widening reads.

  Then write `V8__customers_and_service_records.sql` (design D3, D6, D9, D13, Migration Plan). Verify with `./gradlew test --tests '*CustomersDbTest*'`.
- [x] 1.2 Extend `AgendaDbTest` first:
  - IN_SERVICE and DONE block overlaps;
  - NO_SHOW frees the time;
  - WALK_IN without group accepted;
  - backfill links existing bookings to one customer per phone (most recent name).

  Then adjust V8 until it passes. Verify the existing scheduling tests still pass, and that `Fixtures` truncates the new tables.
- [x] 1.3 Verify the migration on the local demo database: run `./scripts/local.sh down --wipe` then `up`, and check that the demo bookings got customers.

## 2. Customers module

- [x] 2.1 Write `CustomerRulesTest` first:
  - usual interval median (even/odd, fewer than 3 visits, same-day visits counted once);
  - search key normalisation;
  - phone search needs at least 4 digits.

  Then implement the pure functions. Verify the unit tests pass.
- [x] 2.2 Create module `customers` with:
  - `CustomerDirectory.linkOrCreate` (D6: `on conflict do nothing`);
  - the `CustomerVisits` and `CustomerDataHolder` interfaces;
  - the new permissions (D12).

  Update `ModularityTests` to expect the `customers` and `servicerecords` modules with no cycle. Verify with `./gradlew test --tests '*ModularityTests*'`.
- [x] 2.3 Write `CustomersApiTest` first, covering the `customer-records` scenarios:
  - phone written differently, same phone in two salons, other salon not-found;
  - rename, notes too long;
  - consent default, grant and revoke (with history);
  - history numbers, list order, search by digits and without accents;
  - professional scope (list, plus not-found for another's customer);
  - support masked;
  - no customer data in logs or audit.

  Then implement the controller, service and repository with the scope helpers (D7, D8). Verify the test class passes.
- [x] 2.4 Make the public and staff booking flows call `linkOrCreate`, and expose `customerId` on the staff agenda item. Write the "First public booking creates the customer" and "Returning customer with another name" tests first, including the concurrent first booking from the same phone. Verify `PublicBookingApiTest`, `StaffAgendaApiTest` and `BookingRaceTest` stay green.
- [x] 2.5 Verify the module: `./gradlew check` is green.

## 3. Booking lifecycle in scheduling

- [x] 3.1 Write tests first in `StaffAgendaApiTest`, using a fixed `Clock`:
  - "Customer did not come";
  - "Too early for no-show";
  - no-show in another salon is not-found;
  - "Customer cancels after the visit";
  - "Staff reschedules a no-show";
  - "Service in progress holds the time".

  Then implement the no-show endpoint, the CONFIRMED-only guard on cancel and reschedule, and the agenda statuses and actions in the day view.
- [x] 3.2 Implement `BookingLifecycle` (`start`, `finish`, `reopen`, `occupy`, `trimEnd`, `release`) as conditional updates (D2, D4, D5). Write the unit/integration tests for each transition first, including the "someone else got there first" zero-row case. Verify the tests pass.
- [x] 3.3 Implement `CustomerDataHolder` for scheduling (export, and anonymise including link and code rotation from D10). Write the test first: an old manage link returns 404 after anonymisation. Verify it passes.
- [x] 3.4 Verify the group: all scheduling tests, plus `openspec validate customers-and-service-records --strict`.

## 4. Service records module

- [x] 4.1 Write `ServiceRecordsApiTest` first, covering the `service-records` scenarios:
  - items: price prefilled and edited, "starting at" price, negative price;
  - states: closed read-only, last item, other salon;
  - start from a booking: arrive, chained items, other day;
  - walk-in: takes the time, over a booking (rolled back), known phone fills the name.

  Then implement module `servicerecords` (start, walk-in, items). Verify the test class passes.
- [x] 4.2 Write tests first:
  - close: two-tap close, finished early (trim to the next 15 minutes), missing payment method;
  - void: started by mistake (booking back to CONFIRMED), professional cannot void, void audited without the reason;
  - day list total;
  - professional scope (only their records, item for a colleague → 403);
  - support masked.

  Then implement close, void, the day list and the scope. Verify the tests pass.
- [x] 4.3 Implement `CustomerVisits` and `CustomerDataHolder` for records. Then write `DataSubjectRightsApiTest`:
  - owner export content and no other customer;
  - manager 403;
  - other salon not-found;
  - amounts survive anonymisation;
  - phone is free again;
  - open record blocks anonymisation;
  - both actions audited without personal data.

  Verify it passes.
- [x] 4.4 Write `ServiceRecordRaceTest`: two staff start the same booking concurrently, and exactly one record opens. Include a negative control without the conditional update. Verify it passes reliably 20 times in a loop.
- [x] 4.5 Verify the back end: `./gradlew check` is green, and no test log contains customer names or phones.

## 5. Front-end

- [x] 5.1 Add the API clients and models (customers, records, statuses) and money helpers (cents ↔ "R$ 0,00", decimal-string API values). Write unit tests first for parsing and formatting. Verify `npm test`.
- [x] 5.2 Agenda changes:
  - status labels and colours;
  - booking sheet actions ("Iniciar atendimento", "Não veio" only after the start, "Abrir comanda");
  - the "Novo atendimento" button.

  Write component tests first for which actions show per status and role. Verify `npm test`.
- [x] 5.3 Build the comanda sheet:
  - items with an editable price (numeric keypad);
  - "+ Serviço";
  - total;
  - "Concluir", then the four payment buttons;
  - void with a reason for owners and managers;
  - walk-in flow (phone first, then the known name appears).

  Write component tests first for the 3-tap close and the `slot-taken` message on a walk-in. Verify `npm test`.
- [x] 5.4 Build the "Clientes" menu:
  - list with search and last visit;
  - detail with stats on top (visits, total, last visit, "costuma voltar a cada N dias"), notes with the hint, consent toggle and history, and the owner-only "Privacidade" (export download, anonymise with typed confirmation).

  Write component tests first for hidden consent and privacy controls by role. Verify `npm test`.
- [x] 5.5 Flip "Atendimento em 3 toques" to available in `features.ts` and the fallback plan. Update the landing component test. Verify `npm run lint`, `npm test` and `npm run build`.
- [ ] 5.6 Check it manually in the browser at phone width, as owner (dona-bela), manager and professional (pro-bela):
  - start a booking and close with Pix in 3 taps;
  - "Não veio" frees the time on the public page;
  - walk-in occupies the agenda;
  - customer detail shows history and usual interval;
  - export and anonymise as owner.

## 6. Seed, docs and end-to-end

- [ ] 6.1 Extend `R__demo_data.sql` with:
  - customers for both demo salons;
  - closed records over the last months (usual interval visible);
  - one NO_SHOW and one OPEN record today;
  - consent on some customers.

  Verify the demo flows after `./scripts/local.sh down --wipe` and `up`.
- [x] 6.2 Update the README (customer and comanda demo). Add ADR `0006-service-records-lifecycle-and-anonymisation.md`. Update `docs/skills-map.md`. Update the study guide and rebuild the PDF with `guia/build-pdf.sh`, adding:
  - the booking and record state machine;
  - the conditional-update race safety;
  - views vs counters;
  - LGPD export and anonymisation;
  - interview questions.

  Verify that the commands and diagrams render.
- [ ] 6.3 Run `./gradlew check`, the frontend lint/test/build and `openspec validate customers-and-service-records --strict`. Verify everything is green locally and in CI (CI after the user pushes).

## Workflow follow-up

- After review and green CI, archive with `/opsx:archive` so the customers and service-records capabilities and the modified scheduling rules become main specs.
- A later migration makes `agenda_entry.customer_id` required for BOOKING, once this release is verified (design Migration Plan).
- Next change on the roadmap: `finance` (cash register, expenses, commissions), built on the closed records.
