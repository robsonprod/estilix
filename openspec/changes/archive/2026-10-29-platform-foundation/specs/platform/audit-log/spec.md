# Spec Delta

## Purpose

Keeps a trustworthy, append-only history of security-relevant actions so that owners and the platform can answer "who did what, when, and why" without the history itself exposing personal data.

## ADDED Requirements

### Requirement: Security-relevant actions are audited
The system SHALL record an audit event for: first sign-in of a user, membership added, changed or removed, break-glass opened, ended or expired, and each unmasked read during break-glass.

#### Scenario: Membership change audited
- **WHEN** an OWNER changes a member's role
- **THEN** an audit event records the actor, tenant, action, target member id, old and new role, and time

#### Scenario: Unmasked read audited
- **WHEN** a support user reads personal data of tenant A during break-glass
- **THEN** an audit event records the session, the resource type and the resource id

### Requirement: Audit events carry no personal data
Audit events SHALL identify people and records only by opaque ids and SHALL NOT contain names, e-mails, phones or free-text personal data, except the break-glass reason written by the support user.

#### Scenario: Event content
- **WHEN** any audit event is stored
- **THEN** it contains no name, e-mail or phone of any person

### Requirement: Audit log is append-only
The system SHALL NOT allow audit events to be updated or deleted through the application, and the runtime database role SHALL lack permission to do so.

#### Scenario: Update attempt
- **WHEN** the application's database role tries to update or delete an audit event
- **THEN** the database rejects the statement

#### Scenario: Audit written with the action
- **WHEN** an audited action fails and is rolled back
- **THEN** no audit event claims it succeeded

### Requirement: Who can read the audit log
The system SHALL let OWNERs read the audit events of their own tenant and PLATFORM_SUPPORT read audit events of any tenant; MANAGERs and PROFESSIONALs SHALL NOT read them.

#### Scenario: Owner reads own tenant
- **WHEN** an OWNER of tenant A lists audit events, newest first
- **THEN** only events of tenant A are returned, paginated

#### Scenario: Manager denied
- **WHEN** a MANAGER lists audit events
- **THEN** the system responds 403

#### Scenario: Cross-tenant denied
- **WHEN** an OWNER of tenant A lists audit events of tenant B
- **THEN** the system responds not-found
