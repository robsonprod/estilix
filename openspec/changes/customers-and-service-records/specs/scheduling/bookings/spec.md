# scheduling/bookings Delta

## MODIFIED Requirements

### Requirement: A booking is one service with one professional
A booking SHALL hold one active service, one active professional who performs it, a start time on the 15-minute grid (a walk-in starts at the minute it begins), an end time equal to start plus the service duration at booking time, the customer's name and phone, the salon customer it is linked to, its origin (PUBLIC, STAFF or WALK_IN) and status CONFIRMED, IN_SERVICE, DONE, NO_SHOW or CANCELLED.

#### Scenario: Duration copied at booking time
- **WHEN** a 30-minute service is booked and its duration later changes to 45 minutes
- **THEN** the existing booking keeps its 30-minute end time

#### Scenario: Professional who does not perform the service
- **WHEN** a booking asks for a professional who does not perform the service
- **THEN** the system responds with a validation problem

#### Scenario: Inactive service
- **WHEN** a booking asks for an inactive service
- **THEN** the system responds with a validation problem

#### Scenario: Status follows the visit
- **WHEN** a confirmed booking is started and its record is closed
- **THEN** the booking goes from CONFIRMED to IN_SERVICE and then to DONE

### Requirement: No double booking, first commit wins
The database SHALL refuse a booking or block that overlaps another CONFIRMED, IN_SERVICE or DONE booking or block of the same professional; when two requests race for the same time, exactly one SHALL succeed and the others SHALL get a slot-taken problem with the message "Esse horário acabou de ser preenchido". NO_SHOW and CANCELLED bookings SHALL NOT hold time.

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

#### Scenario: Service in progress holds the time
- **WHEN** Paula's 15:00–16:00 booking is IN_SERVICE and a customer tries to book 15:30
- **THEN** the system responds with a slot-taken problem

## ADDED Requirements

### Requirement: No-show frees the time
Staff with access to the booking's professional SHALL mark a CONFIRMED booking as NO_SHOW once its start time has passed. The time SHALL become free for new bookings, and the no-show SHALL count in the customer's history.

#### Scenario: Customer did not come
- **WHEN** at 15:20 the MANAGER marks Ana's 15:00–16:00 booking as "Não veio"
- **THEN** the booking is NO_SHOW and Paula's 15:30 is free again

#### Scenario: Too early for no-show
- **WHEN** staff marks a booking as no-show before its start time
- **THEN** the system responds with a validation problem

#### Scenario: Other salon
- **WHEN** an OWNER of salon B marks a booking of salon A as no-show
- **THEN** the system responds not-found

### Requirement: Attended bookings are final
Only CONFIRMED bookings SHALL be cancelled or rescheduled, by customers or staff. IN_SERVICE, DONE and NO_SHOW bookings SHALL be refused with a validation problem, and the customer's manage page SHALL show them without actions.

#### Scenario: Customer cancels after the visit
- **WHEN** a customer opens the manage link of a DONE booking and tries to cancel it
- **THEN** the system responds with a validation problem and the booking stays DONE

#### Scenario: Staff reschedules a no-show
- **WHEN** staff tries to reschedule a NO_SHOW booking
- **THEN** the system responds with a validation problem suggesting a new booking
