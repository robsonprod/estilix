# Spec Delta

## Purpose

Guarantees that each salon (tenant) sees and changes only its own data, even though all salons share one database, and that the guarantee holds at the database level when application code is wrong.

## ADDED Requirements

### Requirement: Every tenant-owned record belongs to exactly one tenant
The system SHALL store the owning tenant on every tenant-owned record, and the tenant of a record SHALL NOT change after creation.

#### Scenario: Record created in the current tenant
- **WHEN** a user acting in tenant A creates a tenant-owned record
- **THEN** the record is stored as owned by tenant A regardless of any tenant id sent in the request body

#### Scenario: Tenant of a record cannot be changed
- **WHEN** an update tries to move a record of tenant A to tenant B
- **THEN** the update is rejected and the record stays in tenant A

### Requirement: Database-enforced isolation
The system SHALL enforce tenant isolation in the database itself, so that a query without a tenant filter returns and changes only rows of the current tenant.

#### Scenario: Query without tenant filter
- **WHEN** code acting in tenant A runs a query on a tenant-owned table without any tenant condition
- **THEN** only rows of tenant A are returned

#### Scenario: Cross-tenant write denied
- **WHEN** code acting in tenant A tries to update or delete a row of tenant B by its id
- **THEN** no row is changed

#### Scenario: Insert into another tenant denied
- **WHEN** code acting in tenant A inserts a row explicitly owned by tenant B
- **THEN** the database rejects the insert

#### Scenario: No tenant context
- **WHEN** code runs a query on a tenant-owned table without any current tenant set
- **THEN** no rows are returned and no rows can be written

### Requirement: Tenant context per request
The system SHALL resolve the current tenant for each request from the request path and the caller's membership, and SHALL apply it only for the duration of that request's database work.

#### Scenario: Member accesses own tenant
- **WHEN** a member of tenant A calls an endpoint under tenant A
- **THEN** the request runs with tenant A as current tenant

#### Scenario: Non-member accesses another tenant
- **WHEN** a member of tenant A only calls an endpoint under tenant B
- **THEN** the system responds with not-found, without revealing whether tenant B exists

#### Scenario: Context does not leak between requests
- **WHEN** two requests for tenants A and B are served one after another on the same pooled connection
- **THEN** the second request never sees rows of the first request's tenant

### Requirement: Tenant lifecycle status
The system SHALL keep a status for each tenant (ACTIVE or SUSPENDED); members of a suspended tenant SHALL NOT access its data, while platform support still can.

#### Scenario: Suspended tenant
- **WHEN** a member calls an endpoint of a suspended tenant
- **THEN** the system responds with a tenant-suspended problem and no data

#### Scenario: Support on suspended tenant
- **WHEN** platform support opens a suspended tenant
- **THEN** support can read it under the masking rules
