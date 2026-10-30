# scheduling/staff-agenda Delta

## MODIFIED Requirements

### Requirement: Day view per professional
The system SHALL show, for a day in the salon's time zone, each active professional's bookings in every status (time, service, customer name and phone, origin, status) and blocks, ordered by time, with the actions allowed for each status: CONFIRMED offers start, no-show (after its start), cancel and reschedule; IN_SERVICE offers opening the record.

#### Scenario: Owner sees everyone
- **WHEN** an OWNER opens today's agenda
- **THEN** every active professional's bookings and blocks for today are shown

#### Scenario: Cancelled booking shown apart
- **WHEN** a booking was cancelled
- **THEN** it appears marked as cancelled and its time shows as free

#### Scenario: Statuses of the day
- **WHEN** Paula has a DONE booking at 09:00, an IN_SERVICE one at 10:00 and a NO_SHOW one at 11:00
- **THEN** the agenda shows them as "Concluído", "Em atendimento" and "Não veio", and 11:00 shows as free

#### Scenario: Walk-in shown on the agenda
- **WHEN** a walk-in record with Paula starts at 14:10
- **THEN** the agenda shows the walk-in from 14:10 with origin WALK_IN and status IN_SERVICE
