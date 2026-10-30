# ADR 0006 — Service records drive the booking lifecycle; customers are anonymised, never deleted

**Status:** accepted (change `customers-and-service-records`)

## Context

A booking only says that someone *planned* to come. The salon also needs to know:
- what actually happened: arrived, served, paid, or did not come;
- who the customer is across visits;
- how to answer LGPD requests (a copy of the data, or erasure) without breaking its own accounts.

Starting a service and moving the booking must never disagree. Two people at the counter may tap "Iniciar atendimento" at the same moment.

## Decision

- **Three modules, dependencies pointing one way:**
  - `customers` owns the customer and consent tables.
  - `scheduling → customers`: every booking is linked by phone.
  - `servicerecords → scheduling, customers`: a comanda drives the booking through the `BookingLifecycle` API.
  - `customers` never reads bookings or records. It declares `CustomerVisits` and `CustomerDataHolder`, which the other modules implement. Spring Modulith verifies the graph.
- **One transaction per user action.** "Iniciar atendimento" moves the booking (and the bookings with the same code that day) to IN_SERVICE and creates the record's items in the same transaction. Closing moves them to DONE. Voiding an open record returns them to CONFIRMED.
- **Transitions are conditional updates**, e.g. `update … set status = 'IN_SERVICE' where id = :id and status = 'CONFIRMED'`. Zero rows means someone else got there first, so the request fails cleanly. There are no row locks.
- **Agenda constraint:**
  - The exclusion constraint now protects `status in ('CONFIRMED','IN_SERVICE','DONE')`. "Não veio" (NO_SHOW) and CANCELLED free the time.
  - Walk-ins are agenda entries with origin WALK_IN, created already IN_SERVICE. They hit the same constraint, so a walk-in can never overlap a booking.
- **One open record per customer:** a partial unique index `(tenant_id, customer_id) where status = 'OPEN'`.
- **Identity by mobile phone:** one customer per E.164 phone per salon. `insert … on conflict do nothing` lets concurrent first bookings converge on one row.
- **History is computed on read** by `security_invoker` views (`customer_visit_stats`, `customer_professional`). There are no counters to fix after a void or an anonymisation. The usual interval is the median of the gaps between visit days, a pure Kotlin function.
- **Anonymisation instead of deletion:**
  - The customer row stays, with the name "Cliente anonimizado" and the phone and notes erased.
  - Bookings lose the copied name and phone. Booking groups lose the phone and get random code and token hashes, so old links die.
  - Records keep amounts, dates, services and professionals. Anonymised data is no longer personal data, and the salon's revenue stays correct.
  - Export and anonymisation are OWNER-only and audited by customer id.

## Alternatives considered

| Option | Why not |
|---|---|
| Domain events between modules (after commit) | Starting a record and moving the booking must be atomic. Events would need compensation. This is revisited with `event-backbone` (outbox). |
| `customers` reads the bookings and records tables | It hides a real dependency from the module graph, and the first refactor of a table breaks a module that "does not depend" on it. |
| Status stored only on the record, not on the booking | The agenda and the exclusion constraint would need a join to know whether a time is held. |
| Counters on the customer (visits, total) | They drift on void, correction and anonymisation, and need their own tests and jobs. Views are fast enough below ~100k records per salon. |
| Hard delete with cascade on an erasure request | It destroys the salon's financial history. LGPD allows keeping what the controller needs, and anonymised data is out of scope. |
| `SELECT … FOR UPDATE` on the booking before starting | It works, but it serializes every caller and is easy to forget in the next code path. The conditional update needs no lock. |

## Consequences

- **Tests:**
  - 20 repetitions of 10 concurrent starts of the same booking always produce exactly one record.
  - A negative control (a trigger that keeps the booking CONFIRMED, plus dropped unique keys) shows the duplicates the guard prevents.
  - A test re-runs the V8 backfill statements and checks one customer per phone.
- **No time held by mistake:** a professional's time is held only by entries in a held status. Finishing early trims the entry to the next quarter hour.
- **Old releases:** `agenda_entry.customer_id` is nullable in V8, so an app rollback still works. A later migration makes it required for bookings.
- **Trade-off:** synchronous in-process calls couple the modules' transactions. That is acceptable while they share one database. The outbox in `event-backbone` is the way out when they do not.
