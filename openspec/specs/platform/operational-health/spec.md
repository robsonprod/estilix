# platform/operational-health Specification

## Purpose
Tells orchestrators and operators whether the API process is alive and whether it can serve traffic, without exposing internal details to anonymous callers.

## Requirements

### Requirement: Liveness reflects only the process
The system SHALL expose a liveness endpoint that reports UP while the process can handle requests, independent of external dependencies.

#### Scenario: Database down
- **WHEN** the database is unreachable
- **THEN** liveness still reports UP, so the orchestrator does not restart a healthy process

### Requirement: Readiness reflects required dependencies
The system SHALL expose a readiness endpoint that reports DOWN with HTTP 503 when a required dependency (the database) is unreachable, and UP with HTTP 200 again when it recovers, without a restart.

#### Scenario: Database paused
- **WHEN** the database stops answering
- **THEN** readiness reports DOWN with HTTP 503, so traffic stops being routed to this instance

#### Scenario: Database back
- **WHEN** the database answers again
- **THEN** readiness reports UP without restarting the API

### Requirement: Health details are not public
Anonymous callers SHALL receive only the overall status of health endpoints; component details SHALL be visible only to PLATFORM_SUPPORT.

#### Scenario: Anonymous call
- **WHEN** an unauthenticated client calls the health endpoint
- **THEN** the body contains the status only, with no host names, versions or dependency details
