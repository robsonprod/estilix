# Proposal

## Why

The agenda shows who is coming, but nothing records what happened: did the customer show up, what was done, how much was paid. Without that, the salon has no customer history, so it cannot answer "when did Ana last come?" or "who has not come back in 45 days?", which is the promise behind the personalised message next month. This change turns each visit into a quick service record (comanda) and builds the customer list from it.

## What Changes

- **Customers per salon, identified by mobile phone**: one customer per phone per salon. They are created and linked automatically from bookings (public and staff) and from service records. Staff can add notes, fix the name and record marketing-message consent (yes/no, with date and source).
- **Customer history**: visits, total spent, last visit and usual interval between visits (median of the gaps), computed from closed service records. The list can be searched by name or phone.
- **Service records (comanda)**: a record has one or more items (service, professional, price prefilled from the catalog and editable) and one payment method (Pix, dinheiro, débito, crédito). States: OPEN → CLOSED, or OPEN → VOIDED.
- **Two ways to start a record**:
  - **From a booking**: "Iniciar atendimento" when the customer arrives. The booking becomes IN_SERVICE and the record opens with the booking's service and professional (chained bookings of the same code come in as items).
  - **Walk-in**: "Novo atendimento" starts now. It also occupies the professional's agenda from now until the end of the service, so the slot is no longer offered to others.
- **Closing**: "Concluir" asks for the payment method (prices already filled). The record becomes CLOSED and its bookings DONE.
- **No-show**: "Não veio" marks the booking NO_SHOW and frees the time for new bookings. It is counted in the customer's history.
- **Who sees what**: a PROFESSIONAL sees and records only their own records and the customers they served or have bookings with. OWNERs and MANAGERs see everything. Support sees customer data masked.
- **Data-subject rights (LGPD)**: the OWNER can export a customer's data (JSON) and anonymise a customer on request. Anonymisation keeps amounts and dates for the salon's accounts but erases name, phone and notes. Both actions are audited.
- **Modified (scheduling)**:
  - Bookings gain the statuses IN_SERVICE, DONE and NO_SHOW. The no-overlap rule now protects CONFIRMED, IN_SERVICE and DONE entries; NO_SHOW and CANCELLED free the time.
  - The staff agenda shows the new statuses and the actions to start, finish or mark a no-show.

Out of scope (roadmap change that covers it):
- Cash register, expenses, commission per professional and reports → `finance`. Here a record stores only the amounts and the payment method; nothing is processed or charged.
- Sending messages to customers (birthday, "faz tempo que você não vem") → `notifications` / `customer-retention`. Here we only record consent.
- Ranking customers by churn risk → `customer-retention`.
- Full-text and fuzzy search across customers and notes → `customer-search`. Here search is a simple name/phone prefix search.
- Products sold in the record and stock deduction → `inventory`.
- Discounts and coupons as separate fields → later. The editable item price covers discounts for now.
- Dashboard indicators (revenue of the day, no-show rate) → `dashboard`.

Usability goal:
- Staff, booked customer: from the agenda to a closed record in **3 taps** ("Iniciar atendimento" → "Concluir" → payment method). The prices are prefilled.
- Staff, walk-in: "Novo atendimento" → phone (the name fills in when the customer is known) → service → "Iniciar". Closing is the same 2 taps.
- No-show: **2 taps** from the booking ("Não veio" → confirm).
- Owner: open a customer and see last visit, total and usual interval **without scrolling** on a phone.

Security / LGPD / cost impact:
- Security: customers and records are tenant-owned rows under the same Row-Level Security and composite foreign keys. Every endpoint checks a permission from the catalog. PROFESSIONALs are restricted to their own professional on the server side, not only in the UI.
- LGPD: the legal basis is the contract for the customer record and service history, and consent for marketing messages, stored with date and source and revocable. Name, phone and notes are personal data types: masked for support, never logged, never in audit details. Deletion on request is done by anonymisation, so the salon's amounts stay correct. Export and anonymisation are OWNER-only and audited. Health-like notes (allergies) are allowed but flagged in the UI as "visível para a equipe".
- Cost: no new infrastructure. Only tables, indexes and one migration.

## Capabilities

### New Capabilities
- `customers/customer-records`: the salon's customer list. One customer per mobile phone, auto-linking from bookings and records, editable name and notes, marketing consent, history (visits, no-shows, total spent, last visit, usual interval), simple search and who sees which customers.
- `customers/data-subject-rights`: export and anonymisation of a customer by the OWNER, with audit and the rule that amounts survive anonymisation.
- `service-records/service-records`: the comanda. It has items, prices in cents, one payment method and the OPEN/CLOSED/VOIDED states. It starts from a booking or as a walk-in, closes with payment and is voided with a reason. A professional sees only their own.

### Modified Capabilities
- `scheduling/bookings`: requirement "A booking is one service with one professional" adds the statuses IN_SERVICE, DONE and NO_SHOW. Requirement "No double booking, first commit wins" now protects CONFIRMED, IN_SERVICE and DONE entries; NO_SHOW frees the time.
- `scheduling/staff-agenda`: requirement "Day view per professional" shows the new statuses and the actions available for each one.

## Impact

- Back-end:
  - New modules `customers` and `servicerecords`.
  - `scheduling` publishes a small booking-lifecycle interface used by service records, and service records call it to keep the dependency one-way.
  - Migration `V8__customers_and_service_records.sql`:
    - tables `customer`, `service_record` and `service_record_item`;
    - `agenda_entry.customer_id`, the new statuses and origin WALK_IN;
    - the exclusion constraint predicate is rebuilt;
    - the customer is backfilled from existing bookings.
  - New permissions (`VIEW_CUSTOMERS`, `MANAGE_CUSTOMERS`, `MANAGE_MARKETING_CONSENT`, `CUSTOMER_DATA_RIGHTS`, `VIEW_SERVICE_RECORDS`, `MANAGE_SERVICE_RECORDS`, `VOID_SERVICE_RECORDS`) and new audit actions.
- Front-end:
  - "Clientes" menu (list, detail with history).
  - Actions in the agenda booking detail: "Iniciar atendimento", "Não veio" and "Concluir".
  - "Novo atendimento" button on the agenda.
  - Comanda sheet (items, prices, payment).
  - Owner-only "Exportar dados" and "Anonimizar" on the customer detail.
- Seed: demo customers with past closed records (so history and usual interval show real numbers), one no-show and one open record.
- Landing page: "Atendimento em 3 toques" flips to available (the same migration updates the launch plan list). "Clientes para chamar" stays "Em breve" until `customer-retention`.
- Docs: README, an ADR on the record/booking lifecycle and anonymisation, the study guide (state machine, LGPD rights, median interval) and the skills map.
