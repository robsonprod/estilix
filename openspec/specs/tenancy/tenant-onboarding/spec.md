# tenancy/tenant-onboarding Specification

## Purpose
Turns a new account into a configured salon that platform support verifies before it goes live, with every decision traceable and the owner always knowing what happens next.

## Requirements

### Requirement: Onboarding wizard creates a pending salon
The system SHALL let a signed-in user submit, in one request, the establishment type, salon data, opening hours, chosen services, chosen plan and terms acceptance; it SHALL create the salon as PENDING_APPROVAL with that user as OWNER, all in one transaction.

#### Scenario: Complete submission
- **WHEN** a user without a salon submits a valid wizard for "Barbearia do Zé"
- **THEN** a salon in PENDING_APPROVAL exists, the user is its OWNER, its opening hours and chosen services are saved, and a TENANT_SIGNED_UP audit event is recorded

#### Scenario: Invalid data saves nothing
- **WHEN** the submission has an invalid slug or overlapping hours
- **THEN** the system responds with a validation problem naming the fields and no salon, membership or service is created

#### Scenario: Terms not accepted
- **WHEN** the submission does not accept the current terms version
- **THEN** the system rejects it with a validation problem on the terms field

### Requirement: Wizard defaults depend on the establishment type
The system SHALL offer preset services for the chosen establishment type and default opening hours (Monday to Saturday, 09:00–19:00) and time zone (America/Sao_Paulo); the owner MAY change or uncheck any of them before submitting.

#### Scenario: Barbershop presets
- **WHEN** the owner chooses "Barbearia" in the wizard
- **THEN** the suggested services include "Corte masculino", "Barba" and "Corte + barba" with suggested durations and prices

#### Scenario: Salon presets
- **WHEN** the owner chooses "Salão de beleza"
- **THEN** the suggested services include "Corte feminino", "Escova" and "Coloração" priced "a partir de"

### Requirement: One salon awaiting approval per user
The system SHALL NOT let a user submit a new salon while they already own a salon in PENDING_APPROVAL or REJECTED.

#### Scenario: Second submission
- **WHEN** a user whose salon is pending submits the wizard again
- **THEN** the system responds with a conflict problem and creates nothing

### Requirement: Terms acceptance is recorded
The system SHALL store, for each salon, the accepted terms version, the accepting user id and the acceptance time.

#### Scenario: Acceptance stored
- **WHEN** a salon is created through the wizard
- **THEN** its terms version, accepting user and acceptance time are stored and appear in the support queue

### Requirement: Owner of a pending or rejected salon sees only its status
While the salon is PENDING_APPROVAL or REJECTED, the owner SHALL see a status screen (what was submitted, what happens next, the rejection reason if any) and SHALL NOT use the salon's features.

#### Scenario: Waiting screen
- **WHEN** the owner of a pending salon signs in
- **THEN** the app shows "Seu salão está em análise" with the submission summary and no salon menu

#### Scenario: Rejected with reason
- **WHEN** the owner of a rejected salon signs in
- **THEN** the app shows the rejection reason and a "Corrigir e reenviar" action

### Requirement: Approval queue for platform support
The system SHALL list to PLATFORM_SUPPORT the salons in PENDING_APPROVAL, oldest first, with salon data, plan, terms acceptance and the owner's name and e-mail unmasked; this contact data SHALL be unmasked for support only in this queue and only while the salon is pending or rejected.

#### Scenario: Queue content
- **WHEN** support opens the approval queue
- **THEN** each pending salon shows name, type, city, business phone, owner name and e-mail, plan and submission time

#### Scenario: Contact masked after approval
- **WHEN** support reads the members of a salon that is already ACTIVE
- **THEN** the owner's name and e-mail are masked as for any other salon

#### Scenario: Tenant users cannot see the queue
- **WHEN** an OWNER calls the approval queue
- **THEN** the system responds 403

### Requirement: Support approves or rejects
The system SHALL let PLATFORM_SUPPORT approve a pending salon (it becomes ACTIVE) or reject it with a reason of at least 10 characters (it becomes REJECTED); each decision SHALL be audited with the deciding support user.

#### Scenario: Approve
- **WHEN** support approves a pending salon
- **THEN** the salon becomes ACTIVE, a TENANT_APPROVED event is recorded, and the owner's next request opens the salon normally

#### Scenario: Reject with reason
- **WHEN** support rejects a pending salon with the reason "Telefone não atende, confirme o número"
- **THEN** the salon becomes REJECTED with that reason and a TENANT_REJECTED event is recorded

#### Scenario: Decision on a salon that is not pending
- **WHEN** support tries to approve a salon that is already ACTIVE
- **THEN** the system responds with a conflict problem and nothing changes

#### Scenario: Concurrent decisions
- **WHEN** two support users approve and reject the same pending salon at the same time
- **THEN** exactly one decision is applied and audited, and the other gets a conflict problem

### Requirement: Owner fixes and resubmits
The owner of a REJECTED salon SHALL be able to edit the submitted data and resubmit, which returns the salon to PENDING_APPROVAL and records a TENANT_RESUBMITTED event.

#### Scenario: Resubmission
- **WHEN** the owner corrects the phone and resubmits
- **THEN** the salon is PENDING_APPROVAL again and appears in the support queue

#### Scenario: Another user cannot resubmit
- **WHEN** a user who is not the salon's owner tries to resubmit it
- **THEN** the system responds not-found
