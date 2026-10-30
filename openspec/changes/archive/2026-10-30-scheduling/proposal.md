# Proposal

## Why

The product promise is "book without calling the salon". Today a salon has services, professionals and hours, but no agenda: customers still call, and staff still write appointments on paper. This change delivers the agenda for staff and a public booking page per salon, where a customer picks a day and a free time and gets an immediate confirmation — the first feature a salon can show to its own customers.

## What Changes

- **Public booking page** at `/agendar/{slug}` (no login, pt-BR, phone-first): choose a service → a professional or "Qualquer profissional" → a day in a calendar (only bookable days enabled) → a time (free times in green; taken or unavailable times faded and disabled) → name and phone → confirm.
- **Immediate confirmation, first commit wins**: when two people pick the same time, the database accepts the first confirmed booking; the second person gets "Esse horário acabou de ser preenchido" and the refreshed times, never a double booking.
- **One service per booking, chained**: after confirming, "Adicionar mais um serviço" offers a second service starting exactly when the first ends (haircut 15:00–15:30 → next service at 15:30), with any professional free at that time; both bookings share the same code.
- **Code and manage link on screen** (no e-mail yet): the confirmation shows a 6-digit code and a private link. The customer manages the booking with the link, or with phone + code; they can cancel or reschedule until the salon's minimum notice (default 2 hours).
- **Booking rules per salon**: how many days ahead the agenda opens (default 30), minimum notice for a new booking (default 60 minutes) and for cancelling or rescheduling (default 120 minutes).
- **Staff agenda**: a day view per professional with bookings and blocks; staff create bookings (phone or walk-in customers), cancel and reschedule them, and block time (day off, appointment, lunch). PROFESSIONALs see and manage only their own agenda.
- **Abuse protection**: Redis-backed rate limits on public endpoints (per IP and per phone, stricter for code attempts) with an in-memory fallback when Redis is down; at most a few active bookings per phone per salon per day.
- **Modified**: professional display names become visible to customers on the public booking page (the salon chooses the display name).

Out of scope (roadmap change that covers it):
- E-mail/WhatsApp confirmations and reminders → `notifications` (the confirmation is on screen; the customer can save the link).
- Customer records, history and consent for marketing → `customers-and-service-records`; here the booking keeps the customer's name and phone only to fulfil the appointment.
- Marking a booking as attended, no-show, payment → `customers-and-service-records` / `finance`.
- Holidays calendar imports and recurring blocks → later; blocks here are one-off intervals.
- Deposits and online payment → `subscription-billing` (platform) / not planned for customers yet.

Usability goal:
- Customer: from the salon link to a confirmed booking in **4 taps + name/phone** (service, professional or "qualquer", day, time, confirm); the calendar opens on the first day with free times.
- Staff: create a booking for a customer on the phone in **one short form** from the day view (time pre-selected by tapping the free slot).
- Customer: cancel from the manage link in **2 taps**.

Security / LGPD / cost impact:
- Security: public endpoints are read-mostly, rate-limited and scoped by slug; manage links carry a high-entropy token (stored hashed); codes are checked with attempt limits. Bookings are tenant-owned rows under the same Row-Level Security; public requests run inside the salon's tenant context resolved from the slug.
- LGPD: the customer's name and phone are personal data collected to perform the appointment (legal basis: contract). They are typed as personal data (masked for support, never logged), and visible only to the salon's staff with agenda access. Professional display names are shown publicly by the salon's choice.
- Cost: Redis is a small container locally; on AWS it is part of the on/off runtime layer (decided in `aws-deployment`). No paid messaging.

## Capabilities

### New Capabilities
- `scheduling/availability`: booking rules per salon and the computation of free times per service, professional and day (hours, bookings, blocks, notice, window).
- `scheduling/bookings`: creating, cancelling and rescheduling bookings with no double booking, chained services and "qualquer profissional".
- `scheduling/public-booking`: the public page by slug, customer data, code and manage link, self-service cancel/reschedule within the notice, abuse protection.
- `scheduling/staff-agenda`: day view per professional, staff-created bookings, time blocks, and who sees which agenda.

### Modified Capabilities
- `salon/professionals`: requirement "Professional names are personal data" now states that display names are shown to customers on the public booking page.

## Impact

- Back-end: new module `scheduling` (availability, bookings, blocks, public endpoints, rate limiting); migration `V6__scheduling.sql` (`booking_settings` columns on tenant, `booking`, `time_block`, exclusion constraints); Redis client (Spring Data Redis / Lettuce) with a fallback limiter.
- Front-end: public page `/agendar/{slug}` and manage page `/agendamento/{token}` (client-rendered, no login), staff "Agenda" as the first item of the salon menu, booking rules in "Salão".
- Infra: `redis` service in the `core` Docker Compose profile.
- Landing page: the "Agenda online sem ligação" feature flips from "Em breve" to available; the launch plan feature list is updated.
