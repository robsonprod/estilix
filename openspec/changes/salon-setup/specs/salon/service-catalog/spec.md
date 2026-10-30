# Spec Delta

## Purpose

Holds what the salon sells — each service with its duration and price — so that bookings and service records are quick to create and consistent.

## ADDED Requirements

### Requirement: Service definition
The system SHALL keep services per salon with name (1–80 chars, unique per salon ignoring case and accents), category, duration in minutes (multiple of 15, from 15 to 480), price in BRL with two decimals, price type FIXED or FROM ("a partir de"), active flag and display order.

#### Scenario: Create a service
- **WHEN** an OWNER creates "Corte masculino", 30 minutes, R$ 45,00, FIXED
- **THEN** the service is saved as active and listed in its category

#### Scenario: Duration off the grid
- **WHEN** an OWNER creates a service with 40 minutes
- **THEN** the system responds with a validation problem on the duration

#### Scenario: Duplicate name
- **WHEN** an OWNER creates "corte masculino" while "Corte Masculino" exists in the same salon
- **THEN** the system responds with a conflict problem

#### Scenario: Same name in another salon
- **WHEN** salon B creates "Corte masculino" while salon A already has it
- **THEN** the service is created for salon B

#### Scenario: Price with more than two decimals
- **WHEN** an OWNER sends the price 45.999
- **THEN** the system responds with a validation problem; prices are never rounded silently

### Requirement: Price display
The system SHALL present FIXED prices as "R$ 45,00" and FROM prices as "a partir de R$ 150,00", and SHALL accept zero as a price.

#### Scenario: From price shown
- **WHEN** a member lists services that include "Coloração" priced FROM R$ 150,00
- **THEN** the list shows "a partir de R$ 150,00"

### Requirement: Who manages services
OWNERs and MANAGERs SHALL create, edit, reorder, activate and deactivate services; PROFESSIONALs SHALL list them; no member of another salon SHALL see or change them.

#### Scenario: Professional cannot edit
- **WHEN** a PROFESSIONAL tries to change a service price
- **THEN** the system responds 403

#### Scenario: Cross-salon access
- **WHEN** a MANAGER of salon A requests a service of salon B by id
- **THEN** the system responds not-found

### Requirement: Services are deactivated, not deleted
The system SHALL deactivate services instead of deleting them, so past records keep their reference; inactive services SHALL be hidden from new selections and remain listed under "Inativos".

#### Scenario: Deactivate
- **WHEN** a MANAGER deactivates "Pezinho"
- **THEN** it disappears from the active list and appears under "Inativos"

#### Scenario: Reactivate
- **WHEN** a MANAGER reactivates "Pezinho"
- **THEN** it returns to the active list

### Requirement: Presets by establishment type
The system SHALL provide a preset list of suggested services with category, duration, price and price type for each establishment type, used by the onboarding wizard and by an "Adicionar sugestões" action that adds only presets whose name the salon does not have yet.

#### Scenario: Add missing suggestions
- **WHEN** a barbershop that already has "Barba" uses "Adicionar sugestões"
- **THEN** the missing barbershop presets are added and "Barba" is not duplicated
