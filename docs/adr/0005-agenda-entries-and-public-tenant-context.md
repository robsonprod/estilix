# ADR 0005 — One agenda table guarded by an exclusion constraint; public requests under RLS

**Status:** accepted (change `scheduling`)

## Context

Customers book through a public page without an account, and two of them may confirm the same professional and time at the same moment. Staff also block time (day off, course, lunch). A booking must never overlap another booking or a block, and a public request — with no login — must still be confined to one salon.

## Decision

- **Bookings and blocks live in one table, `agenda_entry`** (`kind` BOOKING | BLOCK), so one exclusion constraint covers both:
  `exclude using gist (professional_id with =, tstzrange(starts_at, ends_at, '[)') with &&) where (status = 'CONFIRMED')`.
  Half-open ranges let 15:00–15:30 and 15:30–16:00 coexist. Check constraints keep per-kind columns consistent; composite foreign keys `(tenant_id, professional_id/service_id)` keep references inside the salon.
- **First commit wins.** The losing transaction gets SQLSTATE `23P01`, translated into `slot-taken` (409, "Esse horário acabou de ser preenchido"). Reschedule is an `UPDATE` of the same row and hits the same constraint.
- **"Qualquer profissional"** tries free professionals in fairness order, each insert in a savepoint (`PROPAGATION_NESTED`): a `23P01` rolls back only that attempt.
- **Public requests** resolve the salon with `SECURITY DEFINER` functions (`active_tenant_id(slug)`, `booking_group_tenant(token_hash)`) that return only an id, then run inside that salon's tenant context — the same RLS path as staff.
- **Codes and links:** 6-digit code stored as HMAC-SHA256 with a server pepper; manage-link token (256 bits) stored as SHA-256. Phone + code issues a fresh link.
- **Rate limits:** fixed-window counters in Redis (`INCR` + `EXPIRE`), a 30-second breaker and in-memory counters when Redis is down; the per-phone daily limit is serialized with `pg_advisory_xact_lock`.

## Alternatives considered

| Option | Why not |
|---|---|
| Two tables (`booking`, `time_block`) | An exclusion constraint cannot span tables; overlap would need triggers and locks. |
| Check availability in code, then insert | Check-then-act race: both requests see "free". |
| `SELECT … FOR UPDATE` on the professional | Works, but serializes every booking of a professional and is easy to forget in a new code path. |
| Plain SHA-256 of the 6-digit code | 1,000,000 possibilities are brute-forced offline in milliseconds. |
| Bucket4j token buckets | More moving parts; fixed windows are enough for abuse protection and easy to inspect in Redis. |

## Consequences

- `BookingRaceTest` fires 20 concurrent confirmations: exactly one 201, nineteen 409 and one row; its negative control drops the constraint and shows duplicates.
- Free times shown to customers are a hint; the constraint is the guarantee.
- The customer's name and phone on a booking are personal data types: masked for support, never logged.
