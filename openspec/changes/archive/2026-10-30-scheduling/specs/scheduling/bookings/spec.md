# Spec Delta

## Purpose

Keeps the salon's appointments consistent: one service per booking, confirmed immediately, never two bookings for the same professional at the same time, and simple cancel, reschedule and chaining of services.

## ADDED Requirements

### Requirement: A booking is one service with one professional
A booking SHALL hold one active service, one active professional who performs it, a start time on the 15-minute grid, an end time equal to start plus the service duration at booking time, the customer's name and phone, its origin (PUBLIC or STAFF) and status CONFIRMED or CANCELLED.

#### Scenario: Duration copied at booking time
- **WHEN** a 30-minute service is booked and its duration later changes to 45 minutes
- **THEN** the existing booking keeps its 30-minute end time

#### Scenario: Professional who does not perform the service
- **WHEN** a booking asks for a professional who does not perform the service
- **THEN** the system responds with a validation problem

#### Scenario: Inactive service
- **WHEN** a booking asks for an inactive service
- **THEN** the system responds with a validation problem

### Requirement: Bookings are confirmed immediately
A valid booking for a free time SHALL be created as CONFIRMED without any approval step.

#### Scenario: Successful booking
- **WHEN** a customer books a free 15:00 haircut with Paula
- **THEN** the booking is CONFIRMED and 15:00 is no longer free for Paula

### Requirement: No double booking, first commit wins
The database SHALL refuse a confirmed booking or block that overlaps another confirmed booking or block of the same professional; when two requests race for the same time, exactly one SHALL succeed and the others SHALL get a slot-taken problem with the message "Esse horário acabou de ser preenchido".

#### Scenario: Two customers, same time
- **WHEN** two customers confirm Paula at 15:00 at the same moment
- **THEN** exactly one booking exists and the other customer gets a slot-taken problem

#### Scenario: Back-to-back
- **WHEN** Paula has 15:00–15:30 and a customer books 15:30
- **THEN** the booking is accepted

#### Scenario: Different durations overlap
- **WHEN** Paula has 15:00–16:00 and a customer books a 30-minute service at 15:30
- **THEN** the system responds with a slot-taken problem

#### Scenario: Different professionals
- **WHEN** Paula and Rosa are booked at the same 15:00
- **THEN** both bookings are accepted

### Requirement: Any professional is resolved at booking time
When the booking asks for "qualquer profissional", the system SHALL assign a free professional who performs the service, preferring the one with fewer bookings that day, and SHALL try the next free professional if the first is taken concurrently.

#### Scenario: Assigned to a free professional
- **WHEN** Paula is busy at 15:00 and Rosa is free and a customer books "qualquer profissional"
- **THEN** the booking is assigned to Rosa

#### Scenario: Everybody taken during the race
- **WHEN** all professionals who perform the service are taken while the request is processed
- **THEN** the customer gets a slot-taken problem

### Requirement: Chained services
After a booking, the customer or staff SHALL be able to add another booking for the same customer that starts exactly when the previous one ends, sharing the same booking group and code; it follows all the rules of a normal booking.

#### Scenario: Next service at the end of the first
- **WHEN** a haircut is booked 15:00–15:30 and the customer adds a manicure
- **THEN** the manicure is offered only at 15:30, with any professional free at 15:30 who performs it

#### Scenario: Nobody free at the end time
- **WHEN** no professional who performs the second service is free at 15:30
- **THEN** the system says the service cannot be added right after and the first booking is unchanged

### Requirement: Cancel
A booking SHALL be cancelled by setting CANCELLED with who cancelled (CUSTOMER or STAFF) and when; cancelling an already cancelled booking SHALL succeed without changes.

#### Scenario: Cancel frees the time
- **WHEN** a confirmed booking is cancelled
- **THEN** its time becomes free and the booking remains visible as cancelled in the agenda

#### Scenario: Cancel twice
- **WHEN** a cancelled booking is cancelled again
- **THEN** the system answers success and nothing changes

### Requirement: Reschedule
Rescheduling SHALL move a confirmed booking to another free time (and optionally another professional who performs the service) in one atomic step; if the new time is taken, the booking SHALL stay at its original time.

#### Scenario: Move to a free time
- **WHEN** a booking at 15:00 is rescheduled to 16:00, which is free
- **THEN** the booking is at 16:00 and 15:00 is free

#### Scenario: Target taken
- **WHEN** the new time is taken
- **THEN** the system responds with a slot-taken problem and the booking stays at 15:00

### Requirement: Customer data on the booking is personal data
The customer's name and phone on a booking SHALL follow the personal-data rules: visible to staff with agenda access, masked for platform support unless break-glass is active, never logged.

#### Scenario: Support reads the agenda
- **WHEN** support reads a salon's agenda without break-glass
- **THEN** customer names and phones are masked

#### Scenario: Booking log line
- **WHEN** a booking with a known customer name and phone is created or refused
- **THEN** neither value appears in the logs

### Requirement: Bookings are tenant-isolated
Bookings SHALL belong to one salon and SHALL reference only that salon's services and professionals.

#### Scenario: Cross-salon reference
- **WHEN** a booking in salon A references a professional of salon B
- **THEN** the database refuses it
