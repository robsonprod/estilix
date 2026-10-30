# ADR 0002 — Identity in the token, salon roles in the database

**Status:** accepted (change `platform-foundation`)

## Context

A person can work in two salons with different roles (owner in one, professional in another). Owners change roles at any time and expect it to take effect immediately.

## Decision

The JWT (Cognito, or mock-oauth2-server locally) only proves **who** the caller is. Salon roles live in the `membership` table and are resolved per request from the salon in the URL (`/api/v1/tenants/{tenantId}/...`). The only role in the token is `PLATFORM_SUPPORT`, from the Cognito group `platform-support`, which no salon user can grant. What each role may do is declared once (`PermissionCatalog`), enforced with `@PreAuthorize` and sent to the web app through `/me`.

## Alternatives considered

| Option | Why not |
|---|---|
| Roles as token claims | A change waits for token expiry; flat claims cannot express "owner here, professional there". |
| One Cognito group per salon and role | Group explosion (salons × roles) and identity-provider coupling. |
| "Current salon" stored in a server session | Stateful, harder to scale and to test; the URL is explicit and bookmarkable. |

## Consequences

- Non-members get **404**, not 403, so salon ids cannot be probed.
- One extra indexed lookup per request (membership by primary key).
- Tokens are accepted when the app is named in `aud`, `azp` or `client_id`, which covers ID tokens, mock-oauth2-server and Cognito access tokens.
