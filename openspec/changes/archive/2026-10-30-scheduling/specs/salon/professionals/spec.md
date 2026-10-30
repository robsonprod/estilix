# Spec Delta

## MODIFIED Requirements

### Requirement: Professional names are personal data
Professional display names SHALL follow the personal-data rules: in clear for members of the salon, masked for PLATFORM_SUPPORT unless a break-glass session on that salon is active. Display names of active professionals SHALL also be shown to customers on the salon's public booking page, because the salon chooses that name to be public; only the display name is public, never the linked member's data.

#### Scenario: Support lists professionals
- **WHEN** support lists the professionals of a salon without break-glass
- **THEN** the names are masked and the colors, active flags and service counts are in clear

#### Scenario: Customer sees the display name
- **WHEN** a customer opens the salon's public booking page
- **THEN** the active professionals appear by display name, with no e-mail, phone or account information of the linked member
