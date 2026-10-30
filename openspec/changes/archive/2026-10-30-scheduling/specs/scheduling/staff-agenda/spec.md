# Spec Delta

## Purpose

Gives the salon's staff one place to see and run the day: each professional's bookings and blocks, bookings for customers who call or walk in, and time off.

## ADDED Requirements

### Requirement: Day view per professional
The system SHALL show, for a day in the salon's time zone, each active professional's confirmed and cancelled bookings (time, service, customer name and phone, origin) and blocks, ordered by time.

#### Scenario: Owner sees everyone
- **WHEN** an OWNER opens today's agenda
- **THEN** every active professional's bookings and blocks for today are shown

#### Scenario: Cancelled booking shown apart
- **WHEN** a booking was cancelled
- **THEN** it appears marked as cancelled and its time shows as free

### Requirement: Who sees and manages which agenda
OWNERs and MANAGERs SHALL see and manage every professional's agenda; a PROFESSIONAL SHALL see and manage only the agenda of the professional linked to them; members of other salons SHALL get not-found.

#### Scenario: Professional sees only their agenda
- **WHEN** Paula (a PROFESSIONAL linked to the professional "Paula") opens the agenda
- **THEN** only Paula's bookings and blocks are returned

#### Scenario: Professional without link
- **WHEN** a PROFESSIONAL with no linked professional opens the agenda
- **THEN** the agenda is empty with a hint to ask the owner to link them

#### Scenario: Professional edits a colleague's booking
- **WHEN** Paula tries to cancel a booking of Rosa
- **THEN** the system responds 403

#### Scenario: Other salon
- **WHEN** an OWNER of salon B requests salon A's agenda
- **THEN** the system responds not-found

### Requirement: Staff-created bookings
Staff with agenda access SHALL create bookings with origin STAFF for any free time inside the professional's effective hours; staff bookings SHALL ignore the minimum notice for new bookings but SHALL still refuse overlaps.

#### Scenario: Walk-in now
- **WHEN** a MANAGER books a walk-in customer starting in 10 minutes with a 60-minute notice
- **THEN** the booking is created with origin STAFF

#### Scenario: Staff booking overlap
- **WHEN** staff books a time already taken
- **THEN** the system responds with a slot-taken problem

### Requirement: Staff cancel and reschedule anytime
Staff with access to the booking's professional SHALL cancel or reschedule it regardless of the customer cancel notice.

#### Scenario: Cancel 10 minutes before
- **WHEN** a MANAGER cancels a booking starting in 10 minutes
- **THEN** the booking is CANCELLED by STAFF

### Requirement: Time blocks
Staff SHALL block a professional's time with an optional short reason (max 80 chars, shown only to staff), on the 15-minute grid; a block SHALL NOT overlap a confirmed booking or another block, and removing it frees the time.

#### Scenario: Day off
- **WHEN** a MANAGER blocks Rosa all of Friday
- **THEN** Rosa has no free times on Friday

#### Scenario: Block over a booking
- **WHEN** staff tries to block a time with a confirmed booking
- **THEN** the system responds with a slot-taken problem naming the booking time

#### Scenario: Remove block
- **WHEN** the block is removed
- **THEN** the time is free again

### Requirement: Agenda changes are auditable without personal data
Cancellations and reschedules by staff SHALL be recorded in the audit log with booking id, actor and old/new times, without customer data.

#### Scenario: Staff reschedule audited
- **WHEN** a MANAGER moves a booking from 15:00 to 16:00
- **THEN** an audit event records the booking id, the actor and both times, with no customer name or phone
