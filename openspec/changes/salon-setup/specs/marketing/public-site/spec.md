# Spec Delta

## Purpose

Presents Estilix to salons and barbershops without requiring a login, shows the plans honestly and leads interested owners to sign up or sign in.

## ADDED Requirements

### Requirement: Public landing page
The system SHALL serve a landing page in pt-BR, without login, that explains what Estilix does for salons and barbershops and offers "Ver planos" and "Entrar" as entry points.

#### Scenario: Visitor without account
- **WHEN** a visitor opens the site root without being signed in
- **THEN** the landing page is shown and no sign-in redirect happens

#### Scenario: Signed-in user visits the root
- **WHEN** a signed-in user opens the site root
- **THEN** the landing page is shown with an "Abrir o Estilix" entry point instead of "Entrar"

#### Scenario: Readable without JavaScript
- **WHEN** a search engine or a browser without JavaScript requests the landing page
- **THEN** the full text content of the page is present in the HTML response

### Requirement: Features are labelled honestly
The landing page SHALL mark every feature that is not available yet as "Em breve", and SHALL NOT present it as available.

#### Scenario: Feature not built yet
- **WHEN** the landing page lists the agenda before the scheduling change is released
- **THEN** the agenda item carries the "Em breve" label

### Requirement: Plans come from a catalog
The system SHALL show the plans page from a plan catalog (name, monthly price in BRL, included features, active flag) through a public endpoint, showing only active plans.

#### Scenario: Launch plan
- **WHEN** a visitor opens the plans page during the launch
- **THEN** the page shows the active plan with price "R$ 0,00/mês" and an "Assinar" action

#### Scenario: Inactive plan hidden
- **WHEN** a plan is marked inactive in the catalog
- **THEN** it does not appear on the plans page nor in the public endpoint

### Requirement: Subscribing starts the sign-up
Choosing "Assinar" SHALL lead a visitor to create an account or sign in and then to the onboarding wizard with the chosen plan, without any payment step while the plan price is zero.

#### Scenario: Visitor subscribes
- **WHEN** a visitor without account clicks "Assinar" on the launch plan
- **THEN** they are taken to account creation and, after it, to the onboarding wizard with that plan selected

#### Scenario: Signed-in user subscribes
- **WHEN** a signed-in user without a salon clicks "Assinar"
- **THEN** they go straight to the onboarding wizard

### Requirement: Public endpoints expose no tenant data
Public endpoints SHALL return only the plan catalog and slug availability (available or not), and SHALL NOT return any data of existing salons or people.

#### Scenario: Slug taken
- **WHEN** someone checks the availability of a slug used by another salon
- **THEN** the answer says only that it is not available, without the salon's name or id
