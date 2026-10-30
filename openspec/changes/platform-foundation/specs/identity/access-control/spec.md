# Spec Delta

## Purpose

Defines who can sign in, which salons each person belongs to and with which role, and what each role is allowed to do in the platform foundation.

## ADDED Requirements

### Requirement: Authenticated API
The system SHALL require a valid access token from the configured identity provider on every API endpoint except health checks and explicitly public endpoints.

#### Scenario: Missing token
- **WHEN** a request without a token calls a protected endpoint
- **THEN** the system responds 401 with a problem body and no data

#### Scenario: Expired or foreign token
- **WHEN** a request carries an expired token or a token signed by another issuer
- **THEN** the system responds 401

#### Scenario: Health check is public
- **WHEN** an unauthenticated request calls the health endpoint
- **THEN** the system responds with the health status only

### Requirement: Users are provisioned on first sign-in
The system SHALL link an identity-provider subject to one internal user on the first authenticated request, and SHALL NOT create a duplicate user on later requests.

#### Scenario: First request
- **WHEN** a person with no internal user calls the API with a valid token
- **THEN** an internal user is created and linked to that subject

#### Scenario: Concurrent first requests
- **WHEN** two first requests of the same subject arrive at the same time
- **THEN** exactly one internal user exists afterwards

### Requirement: Memberships and tenant roles
The system SHALL grant access to a tenant only through a membership with exactly one role: OWNER, MANAGER or PROFESSIONAL. A user MAY have memberships in several tenants.

#### Scenario: Current user overview
- **WHEN** a signed-in user asks for their own profile
- **THEN** the system returns their tenants with the role in each and no data of other users

#### Scenario: User without membership
- **WHEN** a signed-in user with no membership and no platform role asks for their profile
- **THEN** the system returns an empty tenant list and every tenant endpoint responds not-found

### Requirement: Only owners manage memberships
The system SHALL allow only OWNERs of a tenant to add, change or remove memberships of that tenant, and SHALL keep at least one OWNER in every tenant.

#### Scenario: Owner changes a role
- **WHEN** an OWNER changes a MANAGER to PROFESSIONAL
- **THEN** the change is saved and audited

#### Scenario: Manager tries to manage members
- **WHEN** a MANAGER tries to add a member
- **THEN** the system responds 403

#### Scenario: Last owner protected
- **WHEN** the only OWNER of a tenant tries to remove or demote themself
- **THEN** the system rejects the change with a last-owner problem

#### Scenario: Owner of another tenant
- **WHEN** an OWNER of tenant A tries to change a membership of tenant B
- **THEN** the system responds not-found

### Requirement: Role permissions are declared once
The system SHALL decide each tenant action from a single declared permission per role, and the web app SHALL show only the actions the user's role allows.

#### Scenario: Menu by role
- **WHEN** a PROFESSIONAL opens the app
- **THEN** the menu does not show member management or the audit log

#### Scenario: Hidden action still enforced
- **WHEN** a PROFESSIONAL calls a member-management endpoint directly
- **THEN** the system responds 403

### Requirement: Platform support role
The system SHALL support a PLATFORM_SUPPORT role assigned only by platform configuration, not by any tenant user, and not tied to a membership.

#### Scenario: Tenant owner cannot grant support
- **WHEN** an OWNER tries to give a member the PLATFORM_SUPPORT role
- **THEN** the system rejects the request

### Requirement: Accessible pt-BR sign-in
The web app SHALL let a staff member sign in and reach their salon's home in at most two steps (credentials, then MFA when enabled), with all text in pt-BR.

#### Scenario: Single-salon user
- **WHEN** a user with one membership signs in
- **THEN** they land directly on that salon's home without a salon picker

#### Scenario: Multi-salon user
- **WHEN** a user with memberships in two salons signs in
- **THEN** they choose the salon once and the app remembers the last choice on that device
