# ADR 0003 — Personal data declared as types

**Status:** accepted (change `platform-foundation`)

## Context

LGPD: the salon is the data controller, Estilix is the processor. The platform operator must diagnose problems without casually seeing customers' data, and personal data must never reach logs. Rules that depend on every developer remembering an annotation fail silently.

## Decision

Names, e-mails, phones and free text about people are typed as `PersonName`, `Email`, `Phone` and `PersonalText`:

- `toString()` always returns `***`, so data-class printing, string templates and logs never show the value.
- One Jackson serializer decides per request: salon members get the value; platform support gets the masked form (`A*** P**** S****`, `a***@gmail.com`, `***********32`); support with an active break-glass session gets the value and the request is audited (`UNMASKED_READ`) and counted on the session, which the owner sees.
- Error responses never echo submitted values, and request bodies are never logged. `LogHygieneTest` submits known values and searches every log line and response for them.

## Alternatives considered

| Option | Why not |
|---|---|
| `@PersonalData` annotation on `String` fields | Easy to forget; `toString()` still leaks. |
| Kotlin value classes | On the JVM the property becomes a plain `String`, so Jackson skipped the masking serializer. Small final classes keep the same API safely. |
| Masking in SQL views for support | Duplicates every query and mixes presentation into the database. |

## Consequences

- Adding a personal field to any response is masked by default for support with no extra code.
- Break-glass is time-boxed (≤ 60 min, database clock), scoped to one salon and one support user, requires a written reason and is visible to the owner.
