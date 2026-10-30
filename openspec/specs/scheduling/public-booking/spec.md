# scheduling/public-booking Specification

## Purpose
Lets a customer book, view, cancel and reschedule an appointment through the salon's public link, without an account and without calling, while keeping the page safe from abuse.

## Requirements

### Requirement: Public booking page per salon
The system SHALL serve, without login, a booking page for each ACTIVE salon at its slug, showing the salon's name, active services (name, duration, price label) and active professionals by display name; salons that are not ACTIVE or unknown slugs SHALL show a not-found page.

#### Scenario: Active salon
- **WHEN** a customer opens `/agendar/salao-bela-vista`
- **THEN** the page shows the salon's name and its active services with prices

#### Scenario: Pending salon
- **WHEN** a customer opens the slug of a salon in PENDING_APPROVAL
- **THEN** the page says the salon was not found

### Requirement: Booking flow in pt-BR
The page SHALL guide the customer through service, professional or "Qualquer profissional", day in a calendar (only bookable days enabled, opening on the first bookable day), time (free times highlighted in green, unavailable times faded and disabled), name and phone, and a confirmation step showing the summary.

#### Scenario: Calendar days
- **WHEN** the customer chooses a service
- **THEN** days without free times are disabled in the calendar

#### Scenario: Times of a day
- **WHEN** the customer taps a day
- **THEN** free times appear enabled in green and unavailable times appear faded and cannot be selected

#### Scenario: Time taken meanwhile
- **WHEN** the customer confirms a time another customer just took
- **THEN** the page shows "Esse horário acabou de ser preenchido. Escolha outro horário." and refreshes the times of that day

### Requirement: Customer data
The page SHALL ask only the customer's name (2–80 chars) and a Brazilian mobile phone, and SHALL state that the data is used only for this appointment.

#### Scenario: Invalid phone
- **WHEN** the customer types a phone with fewer than 10 digits
- **THEN** the page shows a validation message on the phone and nothing is booked

### Requirement: Code and manage link
After confirming, the system SHALL show a 6-digit code and a private manage link (`/agendamento/{token}`) for the booking group; the token SHALL have at least 128 bits of entropy and SHALL be stored only as a hash.

#### Scenario: Confirmation screen
- **WHEN** a booking is confirmed
- **THEN** the screen shows salon, service, professional, day and time, the 6-digit code, the manage link and "Adicionar mais um serviço"

#### Scenario: Token not stored in clear
- **WHEN** the database is inspected
- **THEN** no manage token appears in clear text

### Requirement: Manage with link or phone and code
A customer SHALL view their bookings of the group with the manage link, or by entering the salon slug, phone and code; wrong codes SHALL count against an attempt limit.

#### Scenario: Open with the link
- **WHEN** the customer opens the manage link
- **THEN** the page shows the group's bookings with their status

#### Scenario: Phone and code
- **WHEN** the customer enters the right phone and code on the salon page
- **THEN** the same bookings are shown

#### Scenario: Too many wrong codes
- **WHEN** a phone gets 5 wrong codes within 15 minutes
- **THEN** further attempts for that phone are refused with a rate-limited problem for 15 minutes

### Requirement: Self-service cancel and reschedule within the notice
The customer SHALL cancel or reschedule a confirmed booking only until the salon's cancel notice before its start; after that the page SHALL ask them to contact the salon.

#### Scenario: Cancel in time
- **WHEN** the customer cancels 3 hours before with a 2-hour notice
- **THEN** the booking is CANCELLED by CUSTOMER

#### Scenario: Too late
- **WHEN** the customer tries to cancel 1 hour before with a 2-hour notice
- **THEN** the system refuses with a too-late problem and the page shows the salon's phone

#### Scenario: Reschedule in time
- **WHEN** the customer moves the booking to another free time 3 hours before
- **THEN** the booking moves and keeps its code and link

### Requirement: Abuse protection on public endpoints
Public endpoints SHALL be rate-limited per IP and per phone, return a rate-limited problem (HTTP 429 with Retry-After) when exceeded, keep limiting with an in-memory fallback when the shared limiter store is unavailable, and allow at most 3 active future bookings per phone per salon per day.

#### Scenario: Burst from one IP
- **WHEN** one IP sends 50 booking requests within a minute
- **THEN** requests beyond the limit get HTTP 429 with Retry-After

#### Scenario: Limiter store down
- **WHEN** the shared limiter store is unreachable
- **THEN** public booking still works and limits are applied per instance

#### Scenario: Too many bookings for one phone
- **WHEN** a phone already has 3 active bookings on the same day in the salon
- **THEN** a fourth booking is refused with a validation problem

### Requirement: Public endpoints expose no other customer's data
Availability answers SHALL contain times only, never names, phones or booking ids of other customers.

#### Scenario: Taken time
- **WHEN** a time is taken by another customer
- **THEN** the public answer marks it unavailable without any information about who took it
