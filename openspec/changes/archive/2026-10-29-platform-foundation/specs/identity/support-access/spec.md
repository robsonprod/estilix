# Spec Delta

## Purpose

Lets the platform operator diagnose problems in any salon while seeing personal data only when strictly necessary, with every exception justified, time-boxed, audited and visible to the salon's owners.

## ADDED Requirements

### Requirement: Masked support read access
The system SHALL let PLATFORM_SUPPORT read any tenant's data with every field declared as personal data masked, and SHALL NOT let support change tenant business data.

#### Scenario: Support lists tenants
- **WHEN** a support user lists tenants
- **THEN** the system returns every tenant with name, status and creation date

#### Scenario: Support reads a member list
- **WHEN** a support user reads the members of tenant A
- **THEN** names and e-mails are masked and roles are shown in clear

#### Scenario: Support tries to write
- **WHEN** a support user tries to change a membership of tenant A
- **THEN** the system responds 403

#### Scenario: Tenant user is not support
- **WHEN** an OWNER calls a support endpoint
- **THEN** the system responds 403

### Requirement: Break-glass session
The system SHALL let a support user open a break-glass session on one tenant with a written reason of at least 20 characters and a duration of at most 60 minutes; during it, personal data of that tenant SHALL be shown unmasked to that support user only.

#### Scenario: Open with reason
- **WHEN** a support user opens a break-glass session on tenant A with a valid reason for 30 minutes
- **THEN** reads of tenant A by that support user return unmasked personal data until the session ends

#### Scenario: Missing or short reason
- **WHEN** a support user opens a break-glass session with an empty or 10-character reason
- **THEN** the system rejects it with a validation problem

#### Scenario: Scope limited to one tenant
- **WHEN** a support user with an active session on tenant A reads tenant B
- **THEN** personal data of tenant B stays masked

#### Scenario: Session expires
- **WHEN** the session duration has passed
- **THEN** the next read returns masked data again without any action by the support user

#### Scenario: Ended early
- **WHEN** the support user ends the session before its expiration
- **THEN** masked reads resume immediately

### Requirement: Break-glass is visible to owners
The system SHALL show every break-glass session of a tenant to that tenant's OWNERs, with who opened it, the reason, start, end and the number of unmasked reads.

#### Scenario: Owner sees the session
- **WHEN** an OWNER of tenant A opens the access history
- **THEN** the session opened on tenant A is listed with its reason and duration

#### Scenario: Other tenants' sessions hidden
- **WHEN** an OWNER of tenant B opens the access history
- **THEN** sessions on tenant A are not listed

#### Scenario: Active session indicator
- **WHEN** an OWNER of tenant A opens the app while a session on tenant A is active
- **THEN** the app shows a notice that support has temporary access, with the reason
