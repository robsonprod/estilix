# customers/data-subject-rights Delta

## Purpose

Lets the salon, as data controller, answer a customer's LGPD requests: hand over a copy of their data, and erase their identity while keeping the salon's accounts correct.

## ADDED Requirements

### Requirement: Export a customer's data
An OWNER SHALL download, for one customer, a JSON file with their profile, consent history, bookings and service records (dates, services, professionals' display names, amounts, payment methods), with no other customer's data. MANAGERs, PROFESSIONALs and support SHALL get 403.

#### Scenario: Owner exports
- **WHEN** the OWNER exports Ana's data
- **THEN** the file contains Ana's name, phone, notes, consent history, bookings and records, and nothing about other customers

#### Scenario: Manager cannot export
- **WHEN** a MANAGER requests the export
- **THEN** the system responds 403

#### Scenario: Other salon
- **WHEN** an OWNER of salon B requests the export of a customer of salon A
- **THEN** the system responds not-found

### Requirement: Anonymise a customer on request
An OWNER SHALL anonymise a customer after typing a confirmation. Name, phone, notes and the customer data copied on bookings SHALL be erased irreversibly; record amounts, dates, services, professionals and payment methods SHALL be kept. The customer SHALL show as "Cliente anonimizado".

#### Scenario: Amounts survive
- **WHEN** the OWNER anonymises Ana, who has 3 closed records totalling R$ 240,00
- **THEN** the salon's records still total R$ 240,00 and none of them shows Ana's name or phone

#### Scenario: Phone is free again
- **WHEN** Ana is anonymised and later books again with the same phone
- **THEN** a new customer is created, with no link to the anonymised history

#### Scenario: Open record blocks anonymisation
- **WHEN** the OWNER anonymises a customer with an OPEN record or a future confirmed booking
- **THEN** the system responds with a validation problem asking to close or cancel them first

### Requirement: Data-rights actions are audited without personal data
Exports and anonymisations SHALL each create an audit event with the customer id, the actor and the time, and SHALL NOT contain the customer's name, phone or notes.

#### Scenario: Anonymisation audited
- **WHEN** the OWNER anonymises a customer
- **THEN** an audit event CUSTOMER_ANONYMISED records the customer id and the owner, with no customer data
