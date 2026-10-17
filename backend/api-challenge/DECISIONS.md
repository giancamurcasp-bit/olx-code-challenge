# Decisions

Technical decisions, discarded alternatives and how ambiguous requirements were interpreted.

---

## 1. Moderation is asynchronous, using the database as the queue

`POST /ads` validates, saves the ad as `pending` and returns `201`. A background worker claims pending ads (`FOR UPDATE SKIP LOCKED`) and calls the moderation API.

**Why async:** the provider has 100–2000 ms latency, ~20% `500`s and a 5 req/s limit. Calling it inside the request would make creation slow, fail ~1 in 5 times and cap the API at 5 creates/s. Async keeps creation at one insert, and keeps it working while moderation is slow, down or throttling. **Trade-off:** the result isn't in the response; the ad shows as `pending` ("under review", as usual in marketplaces) until the worker processes it.

**No transaction is held during the HTTP call** (up to 2s): the worker leases the ad in a short transaction (pushing `next_attempt_at` forward, so other workers skip it), calls the provider outside any transaction, and saves the result in a second short one. If the worker crashes mid-call, the lease expires and the ad is retried.

**The lease is a fencing token:** a slow batch can outlive its lease and another worker re-claim the ad, so the save only applies `WHERE status = 'pending' AND next_attempt_at = <the lease this worker claimed>`, and a stale write is discarded. Moderation is therefore **at-least-once**, which is harmless: a check has no side effects.

**The client polls** the `Location` returned by `POST /ads` until `status` leaves `pending`, usually within seconds: no new endpoint or connection state, at the cost of some wasted requests.
- **Next:** notify the seller (push or email) on each status change by publishing an event, one of the broker triggers in 1.1.
- **Discarded for now:** webhooks (registered clients, signing and their own retries, with no clients or auth, 4.1) and SSE/WebSocket (long-lived connections across instances for one status change).

**Why the database as the queue:** nothing is lost on restart, and "ad created" and "work enqueued" are atomic because they are the same row.

**Discarded:** a synchronous call (exposes users to the provider), sync with an async fallback (two code paths for a small gain), an in-memory queue or `@Async` (loses work on restart), SQS / Kafka (1.1).

### 1.1 Why not SQS or Kafka (yet)

A broker adds infrastructure without removing any hard part of this problem:
- **Dual write:** saving the ad and publishing can't share a transaction. The fix, a transactional outbox, is the table already used here.
- **Same rate limit:** the provider's 5 req/s caps throughput, not the queue, and the consumer still needs the rate limiter.
- **Retries fit worse:** backoff and `Retry-After` become visibility timeouts (SQS) or retry topics (Kafka), and every receive counts toward `maxReceiveCount`, even when no real call was made.
- **Extra cost:** an idempotent consumer, plus a broker emulator in Docker Compose and in tests.

| Trigger to adopt one | Fit |
| --- | --- |
| Other consumers react to ads (search, notifications, fraud) | Kafka (or SNS → SQS) |
| Moderation becomes a separate service/team | SQS (job) or Kafka (event) |
| Replay is needed (reprocess after a policy change) | Kafka |
| Per-ad ordering (edits re-trigger moderation) | Kafka keyed by `ad_id`, or SQS FIFO |
| Queue polling hurts the primary database | SQS |
| Several workers must consume at once to scale horizontally (e.g. the provider's quota grows) | SQS, plus a shared rate limiter (3) |

**SQS is the better fit once one worker is no longer enough:** several workers scaling horizontally, or more consumers of the "ad created" event. A version with SQS was built and set aside: here the provider's quota caps throughput, and one worker is sized for all of it (3). SQS fits moderation as a **job** (per-message retry, DLQ, redrive); Kafka fits ads as **events** for several teams (retention, replay).

**Migration path:** keep the outbox, add a relay to the broker, and turn the worker into an idempotent consumer with the same client, rate limiter and status transitions; size `maxReceiveCount` like the cap in 2.1, stop receiving while the circuit is open, alarm on DLQ depth and redrive. The API and status model don't change.

---

## 2. Status model and failure handling

| Status | Meaning |
| --- | --- |
| `pending` | Waiting for or retrying moderation |
| `published` | Approved, visible in the public listing |
| `rejected` | Rejected by moderation, `rejection_reason` stored |
| `moderation_failed` | Non-retryable error or retries exhausted, `last_moderation_error` stored |

`published` rather than `approved`, because the brief says "moderate before publishing". **Fail closed:** nothing is published without a successful moderation response.

| Moderation response | Outcome |
| --- | --- |
| `200 approved` / `rejected` | `published` / `rejected` |
| `500`, timeout, connection error | Retry with exponential backoff + jitter (cap ~5 min). Counts as an attempt |
| `429` | Retry after `Retry-After`. Does **not** count as an attempt ("not now", not "failed") |
| `400`, unparseable response | `moderation_failed` immediately: retrying can't fix it |
| Max attempts reached (default 15) | `moderation_failed` with `retries_exhausted` |

### 2.1 Why the retry cap

Without a cap, stuck ads eat the rate-limit budget and starve new ones, and a payload that always triggers a `500` retries forever. The usual objection, that an outage exhausts every ad's attempts, doesn't apply: **while the circuit is open the worker claims nothing**, and `429`s don't count, so attempts are only spent against a reachable provider.

**Why 15:** the 14 gaps between attempts add up to ~34 min of backoff (nine growing gaps from 1s, then five at the 5 min cap). Random failures almost never exhaust them (15 in a row: ~1 in 10¹⁰ at the normal 20% error rate, ~1 in a million at 40%), so what the cap really sets is how long a seller waits for an ad that can never be moderated. The first cap, 50, added no protection and left such an ad `pending` for ~3.5 hours.
- **A minimum, not a maximum:** call latency, the wait to be claimed, `Retry-After` and open-circuit periods add to it; the last one is unbounded on purpose.
- **Attempts vs. calls:** `moderation_attempts` counts provider answers, at most 15. `429`s and calls whose lease expired (1) are extra calls; calls deferred by our own limiter or circuit are never sent.

**Discarded:** retrying forever (starvation, poison messages), and a low cap without circuit-breaker awareness (a short blip fails valid ads).

**Next steps:** an endpoint to requeue `moderation_failed` ads (equivalent to a DLQ redrive), and alerting on the failure rate.

---

## 3. Moderation client resilience

- **Timeout ~3s:** the mock's maximum latency is 2s.
- **Client-side rate limiter (4 req/s, Resilience4j):** stays under the provider's 5 req/s instead of discovering it through `429`s; the mock counts rejected requests in its window, so hammering it keeps us throttled. Configured as **1 permit every 250ms**, not 4 per second: Resilience4j refills in fixed periods while the provider counts a sliding 1s window, so "4 per 1s" let 8 calls through around a boundary (measured: 23 `429`s for 40 ads before, 0 after).
- **One worker deployment, separate from the API:** the rate limiter lives in each instance's memory, so N workers would send N × 4 req/s. API and worker are two deployments of the same image; `moderation.worker.enabled` is on only in the worker, so scaling the API never adds workers.
  - **Why one is enough:** the provider's quota caps throughput, not the worker. One worker keeps enough calls in flight for the whole 4 req/s (below); a second would only split the same quota.
  - **Enforced by deployment, not by a lock:** a second worker by mistake (or during a rolling deploy) is still correct, thanks to `SKIP LOCKED` and the lease fencing token (1); it only costs `429`s. A `Recreate` deploy strategy avoids the overlap.
  - **Failover:** the orchestrator restarts a dead worker (a one-replica Deployment or ECS service). Pending ads wait in the table and claimed ones become due when their lease expires: moderation is delayed, nothing is lost, and the API keeps accepting ads.
  - **Discarded, a lock (ShedLock):** the first version ran the worker in every instance behind a lock row. It avoids the restart delay, but adds a dependency, a table and a lock timeout to coordinate instances that a single deployment doesn't have. It is the step to take if restart time becomes a problem.
  - **If several workers must run at once:** SQS (1.1) plus a shared rate limiter (Bucket4j on Postgres, or Redis), or each of N replicas at 4/N req/s.
- **8 calls in flight (virtual threads), sized with Little's law** (calls in flight = rate × latency): 4 req/s × the mock's 2s maximum latency = 8. The ~1s average would give 4, with no margin: runs of slow responses dropped below 2 req/s. More than 8 is no faster, since the limiter caps at 4 req/s and extra calls only wait for permits. For another provider: concurrency ≈ rate × maximum latency.
  - **Measured:** 4 → 2.9 calls/s, 8 → 3.1 calls/s. The rest of the gap to 4 is the batch structure: each run waits for its slowest call, then pauses 1s (11).
- **Circuit breaker (Resilience4j):** stops claiming ads while the provider is unhealthy. The ~50% threshold sits above the normal 20% error rate.
- **No Resilience4j `Retry`:** retries are rescheduled in the database (`next_attempt_at`), so they survive restarts and don't block the worker.

---

## 4. Listing and pagination

- `GET /ads` shows only `published` by default (public feed). An optional `status` filter covers the author, the demo and debugging, since there is no auth.
- `GET /ads/{id}` returns any status, including `rejection_reason`. This is where "the reason must be visible" is met.
- **Cursor (keyset) pagination** on `(created_at DESC, id DESC)`, using an opaque cursor, `limit` 20 (max 50) and `{ data, next_cursor }`:
  - stable when ads are inserted during paging, unlike `OFFSET`;
  - constant cost per page (index seek);
  - `id` breaks ties: the seed has 57 ads but only 28 distinct `created_at` values.
- **Trade-off:** no "jump to page N" and no total count, which is fine for a feed. Indexes: `(status, created_at DESC, id DESC)` and `(status, category, created_at DESC, id DESC)`: a single index with `category` second can't serve the unfiltered listing efficiently.

### 4.1 Authentication and ad ownership: out of scope

There are no users in this version: ads have no author, and any client can see `pending`, `rejected` and `moderation_failed` ads through the `status` filter and `GET /ads/{id}`. This is a **known gap, not a design choice**, and it should be implemented in a real version:
- **Author from credentials:** `author_id` taken from the authenticated user's token (e.g. a JWT validated against the identity provider), never from the request body. A client-supplied author would let anyone impersonate anyone.
- **Ownership rules:** non-published ads, including rejection reasons, visible only to their author; the `status` filter replaced by a "my ads" endpoint.

**Why not now:** the brief doesn't mention users. Doing it properly (token validation, ownership checks and their tests) doesn't fit the time budget, and a partial version, such as an unauthenticated `author_id` field, would be misleading.

---

## 5. Validation: ours vs. moderation's

Ours (`400`, `ProblemDetail`):

| Field | Rule |
| --- | --- |
| `title` | 10–100 characters after trimming (code points, not bytes) |
| `description` | Not blank, at most 5000 characters |
| `price` | `> 0`, 2 decimal places |
| `category` | One of the 6 moderation categories |
| `images` | 1–10 absolute `http(s)` URLs, at most 2048 characters each |
| Text fields | No control characters, except line breaks and tabs in text |

- **Size limits and control characters:** without them, a 5 MB description was stored and a NUL character returned `500` (PostgreSQL can't store it). The whole body is still read before validation, so a body-size limit belongs at the edge (load balancer or gateway).
- **Where each rule lives:** the request DTO (Bean Validation) checks presence and simple bounds; the precise rules live in the domain, applied to every way an ad is created (API, seed), with the same `400` and `errors: [{field, message}]`. `@Size` would also miscount titles: it counts UTF-16 units, so an emoji counts as 2. Trade-off: the domain stops at the first broken rule, so a request with several mistakes may not report all of them.
- **Left to moderation:** contact info, prohibited items and price range per category. That policy belongs to another service; duplicating it creates two sources of truth. **Conscious duplication:** the category list, since an invalid category could never be moderated (the mock returns `400`).
- **Category as an enum, not a table:** the valid list belongs to the provider, so a table would be a second source of truth that suggests a category can be added with an `INSERT`. Adding one means changing the enum and a migration. A table becomes right when categories gain metadata or a hierarchy, or must change without a deploy.

---

## 6. Data model

- **Money:** `BigDecimal` / `NUMERIC(12,2)`, never floating point. **Time:** `Instant` / `TIMESTAMPTZ`, ISO-8601 UTC.
- **Images:** a URL list on `ads` (`TEXT[]`). They are always read with the ad and never queried alone. An `ad_images` table becomes worth it once images gain metadata.
- **Moderation state as columns on `ads`:** `moderation_attempts`, `next_attempt_at` (null once terminal), `last_moderation_error`, `rejection_reason`. The worker's claim query needs them in the same transaction. A partial index `(next_attempt_at) WHERE status = 'pending'` keeps it cheap. The per-attempt history goes to structured logs keyed by `ad_id`.
- **Queue age:** `moderation_queued_at`, set by the database on insert and never updated, is when the ad entered the moderation queue, and the queue-age metric (11) reads it. `updated_at` moves on every attempt, so it restarts the clock on each retry (measured: 15s reported for ads waiting 60s), and `created_at` is the original date for seeded ads.
- **Logging:** structured, with `ad_id` (worker) and `request_id` (HTTP) in the MDC, so one filter finds an ad's history or a request's logs.
  - **Moderation:** each attempt's HTTP status and latency, then the outcome: `INFO` for results, `WARN` for transient failures, `ERROR` for `moderation_failed`.
  - **Creation:** `INFO` (created, replayed), `WARN` (key reused). Validation `400`s aren't logged: they are client errors.
  - **Operations:** circuit-breaker transitions (`WARN`), a summary per worker run (`INFO`), calls not sent for lack of a permit (`WARN`), unexpected errors (`ERROR` with the stack trace; the client gets a generic `500` problem).
  - **`request_id`** reuses the caller's `X-Request-Id` when it is safe to log (letters, digits, `._-`, up to 64 characters), otherwise a UUID, and is returned in the response.
  - **No logging port:** SLF4J already is one (Logback is the swappable adapter), and logs go to stdout for the platform. A record that must be kept would be a table behind a port, not a log.
  - **Discarded:** blob storage (for large files, not small queryable state), and a `moderation_attempts` history table: no consumer in this scope; it becomes right once decisions must be auditable (seller disputes), since logs are not a system of record.

---

## 7. Seed data

- The 57 seed ads are imported as `pending` and go through real moderation. Assuming approval would bypass the rule, and this way the pipeline is exercised.
- The seed has **no images**. Each ad gets a deterministic placeholder URL instead of weakening the "at least one image" rule.
- There are no seed ads in `colecionaveis`, so that filter returns an empty page.
- **Through `AdService`, at startup, idempotent:** each ad is created like any other (validated, `pending`), with the key `seed:<id>`, so a restart imports nothing new. The seed's `created_at` is kept, so its ties (28 distinct values for 57 ads) exercise the listing's tie-breaker. Disabled with `seed.enabled=false` (as in tests).
- **A copy of the file:** `src/main/resources/seed/ads.json` is an unmodified copy of `api-mock/seed/ads.json`, because the app's Docker image can't read the mock's folder and the mock must not be changed.

---

## 8. Architecture: pragmatic hexagonal (ports and adapters)

```
com.olx.ads
├── domain/                 # pure Java: Ad, AdStatus, Category, ModerationResult, RetryPolicy
├── application/            # AdService (create/list/get), ModerationService (claim and moderate a batch)
│   └── port/               # AdRepository, ModerationGateway, PageCursor
├── adapter/
│   ├── in/web/             # controller/ (AdController), dto/ (request/response), error/ (ProblemDetail), request id, cursor, OpenAPI
│   ├── in/scheduler/       # ModerationWorker (@Scheduled → ModerationService; worker deployment only)
│   ├── in/seed/            # SeedImporter (startup → AdService)
│   ├── out/persistence/    # JdbcAdRepository
│   └── out/moderation/     # HttpModerationGateway (RestClient), ResilientModerationGateway (rate limiter + circuit breaker)
└── config/
```

**Why:**
- **Business rules isolated from infrastructure:** `Ad` owns validation and state transitions (`create`, `applyModeration` with the `RetryPolicy`) with no Spring or SQL, so the core is unit-tested without mocks.
- **The provider is a real boundary:** `ModerationGateway` is faked in application tests and implemented over HTTP, with resilience, in its adapter. Swapping the provider (or adding a broker, 1.1) touches one adapter.
- **Familiar** to anyone new to the repo, and **enforced:** an ArchUnit test checks the dependency rule (`domain` depends only on the JDK, `application` not on `adapter`, inbound and outbound adapters not on each other).

**Left out**, to avoid abstractions without a purpose: input-port interfaces per use case (controllers call concrete services), a Gradle module per layer, and JPA entities mirroring the domain. Strict hexagonal with JPA needs a domain `Ad`, an `AdJpaEntity` and a mapper; with `JdbcClient` the row mapper *is* the mapping, and the key queries (`SKIP LOCKED`, keyset pagination) are native SQL anyway.

**Discarded:** classic layered (`controller/service/repository`): simpler, but provider and persistence details tend to leak into services, with no tested boundary.

---

## 9. Stack

| Concern | Choice | Why |
| --- | --- | --- |
| Runtime | Java 25 (LTS), Spring Boot 4.1, Gradle 9 (Kotlin DSL) | Current LTS and Boot generation; the Gradle daemon is pinned to Java 25 and auto-provisioned. Records for DTOs; Lombok never in the domain (its `@Generated` would break the dependency rule) |
| Database | PostgreSQL + Flyway | `SKIP LOCKED` powers the queue; plain SQL migrations |
| Persistence | Spring `JdbcClient` | No JPA entity duplicating the domain model (section 8); native SQL for keyset pagination and the worker claim |
| HTTP / resilience | `RestClient`, Resilience4j (`RateLimiter`, `CircuitBreaker`) | No `Retry` (section 3) |
| API / errors | Bean Validation, `ProblemDetail`, springdoc-openapi | Documented contract via Swagger UI |
| Logging | SLF4J + Logback (Boot default), Boot's built-in structured logging | No extra library. JSON (ECS) in Docker, plain text locally |
| Observability | Spring Boot Actuator + Micrometer (Prometheus registry), structured logs | `/actuator/health`, `/metrics` and `/prometheus`: HTTP, connection pool, circuit breaker, rate limiter. Moderation outcomes and provider latency are in the logs per attempt (6) |
| Load test | k6, Prometheus, Grafana, postgres-exporter (Compose profiles) | Section 11. Off by default, so `docker compose up` stays four services |
| Tests | JUnit 5, AssertJ, Testcontainers, WireMock, Awaitility, ArchUnit | Real Postgres: the queries are Postgres-specific. ArchUnit enforces the architecture's dependency rule |
| Coverage | JaCoCo | Reported, not gated: what is tested matters more than the percentage |
| Formatting | Spotless (google-java-format) | Consistent formatting, so diffs only show real changes |
| Run | Docker Compose (API + worker + Postgres + mock) | One command |

---

## 10. Safe retries of `POST /ads` (`Idempotency-Key`)

A client that times out and retries would otherwise create the ad twice. `POST /ads` **requires** an `Idempotency-Key` header (IETF draft `draft-ietf-httpapi-idempotency-key-header`; Stripe uses the same header):

| Request | Response |
| --- | --- |
| New key | `201`, ad created |
| Same key, same content | `201` with the **same** ad and `Location`, plus `Idempotent-Replayed: true` |
| Same key, different content | `422`: the key was reused for another ad |
| No key, or a malformed one | `400` |

**Why required, not optional:** an optional key protects only the clients that remember to send it; every other retry still duplicates the ad. The alternative, detecting duplicates by content, is wrong without users: two sellers posting the same ad would be merged into one. Trade-off: every client must generate a key (a UUID per create attempt, reused on retries), and a plain `curl` needs the extra header.

- **Storage:** a nullable unique column `ads.idempotency_key`, written by the same insert as the ad (`ON CONFLICT DO NOTHING`), so two concurrent retries can't both create an ad. No separate keys table: one statement, no dual write.
- **"Same content"** compares the validated ad (trimmed title, price by value), not the raw bytes, so `3500` and `3500.00` match.
- **The replay returns the ad's current state**, which may no longer be `pending`; the ad is the same, which is what the client needs.
- **Not done:** keys never expire (a TTL would clear them after e.g. 24h), and they are global, not per user, because there are no users (4.1). With authentication, the unique constraint becomes `(author_id, idempotency_key)`.

---

## 11. Load test and metrics

How the system behaves under load is measured, not assumed: k6 scenarios ([`loadtest/README.md`](./loadtest/README.md)), with metrics from the API, the worker, PostgreSQL and k6 in one Grafana dashboard.
- **k6:** scripted scenarios with pass/fail thresholds (p95, error rate, no 5xx at all, moderation within 60s and no ad left pending), run from its Docker image. Gatling would fit a JVM team equally; k6 scripts are shorter to read.
- **Prometheus + Grafana, provisioned:** the app only adds the Micrometer Prometheus registry (`/actuator/prometheus`) and histogram buckets for HTTP latency, so p95/p99 come from Prometheus. PostgreSQL metrics come from `postgres-exporter`, with `pg_stat_statements` and custom queries for the backlog (ads per status, queue age from `moderation_queued_at`, 6), so no app code exists only for the test.
- **Optional:** the tools run under Compose profiles (`observability`, `loadtest`), so the default stack doesn't change. `/actuator/prometheus` is open like the other actuator endpoints; in production it belongs on an internal port or network.
- **Measured** (one laptop VM, so relative):
  - **API:** creates p95 19 ms with 50 concurrent clients (~5,500 req/s); with 100 production-like users, reads p95 ~3 ms and creates ~8 ms, 0 errors. The connection pool never waited.
  - **Moderation: ~2.2–2.4 ads/s** (2.4 draining 500 ads alone, ~2.2 alongside production reads). The provider gets ~3 of the 4 calls/s allowed, and each ad needs ~1.3 calls (~20% are `500`s, retried), so the ceiling at 4 req/s is ~3 ads/s.
- **Finding:** the missing ~1 req/s is the batch barrier: each run waits for its slowest call before claiming more. Not changed: it only limits how fast a backlog drains, and the fix belongs with the next change to the worker ("more time").

---

## Ambiguities and interpretations

| Ambiguity | Interpretation |
| --- | --- |
| Is moderation synchronous with creation? | No: async, ad starts `pending` (1) |
| What does the listing show? | `published` by default, `status` filter optional (4) |
| Pagination style | Cursor (4) |
| Title length | Trimmed, counted in characters |
| Decimal prices | Yes, 2 places (seed has `395.71`) |
| Max images | 10 (unspecified; a bound protects the API) |
| Seed ads already approved? | No, moderated on import (7) |
| Moderation never succeeds? | Capped retries, then `moderation_failed`; never published (2) |
| Who is the author of an ad? | Not modeled: no authentication in scope (4.1) |

**Mock docs vs. behavior:** `API.md` documents `reason`, but `server.js` returns `rejection_reason`. The client reads `rejection_reason` and falls back to `reason`. In real life this would be reported to the owning team.

---

## Time spent

About 17 hours of active work over four days, estimated from the session logs and commits: well above the suggested 4–6 hours.

| Day | Hours | Work |
| --- | --- | --- |
| Oct 14 | ~1.5 | Reading the brief and the mock's code, first decisions |
| Oct 15 | ~8.5 | Implementation (test-first), Docker setup, docs |
| Oct 16 | ~6 | Revisiting the queue (an SQS version, then back to the table with a single worker), load test and metrics |
| Oct 17 | ~1 | Final review: load-test pass/fail criteria, the queue-age metric, the docs |

## What I would do with more time

- **Authentication and ad ownership** (4.1): author taken from the user's credentials; non-published ads visible only to their author. Then idempotency keys per author, and a rate limit per user on `POST /ads`.
- **Notify the seller** (push or email) when moderation finishes, by publishing an event on each status change (1, 1.1), so only integrations need to poll.
- **Moderation metrics and alerts:** outcomes per status, provider latency, `pending` queue size and age of the oldest pending ad, with alerts on queue age and on the circuit staying open. Today the load-test dashboard shows them (11), but nothing alerts, and outcomes per status are counted from the table, not by the app.
- **Keep the worker's calls in flight continuously:** claim new ads as calls finish, instead of a batch that waits for its slowest call, to use the whole 4 req/s (11).
- **Redrive for `moderation_failed`:** an admin action to put ads back in the queue once the cause is fixed (e.g. after a provider bug).
- **Pause the worker after a `429`** for the `Retry-After`, in case another client shares the provider's quota (3). Not needed so far: 0 `429`s measured with one worker.
- **Expire idempotency keys** (e.g. after 24h) instead of keeping them forever (10).
- **Edge limits:** request body size and per-client rate limits at the gateway (5).
- **Moderation history table**, if decisions must be auditable (seller disputes), since logs are not a system of record (6).
