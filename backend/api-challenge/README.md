# Ads API — Grupo OLX Code Challenge (Backend)

REST service for **creating and listing marketplace ads**, with **automatic moderation** through an external moderation API before an ad is published.

> 🚧 **Work in progress.** This README describes the target design; sections marked _(planned)_ are not implemented yet.

- Technical decisions, trade-offs and interpretations: [`DECISIONS.md`](./DECISIONS.md)
- How AI was used: [`AI_USAGE.md`](./AI_USAGE.md)
- Moderation API (mock) contract: [`../api-mock/docs-candidato/API.md`](../api-mock/docs-candidato/API.md)

---

## How it works

```
 Client ──POST /ads──▶ API ──insert (status=pending)──▶ PostgreSQL
                         ◀── 201 Created (pending) ──┘        ▲
                                                              │ claim pending ads
                                                              │ (FOR UPDATE SKIP LOCKED)
                                                     Moderation worker
                                                              │ rate limited (~4 req/s)
                                                              │ circuit breaker, timeout
                                                              ▼
                                                     Moderation API (mock)
                                                              │
                              published / rejected (+reason) or reschedule with backoff
```

1. `POST /ads` validates the request and stores the ad as `pending`. The client gets a response immediately, without waiting on the moderation API.
2. A background worker picks up pending ads and calls the moderation API.
3. The ad becomes `published` (approved) or `rejected` (with `rejection_reason`). Transient failures (`500`, `429`, timeouts) are retried later with backoff; non-retryable failures (e.g. `400`) or exhausted retries move the ad to `moderation_failed`. An ad is **never published without moderation**.

Why async: the moderation API has 100–2000 ms latency, ~20% error rate and a 5 req/s limit. See [`DECISIONS.md`](./DECISIONS.md#1-moderation-is-asynchronous-using-the-database-as-the-queue).

### Ad statuses

| Status | Meaning |
| --- | --- |
| `pending` | Waiting for (or retrying) moderation |
| `published` | Approved, visible in the public listing |
| `rejected` | Rejected by moderation; see `rejection_reason` |
| `moderation_failed` | Moderation hit a non-retryable error or exhausted its retries; see `last_moderation_error` |

---

## Running

**Requirements:** Docker and Docker Compose.

```bash
docker compose up --build
```

This starts:

| Service | URL |
| --- | --- |
| Ads API | `http://localhost:8080` |
| Health | `http://localhost:8080/actuator/health` |
| Swagger UI _(planned)_ | `http://localhost:8080/swagger-ui.html` |
| PostgreSQL | `localhost:5433` (not 5432, to avoid clashing with a local Postgres) |
| Moderation mock | `http://localhost:8787` |

_(planned)_ On startup, the 57 example ads from the mock's seed are imported as `pending` and moderated by the worker.

### Running the app outside Docker

Requires any JDK 17+ to launch Gradle; the Gradle daemon and the build use Java 25, downloaded automatically if missing.

```bash
docker compose up -d postgres moderation-api
./gradlew bootRun
```

### Tests and coverage

```bash
./gradlew test       # requires Docker (Testcontainers); also generates the coverage report
./gradlew build      # formatting check (Spotless) + tests
```

Coverage report: `build/reports/jacoco/test/html/index.html`.

> **Colima / non-default Docker socket:** Testcontainers looks for `/var/run/docker.sock`. With Colima, export
> `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` before running the tests.

---

## API contract _(planned)_

The full, interactive contract is served by OpenAPI at `/swagger-ui.html` (JSON at `/v3/api-docs`). Summary:

### `POST /ads` — create an ad

```json
{
  "title": "iPhone 13 128GB azul",
  "description": "Em ótimo estado, com caixa e nota fiscal.",
  "price": 3500.00,
  "category": "eletronicos",
  "images": ["https://example.com/iphone.jpg"]
}
```

| Field | Rules |
| --- | --- |
| `title` | 10–100 characters (after trimming) |
| `description` | Required, at most 5000 characters |
| `price` | `> 0`, up to 2 decimal places |
| `category` | `eletronicos`, `moveis`, `veiculos`, `imoveis`, `esportes`, `colecionaveis` |
| `images` | 1–10 absolute `http(s)` URLs, at most 2048 characters each |

Text fields can't contain control characters (line breaks and tabs are allowed).

Optional header `Idempotency-Key` (1–255 visible ASCII characters, e.g. a UUID): retrying with the same key returns the ad created the first time instead of a duplicate (`DECISIONS.md` 10).

Responses:

- `201 Created`: ad stored with `"status": "pending"`; `Location` header points to `/ads/{id}`. On a replayed key: the original ad, with `Idempotent-Replayed: true`.
- `400 Bad Request`: validation errors (`application/problem+json`), or a malformed `Idempotency-Key`.
- `422 Unprocessable Content`: the `Idempotency-Key` was already used for a different ad.

### `GET /ads` — list ads

| Query param | Default | Description |
| --- | --- | --- |
| `category` | (all) | Filter by category |
| `status` | `published` | Filter by status |
| `limit` | `20` | Page size (max `50`) |
| `cursor` | (first page) | Value of `next_cursor` from the previous page |

Ordered by most recent first. Response:

```json
{
  "data": [ { "id": 58, "title": "...", "status": "published", "...": "..." } ],
  "next_cursor": "eyJjcmVhdGVkQXQiOi4uLn0"
}
```

`next_cursor` is `null` on the last page.

### `GET /ads/{id}` — ad details

Returns the ad in any status, including moderation info:

```json
{
  "id": 58,
  "title": "Celular com whatsapp",
  "description": "...",
  "price": 1200.00,
  "category": "eletronicos",
  "images": ["https://example.com/a.jpg"],
  "status": "rejected",
  "rejection_reason": "contact_info_not_allowed",
  "moderation_attempts": 1,
  "last_moderation_error": null,
  "created_at": "2026-10-14T12:00:00Z",
  "updated_at": "2026-10-14T12:00:02Z"
}
```

- `404 Not Found` if the ad doesn't exist.

---

## Tech stack

Java 25 · Spring Boot 4.1 · Gradle 9 · PostgreSQL · Flyway · Spring JdbcClient · Resilience4j · Actuator/Micrometer · springdoc-openapi · JUnit 5 · Testcontainers · WireMock · ArchUnit · JaCoCo · Spotless

Rationale for each choice: [`DECISIONS.md` → Stack](./DECISIONS.md#9-stack).
