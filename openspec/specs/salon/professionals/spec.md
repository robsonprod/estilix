# salon/professionals Specification

## Purpose
Lists the people who appear in the salon's agenda — with or without a login — which services each one performs and when each one works.

## Requirements

### Requirement: Professionals are agenda entries
The system SHALL keep professionals per salon with display name (1–60 chars), optional link to a member user of the same salon, color for the agenda, active flag and creation time; a professional SHALL NOT require a login.

#### Scenario: Professional without login
- **WHEN** an OWNER adds the professional "Caio" without linking a member
- **THEN** Caio is saved as an active professional

#### Scenario: Link to a member
- **WHEN** an OWNER links the professional "Paula" to the member Paula Rocha
- **THEN** the professional shows as linked and the member sees "Paula" as her own professional profile in `/me`

#### Scenario: Link to a user of another salon
- **WHEN** an OWNER links a professional to a user who is not a member of the salon
- **THEN** the system responds with a validation problem

#### Scenario: One professional per member
- **WHEN** an OWNER links a second professional to a member already linked
- **THEN** the system responds with a conflict problem

### Requirement: Professional names are personal data
Professional display names SHALL follow the personal-data rules: in clear for members of the salon, masked for PLATFORM_SUPPORT unless a break-glass session on that salon is active.

#### Scenario: Support lists professionals
- **WHEN** support lists the professionals of a salon without break-glass
- **THEN** the names are masked and the colors, active flags and service counts are in clear

### Requirement: Who manages professionals
OWNERs and MANAGERs SHALL create, edit, activate and deactivate professionals and set their services and hours; PROFESSIONALs SHALL list them; members of other salons SHALL get not-found.

#### Scenario: Professional cannot add colleagues
- **WHEN** a PROFESSIONAL tries to add a professional
- **THEN** the system responds 403

#### Scenario: Cross-salon access
- **WHEN** an OWNER of salon A requests a professional of salon B by id
- **THEN** the system responds not-found

### Requirement: Services performed
The system SHALL record which active services each professional performs, replaced as a whole set; services and professional SHALL belong to the same salon.

#### Scenario: Set services
- **WHEN** a MANAGER sets Caio's services to "Corte masculino" and "Barba"
- **THEN** Caio performs exactly those two services

#### Scenario: Service of another salon
- **WHEN** a MANAGER includes a service id of another salon in Caio's services
- **THEN** the system responds with a validation problem and Caio's services are unchanged

#### Scenario: Deactivated service
- **WHEN** a service is deactivated
- **THEN** it no longer appears among the services a professional performs, and reactivating it restores the previous links

### Requirement: Weekly hours default to the salon's
Each professional SHALL either follow the salon's opening hours (the default) or have own weekly intervals following the same rules as opening hours; own intervals SHALL lie within the salon's opening hours.

#### Scenario: Default hours
- **WHEN** a professional is created without hours
- **THEN** their effective hours are the salon's opening hours, also after the salon changes them

#### Scenario: Own hours
- **WHEN** a MANAGER sets Paula to Tuesday 13:00–19:00 only
- **THEN** Paula's effective hours are only that interval

#### Scenario: Outside the salon's hours
- **WHEN** a MANAGER sets a professional to work Sunday while the salon is closed on Sunday
- **THEN** the system responds with a validation problem

#### Scenario: Salon hours shrink later
- **WHEN** the salon removes Saturday afternoon while a professional has own hours on Saturday afternoon
- **THEN** the salon change is saved and the professional's effective hours are clipped to the salon's opening hours, with the conflict shown on the professional's screen
