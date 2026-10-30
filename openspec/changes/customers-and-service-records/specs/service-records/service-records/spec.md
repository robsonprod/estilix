# service-records/service-records Delta

## Purpose

Records what was actually done in each visit (the comanda): services, who did them, how much was charged and how it was paid, quickly enough to fill in at the counter in a few taps.

## ADDED Requirements

### Requirement: A record has items and one payment method
A service record SHALL belong to one customer and hold one or more items, each with an active service, a professional who performs it and a price in cents (0 to 1,000,000), prefilled with the catalog price and editable. The total SHALL be the sum of the items. Closing SHALL require one payment method: PIX, CASH, DEBIT or CREDIT.

#### Scenario: Price prefilled and edited
- **WHEN** staff adds "Corte" (catalog R$ 50,00) and changes the price to R$ 45,00
- **THEN** the item is saved with 4500 cents and the record total is R$ 45,00

#### Scenario: Price from a "starting at" service
- **WHEN** staff adds "Escova" priced "a partir de R$ 60,00"
- **THEN** the item is prefilled with R$ 60,00 and the price can be raised

#### Scenario: Negative price
- **WHEN** staff saves an item with a negative price
- **THEN** the system responds with a validation problem on the price

#### Scenario: Other salon
- **WHEN** an OWNER of salon B requests a record of salon A
- **THEN** the system responds not-found

### Requirement: Record states
A record SHALL be OPEN, CLOSED or VOIDED. Items SHALL be added, edited or removed only while OPEN, and an OPEN record SHALL keep at least one item. A customer SHALL have at most one OPEN record.

#### Scenario: Closed record is read-only
- **WHEN** staff tries to change an item of a CLOSED record
- **THEN** the system responds with a validation problem

#### Scenario: Last item cannot be removed
- **WHEN** staff removes the only item of an OPEN record
- **THEN** the system responds with a validation problem suggesting to void the record

### Requirement: Start a record from a booking
Staff SHALL start a CONFIRMED booking on its own day (salon time zone). The booking SHALL become IN_SERVICE and the record SHALL open with one item per booking of the same code that day that is still CONFIRMED. If the customer already has an OPEN record, the items SHALL be added to it.

#### Scenario: Customer arrives
- **WHEN** the MANAGER taps "Iniciar atendimento" on Ana's 15:00 haircut with Paula
- **THEN** the booking is IN_SERVICE and an OPEN record for Ana has the item "Corte · Paula · R$ 50,00"

#### Scenario: Chained services come together
- **WHEN** Ana booked a haircut at 15:00 and a manicure at 15:30 with the same code and her 15:00 booking is started
- **THEN** both bookings are IN_SERVICE and the record has two items

#### Scenario: Booking on another day
- **WHEN** staff tries to start a booking for tomorrow
- **THEN** the system responds with a validation problem

### Requirement: Walk-in record occupies the agenda
Staff SHALL start a record for a customer without a booking ("Novo atendimento") by phone, name if new, service and professional. Each item added that does not come from a booking SHALL occupy its professional's agenda from now, or from the end of that professional's previous item in the record, for the service duration.

#### Scenario: Walk-in takes the time
- **WHEN** a MANAGER starts a 30-minute haircut with Paula for a walk-in at 14:10
- **THEN** Paula's agenda is occupied from 14:10 to 14:40 and those times are no longer offered to customers

#### Scenario: Walk-in over a booking
- **WHEN** Paula has a booking at 14:30 and staff starts a 30-minute walk-in with her at 14:10
- **THEN** the system responds with a slot-taken problem naming the 14:30 booking, and no record is opened

#### Scenario: Known phone fills the name
- **WHEN** staff types the phone of an existing customer
- **THEN** the customer's name is shown and the record is linked to that customer

### Requirement: Close a record with payment
Closing an OPEN record SHALL set the payment method, the closing time and CLOSED; its bookings and walk-in entries SHALL become DONE. Agenda time planned after the closing moment SHALL be freed, keeping the entry until the next 15-minute boundary.

#### Scenario: Close in two taps
- **WHEN** staff taps "Concluir" and chooses Pix on Ana's record
- **THEN** the record is CLOSED with payment PIX and her booking is DONE

#### Scenario: Finished early
- **WHEN** a 60-minute service started at 15:00 is closed at 15:35
- **THEN** the professional's agenda is free again from 15:45

#### Scenario: Closing without payment method
- **WHEN** staff closes a record without a payment method
- **THEN** the system responds with a validation problem on the payment method

### Requirement: Void a record with a reason
OWNERs and MANAGERs SHALL void an OPEN or CLOSED record with a reason (5–200 chars, staff only). Voiding an OPEN record SHALL return its bookings to CONFIRMED and free walk-in time; voiding a CLOSED record SHALL leave the agenda as it was.

#### Scenario: Started by mistake
- **WHEN** a MANAGER voids an OPEN record started on the wrong booking
- **THEN** the record is VOIDED and the booking is CONFIRMED again

#### Scenario: Professional cannot void
- **WHEN** a PROFESSIONAL tries to void a record
- **THEN** the system responds 403

### Requirement: Who sees and records which service records
OWNERs and MANAGERs SHALL see and manage every record unmasked. A PROFESSIONAL SHALL see records with at least one item of the professional linked to them, add or edit only their own items, and start or close records containing their items. Support SHALL see records with customer data masked.

#### Scenario: Professional sees only their records
- **WHEN** Paula (PROFESSIONAL) lists today's records
- **THEN** only records with an item of Paula are returned

#### Scenario: Professional adds an item for a colleague
- **WHEN** Paula adds an item with Rosa as the professional
- **THEN** the system responds 403

#### Scenario: Support sees masked customer
- **WHEN** support opens a record
- **THEN** the customer's name and phone are masked and amounts are shown

### Requirement: Records list by day
Staff SHALL list the records of a day (salon time zone) with customer name, items, total, payment method and state, plus the day's total of CLOSED records.

#### Scenario: Day total ignores voided and open
- **WHEN** a day has a CLOSED record of R$ 80,00, an OPEN one of R$ 50,00 and a VOIDED one of R$ 30,00
- **THEN** the day's total is R$ 80,00

### Requirement: Record changes are auditable without personal data
Voiding a record SHALL create an audit event with the record id, the actor and the amount, without the reason text or customer data. Record operations SHALL NOT log customer data.

#### Scenario: Void audited
- **WHEN** a MANAGER voids a record of R$ 80,00
- **THEN** an audit event SERVICE_RECORD_VOIDED records the record id, the manager and 8000 cents, with no customer name, phone or reason
