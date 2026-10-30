# customers/customer-records Delta

## Purpose

Keeps the salon's customer list, one customer per mobile phone, built automatically from bookings and service records, with notes, marketing consent and a visit history the staff can read at a glance.

## ADDED Requirements

### Requirement: One customer per mobile phone per salon
A customer SHALL belong to one salon and be identified by a mobile phone normalised to E.164; two customers of the same salon SHALL NOT share a phone. The same phone in two salons SHALL be two unrelated customers.

#### Scenario: Same phone written differently
- **WHEN** staff creates a customer with "(11) 98888-7777" and later another with "+55 11 98888-7777"
- **THEN** the second request responds with a validation problem pointing to the existing customer

#### Scenario: Same phone in two salons
- **WHEN** salon A and salon B each have a customer with the same phone
- **THEN** each salon sees only its own customer, name and history

#### Scenario: Other salon's customer
- **WHEN** an OWNER of salon B requests a customer id of salon A
- **THEN** the system responds not-found

### Requirement: Customers are linked automatically
Every new booking (public or staff) and every new service record SHALL be linked to the salon's customer with that phone, creating the customer with the given name when none exists. An existing customer's name SHALL NOT be overwritten by a booking.

#### Scenario: First public booking creates the customer
- **WHEN** a new phone books a haircut on the public page as "Ana Souza"
- **THEN** the salon has a customer "Ana Souza" with that phone, linked to the booking

#### Scenario: Returning customer with another name
- **WHEN** a customer saved as "Ana Souza" books again typing "Aninha"
- **THEN** the booking is linked to "Ana Souza" and the customer's name is unchanged

#### Scenario: Existing bookings are linked on upgrade
- **WHEN** the change is deployed to a salon with bookings made before it
- **THEN** each of those bookings is linked to a customer by its phone

### Requirement: Customer profile
Staff with customer management SHALL edit a customer's name (2–80 chars), phone (still unique) and free-text notes (max 1000 chars). Notes SHALL be shown with the hint "visível para a equipe".

#### Scenario: Fix the name
- **WHEN** a MANAGER renames "Aninha" to "Ana Souza"
- **THEN** the customer and their future records show "Ana Souza"

#### Scenario: Notes too long
- **WHEN** staff saves notes with 1001 characters
- **THEN** the system responds with a validation problem on the notes field

### Requirement: Marketing consent is explicit and revocable
A customer SHALL have marketing consent GRANTED, REVOKED or UNKNOWN (default). Every change SHALL store the date, the source (STAFF or CUSTOMER) and the acting user, and SHALL be visible on the customer. Booking or being served SHALL NOT grant consent.

#### Scenario: Default is unknown
- **WHEN** a customer is created from a booking
- **THEN** their marketing consent is UNKNOWN

#### Scenario: Staff records consent
- **WHEN** a MANAGER marks that Ana agreed to receive messages
- **THEN** her consent is GRANTED with today's date, source STAFF and the manager as actor

#### Scenario: Revoke
- **WHEN** consent is revoked
- **THEN** it becomes REVOKED with the date of the revocation, and the previous grant date stays visible in the history

### Requirement: Customer history
The system SHALL show for a customer: number of visits (closed records), no-shows, total spent, last visit date, usual interval in days (median of gaps between consecutive visit days, shown only with at least 3 visits) and the closed records, newest first.

#### Scenario: Usual interval
- **WHEN** Ana's closed records are on days 1, 31 and 66 of the year
- **THEN** her usual interval is 32 days (median of 30 and 35, rounded down) and her last visit is day 66

#### Scenario: Too few visits
- **WHEN** a customer has 2 visits
- **THEN** the usual interval is not shown

#### Scenario: Voided records do not count
- **WHEN** a record of Ana is voided
- **THEN** it is not counted in visits nor in the total spent

### Requirement: Customer list and search
Staff SHALL list customers ordered by last visit (most recent first, never-visited last), paged, and search by the start of any word of the name (at least 2 letters, accents and case ignored) or by phone digits (at least 4, matched anywhere in the phone).

#### Scenario: Search by phone digits
- **WHEN** staff searches "98888"
- **THEN** customers whose phone contains "98888" are returned

#### Scenario: Search ignores accents
- **WHEN** staff searches "joao"
- **THEN** "João Silva" is returned

### Requirement: Who sees which customers
OWNERs and MANAGERs SHALL see and edit every customer of the salon unmasked. A PROFESSIONAL SHALL see, unmasked, only customers with a booking or record of the professional linked to them, and SHALL NOT change consent. Support SHALL see customers masked.

#### Scenario: Professional sees their own customers
- **WHEN** Paula (PROFESSIONAL) lists customers
- **THEN** only customers she served or has bookings with are returned

#### Scenario: Professional opens another customer
- **WHEN** Paula requests a customer only served by Rosa
- **THEN** the system responds not-found

#### Scenario: Support sees masked data
- **WHEN** support opens the salon's customer list
- **THEN** names, phones and notes are masked

### Requirement: Customer data stays out of logs and audit details
Customer names, phones and notes SHALL NOT appear in application logs, error responses or audit event details. Audit events SHALL reference customers by id.

#### Scenario: Customer edit is logged without data
- **WHEN** staff edits Ana's notes and an error occurs in the same request
- **THEN** neither the logs nor the error response contain her name, phone or notes
