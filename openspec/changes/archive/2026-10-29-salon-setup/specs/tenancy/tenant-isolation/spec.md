# Spec Delta

## MODIFIED Requirements

### Requirement: Tenant lifecycle status
The system SHALL keep a status for each tenant: PENDING_APPROVAL, REJECTED, ACTIVE or SUSPENDED. Members SHALL access tenant data only while it is ACTIVE; for the other statuses they get a problem naming the status. Platform support can still read any tenant.

#### Scenario: Suspended tenant
- **WHEN** a member calls an endpoint of a suspended tenant
- **THEN** the system responds with a tenant-suspended problem and no data

#### Scenario: Pending tenant
- **WHEN** the owner calls a salon endpoint of a tenant in PENDING_APPROVAL
- **THEN** the system responds with a tenant-pending-approval problem and no data

#### Scenario: Rejected tenant
- **WHEN** the owner calls a salon endpoint of a REJECTED tenant
- **THEN** the system responds with a tenant-rejected problem and no data

#### Scenario: Support on suspended tenant
- **WHEN** platform support opens a suspended tenant
- **THEN** support can read it under the masking rules
