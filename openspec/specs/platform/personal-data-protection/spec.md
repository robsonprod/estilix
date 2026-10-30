# platform/personal-data-protection Specification

## Purpose
Makes personal data (LGPD) a first-class, declared concept so that masking for support and keeping it out of logs and error responses is automatic for every feature, not a per-feature effort.

## Requirements

### Requirement: Personal data is declared on the response model
The system SHALL mark every field that holds personal data (name, e-mail, phone, birth date, document number, free-text notes about a person) in one declared way, and masking SHALL apply to every marked field without per-endpoint code.

#### Scenario: New marked field is masked
- **WHEN** a response field is marked as personal data and returned to a support user without break-glass
- **THEN** the field is masked without any change to the endpoint

### Requirement: Masking format
Masked values SHALL keep enough shape for diagnosis and hide the identity: names keep the first letter of each word, e-mails keep the first letter and the domain, phones keep the last 2 digits, other values are fully replaced.

#### Scenario: Mask a name
- **WHEN** the name "Ana Paula Souza" is masked
- **THEN** it is shown as "A*** P**** S****"

#### Scenario: Mask an e-mail
- **WHEN** the e-mail "ana.souza@gmail.com" is masked
- **THEN** it is shown as "a***@gmail.com"

#### Scenario: Mask a phone
- **WHEN** the phone "+55 85 99876-5432" is masked
- **THEN** it is shown as "***********32"

#### Scenario: Null stays null
- **WHEN** a marked field is empty
- **THEN** it is returned empty, not as a mask

### Requirement: Tenant users see their own data unmasked
The system SHALL return personal data unmasked to members of the tenant that owns it, subject to the role rules of each feature.

#### Scenario: Owner reads members
- **WHEN** an OWNER reads the members of their own tenant
- **THEN** names and e-mails are shown in clear

### Requirement: No personal data in logs
The system SHALL NOT write personal data to application logs, access logs, traces or metrics; people and records are referenced only by opaque ids.

#### Scenario: Validation failure on personal data
- **WHEN** a request with an invalid e-mail and a name is rejected
- **THEN** the captured log output contains neither the e-mail nor the name

#### Scenario: Unexpected error
- **WHEN** an unexpected exception is raised while handling a request that carries personal data
- **THEN** the log has the error type, correlation id and stack trace but no request body values

#### Scenario: Object printed in a log
- **WHEN** a domain object holding personal data is converted to text
- **THEN** the text shows the marked fields as redacted

### Requirement: No personal data in error responses
Error responses SHALL follow RFC 9457 Problem Details with a stable type and a correlation id, and SHALL NOT echo submitted personal data.

#### Scenario: Validation error body
- **WHEN** a request with an invalid phone is rejected
- **THEN** the problem body names the invalid field and the rule, not the submitted value
