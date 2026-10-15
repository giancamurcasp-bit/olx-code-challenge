# Agent instructions

Instructions for AI coding agents working in this repository. Tool-agnostic: `CLAUDE.md` imports this file; other tools read it directly.

## Context

Ads API for a marketplace: create, list and detail ads, with asynchronous moderation by an external API (mock in `../api-mock`).

- [`README.md`](./README.md): how to run, API contract.
- [`DECISIONS.md`](./DECISIONS.md): **source of truth for design decisions.** Read it before changing behavior.
- [`../api-mock/server.js`](../api-mock/server.js): the mock's real behavior. When it disagrees with `API.md`, **the code wins** (e.g. it returns `rejection_reason`, not `reason`).

## Rules

### Decisions and scope
- Don't contradict `DECISIONS.md` silently. If a change requires it, stop and say so. If the decision changes, update `DECISIONS.md` in the same change.
- Don't add dependencies without a reason recorded in `DECISIONS.md`.
- Prefer the smallest solution that solves the problem. No speculative abstractions.
- **Out of scope unless asked:** authentication (`DECISIONS.md` 4.1), message brokers (1.1), features beyond the brief.
- **Never modify `../api-mock`.** It is provided by OLX and simulates an external service.
- **Precedence:** `DECISIONS.md` and this file win over memory/context from previous sessions, which may be stale.

### Workflow
- For changes that cross layers, propose a plan before writing code.
- **Verify before saying "done":** run `./gradlew test` and report failures with their output. Never disable, skip or weaken a test to make the build pass.
- Small, incremental commits. Don't commit or push unless asked.
- When the human rejects or corrects your output, say so explicitly so it can be logged in `AI_USAGE.md`.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(optional scope): <description>`.

  | Type | Use for |
  | --- | --- |
  | `feat` | A new feature (correlates with MINOR in Semantic Versioning) |
  | `fix` | A bug fix (correlates with PATCH in Semantic Versioning) |
  | `chore`, `refactor`, `docs`, `style`, `test`, `ci`, `perf` | Other changes: maintenance, restructuring without behavior change, documentation, formatting, tests, CI, performance |

  A breaking change is marked with `!` (e.g. `feat!:`) or a `BREAKING CHANGE:` footer (correlates with MAJOR).

### Architecture (`DECISIONS.md` 8)
- `domain/` is pure Java: no Spring, no SQL, no HTTP. State transitions and the retry policy live in `Ad`.
- Ports (`application/port/`) only at real boundaries: `AdRepository`, `ModerationGateway`. No per-use-case interfaces.
- Persistence with `JdbcClient` and plain SQL. No JPA.
- Never hold a database transaction open during the moderation HTTP call.

### API conventions
- JSON fields in `snake_case` (`next_cursor`, `rejection_reason`); dates as ISO-8601 UTC.
- Create returns `201` with a `Location` header; errors use `ProblemDetail` (`application/problem+json`).

### Database
- Never edit a Flyway migration that has been committed; add a new one.

### Code
- Java 21: records for DTOs and value objects. Lombok only where records don't fit.
- `BigDecimal` for money, `Instant` for time. Constructor injection only.
- Logging: SLF4J, with `ad_id` / `request_id` in the MDC. Never log full request bodies.

### Tests
- Test behavior, not implementation. Name tests after the behavior (`rejectsTitleShorterThan10Chars`).
- Domain: plain unit tests. Application: a fake `ModerationGateway`. Moderation adapter: WireMock. Persistence/API: Testcontainers PostgreSQL, **never H2**.
- Async assertions with Awaitility, never `Thread.sleep`.
- Tests must be deterministic: never depend on the mock's random errors or latency.

### Docs
- Keep docs short: tables and bullets over prose.
- API change → update the contract in `README.md`. Decision change → update `DECISIONS.md`.

## Commands

```bash
docker compose up --build    # app + PostgreSQL + moderation mock
./gradlew test               # requires Docker (Testcontainers)
./gradlew jacocoTestReport   # coverage report
```
