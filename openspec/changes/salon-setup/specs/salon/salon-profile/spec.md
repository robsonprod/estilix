# Spec Delta

## Purpose

Keeps the salon's own identity and working week — name, contact, address, time zone, opening hours and public address — as the reference that the agenda and the public booking page will use.

## ADDED Requirements

### Requirement: Salon profile
The system SHALL keep for each salon: name (1–120 chars), establishment type, business phone, address (street, number, district, city, state, postal code), time zone (IANA, Brazilian zones) and public slug; OWNERs SHALL edit it and MANAGERs and PROFESSIONALs SHALL only read it.

#### Scenario: Owner updates the profile
- **WHEN** an OWNER changes the business phone
- **THEN** the new phone is saved and returned on the next read

#### Scenario: Manager cannot edit
- **WHEN** a MANAGER tries to change the salon name
- **THEN** the system responds 403

#### Scenario: Other salon's profile
- **WHEN** an OWNER of salon A requests the profile of salon B
- **THEN** the system responds not-found

#### Scenario: Unsupported time zone
- **WHEN** an OWNER sets the time zone to "Europe/Lisbon"
- **THEN** the system responds with a validation problem on the time zone

### Requirement: Business contact visibility
Salon business phone and address are business data of the salon: members of the salon SHALL see them in clear, and PLATFORM_SUPPORT SHALL see the business phone masked outside the approval queue unless a break-glass session is active.

#### Scenario: Support reads an active salon's profile
- **WHEN** support reads the profile of an ACTIVE salon without break-glass
- **THEN** the business phone is masked and the city is shown in clear

### Requirement: Weekly opening hours with split shifts
The system SHALL store opening hours as zero or more intervals per weekday, each starting before it ends, aligned to the 15-minute grid, without overlapping other intervals of the same day; a weekday without intervals is a closed day.

#### Scenario: Split shift
- **WHEN** an OWNER sets Saturday to 08:00–12:00 and 14:00–18:00
- **THEN** both intervals are saved for Saturday

#### Scenario: Overlapping intervals
- **WHEN** an OWNER sets Monday to 09:00–13:00 and 12:00–18:00
- **THEN** the system responds with a validation problem and keeps the previous Monday hours

#### Scenario: Off the grid
- **WHEN** an OWNER sets an interval starting at 09:10
- **THEN** the system responds with a validation problem naming the 15-minute rule

#### Scenario: Closed day
- **WHEN** an OWNER removes all Sunday intervals
- **THEN** Sunday is reported as closed

### Requirement: Unique public slug
Each salon SHALL have a unique public slug of 3–60 characters (lowercase letters, digits and single hyphens, not starting or ending with a hyphen), not in the reserved list (for example "api", "app", "admin", "suporte", "planos", "entrar"); the wizard SHALL suggest one from the salon name.

#### Scenario: Suggested from the name
- **WHEN** the owner types the name "Salão Bela Vista" in the wizard
- **THEN** the suggested slug is "salao-bela-vista" (accents removed)

#### Scenario: Slug in use
- **WHEN** an OWNER changes the slug to one used by another salon
- **THEN** the system responds with a conflict problem and keeps the current slug

#### Scenario: Reserved slug
- **WHEN** an OWNER chooses the slug "admin"
- **THEN** the system responds with a validation problem
