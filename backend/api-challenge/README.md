# Ads API — Grupo OLX Code Challenge (Backend)

REST service for **creating and listing marketplace ads**, with **automatic moderation** through an external moderation API before an ad is published.

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
| Test UI | `http://localhost:8080/` (manual testing helper, see below) |
| Health | `http://localhost:8080/actuator/health` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`) |
| PostgreSQL | `localhost:5433` (not 5432, to avoid clashing with a local Postgres) |
| Moderation mock | `http://localhost:8787` |

On startup, the 57 example ads from the mock's seed are imported as `pending` and moderated by the worker within seconds; restarts don't import them again. To start from an empty database: `docker compose down -v`.

### Try it

```bash
# create (the Idempotency-Key is required; reuse it only when retrying the same ad)
curl -i localhost:8080/ads -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"title":"Celular, chama no whatsapp","description":"Novo","price":900,"category":"eletronicos","images":["https://example.com/a.jpg"]}'

curl localhost:8080/ads/{id}                         # pending → rejected, with rejection_reason
curl 'localhost:8080/ads?category=moveis&limit=5'    # published ads; follow next_cursor for more
curl 'localhost:8080/ads?status=rejected'
```

### Test UI

A single-page helper at `http://localhost:8080/` to exercise the API without curl: create ads (with example payloads that get approved or rejected for each reason), retry with the same `Idempotency-Key`, watch an ad go from `pending` to its outcome (it polls `GET /ads/{id}`), and page through the listing with filters. An **Architecture** tab (`http://localhost:8080/#architecture`) shows the components, the create → moderate → read flow, the ad status lifecycle and the key mechanisms. It is a testing aid, not part of the API: one static file (`src/main/resources/static/index.html`) with React from a CDN, no build step, served by the app so no CORS setup is needed.

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

## API contract

The full, interactive contract is served by OpenAPI at `/swagger-ui.html` (JSON at `/v3/api-docs`); a test keeps it in line with the real responses. Summary:

- JSON in `snake_case`, dates in ISO-8601 UTC. Errors are RFC 9457 problem details (`application/problem+json`); validation errors add `errors: [{field, message}]`.
- Every response carries an `X-Request-Id` (the caller's, if sent and safe to log), also present in the logs.

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

Required header `Idempotency-Key` (1–255 visible ASCII characters, e.g. a UUID): generate one per ad and reuse it on retries, so a retry returns the ad created the first time instead of a duplicate (`DECISIONS.md` 10).

```bash
curl -i localhost:8080/ads -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d @ad.json
```

Responses:

- `201 Created`: ad stored with `"status": "pending"`; `Location` header points to `/ads/{id}`. Moderation runs in the background, usually within seconds: poll the `Location` URL until `status` is no longer `pending` (`DECISIONS.md` 1). On a replayed key: the original ad, with `Idempotent-Replayed: true`.
- `400 Bad Request`: validation errors (`application/problem+json`), or a missing or malformed `Idempotency-Key`.
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

`next_cursor` is `null` on the last page. Invalid parameters (`limit` outside 1–50, unknown `status` or `category`, a cursor not issued by the API) return `400` with `errors: [{field, message}]`.

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

Java 25 · Spring Boot 4.1 · Gradle 9 · PostgreSQL · Flyway · Spring JdbcClient · Resilience4j · ShedLock · Actuator/Micrometer · springdoc-openapi · JUnit 5 · Testcontainers · WireMock · ArchUnit · JaCoCo · Spotless

Rationale for each choice: [`DECISIONS.md` → Stack](./DECISIONS.md#9-stack).
