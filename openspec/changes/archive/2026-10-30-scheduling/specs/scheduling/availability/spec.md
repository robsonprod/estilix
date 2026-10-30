# Spec Delta

## Purpose

Decides which days and times a service can be booked with a professional, from the salon's rules, opening hours, the professional's hours, existing bookings and blocks.

## ADDED Requirements

### Requirement: Booking rules per salon
Each salon SHALL have booking rules: days ahead the agenda opens (1–90, default 30), minimum notice for a new booking (0–1440 minutes, default 60) and minimum notice to cancel or reschedule (0–2880 minutes, default 120); OWNERs edit them, everyone with agenda access reads them.

#### Scenario: Defaults
- **WHEN** a salon has never changed its rules
- **THEN** it opens 30 days ahead, needs 60 minutes of notice to book and 120 minutes to cancel or reschedule

#### Scenario: Out of range
- **WHEN** an OWNER sets the agenda to open 120 days ahead
- **THEN** the system responds with a validation problem

#### Scenario: Manager cannot change rules
- **WHEN** a MANAGER changes the booking rules
- **THEN** the system responds 403

### Requirement: Free times of a day
For a service, a day and a professional (or any professional who performs it), the system SHALL list start times on the 15-minute grid in the salon's time zone where the whole service duration fits inside the professional's effective hours, without overlapping a confirmed booking or a block of that professional.

#### Scenario: Fits in the interval
- **WHEN** a 60-minute service is checked for a professional working 09:00–12:00 with no bookings
- **THEN** the free times are 09:00 to 11:00 every 15 minutes

#### Scenario: Busy professional
- **WHEN** the professional has a booking 10:00–10:30
- **THEN** no free time overlaps 10:00–10:30 for a 30-minute service, and 10:30 is free

#### Scenario: Block
- **WHEN** the professional has a block 14:00–15:00
- **THEN** no free time overlaps that block

#### Scenario: Split shift
- **WHEN** the professional works 09:00–12:00 and 14:00–18:00
- **THEN** a 60-minute service is never offered at 11:30 (it would cross the break)

#### Scenario: Cancelled booking frees the time
- **WHEN** a booking is cancelled
- **THEN** its time is free again

### Requirement: Unavailable times are listed too
The free-times answer SHALL also return the grid times of the day inside the effective hours that cannot be booked, so the page can show them faded and disabled.

#### Scenario: Taken time shown as unavailable
- **WHEN** 10:00 is taken
- **THEN** 10:00 appears in the unavailable list, not in the free list

### Requirement: Booking window and notice
The system SHALL NOT offer times before now plus the minimum notice, nor days beyond the days-ahead window, computed in the salon's time zone.

#### Scenario: Too soon
- **WHEN** it is 09:20 and the notice is 60 minutes
- **THEN** the earliest free time today is 10:30

#### Scenario: Too far
- **WHEN** a day 31 days ahead is requested with a 30-day window
- **THEN** the system responds with a validation problem

### Requirement: Days with free times
For a service and a month, the system SHALL tell which days of the window have at least one free time, so the calendar enables only bookable days.

#### Scenario: Closed day disabled
- **WHEN** the salon is closed on Sundays
- **THEN** no Sunday is listed as bookable

#### Scenario: Fully booked day disabled
- **WHEN** every time of a day is taken for that service
- **THEN** the day is not listed as bookable

### Requirement: Any professional
When no professional is chosen, the system SHALL consider every active professional who performs the active service, and a time SHALL be free if at least one of them is free.

#### Scenario: Two professionals, one busy
- **WHEN** Paula is booked at 10:00 and Rosa is free and both perform the service
- **THEN** 10:00 is a free time for "Qualquer profissional"

#### Scenario: Inactive professional ignored
- **WHEN** a professional is deactivated
- **THEN** their hours never produce free times

### Requirement: Availability is tenant-isolated
Availability SHALL be computed only from the requesting salon's data.

#### Scenario: Other salon's bookings ignored
- **WHEN** salon B has bookings at 10:00
- **THEN** salon A's free times are not affected
