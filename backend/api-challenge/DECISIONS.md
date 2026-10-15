# Decisions

Technical decisions, discarded alternatives and how ambiguous requirements were interpreted.

---

## 1. Moderation is asynchronous, using the database as the queue

`POST /ads` validates, saves the ad as `pending` and returns `201`. A background worker claims pending ads (`FOR UPDATE SKIP LOCKED`) and calls the moderation API.

**No transaction is held during the HTTP call** (up to 2s). The worker claims an ad in a short transaction by leasing it (pushing `next_attempt_at` forward, so other workers skip it), calls moderation outside any transaction, then saves the result in a second short transaction. If the worker crashes mid-call, the lease expires and the ad is retried.

**The lease is a fencing token.** A slow batch can outlive its lease (rate-limit waits plus timeouts), and another instance may then re-claim the ad. The save only applies `WHERE status = 'pending' AND next_attempt_at = <the lease this worker claimed>`, so the stale worker's write is discarded instead of overwriting the newer attempt. Moderation is therefore **at-least-once**: the provider may see the same ad twice, which is harmless because a check has no side effects.

**Why async:** the moderation API has 100–2000 ms latency, ~20% `500`s and a 5 req/s limit. Calling it inside the request would make creation slow, fail ~1 in 5 times and cap the API at 5 creates/s. Async gives:
- **Low, predictable latency:** creating an ad costs one insert, regardless of the dependency.
- **Availability decoupling:** ad creation keeps working while moderation is slow, down or throttling us, and moderation catches up afterwards.

**Trade-off:** the user doesn't get the moderation result in the response; the ad shows as `pending` until the worker processes it. This is the usual "under review" experience in marketplaces.

**How the client learns the result: polling.** The client polls the `Location` returned by `POST /ads` (`GET /ads/{id}`) until `status` leaves `pending`, usually within seconds. It needs no new endpoint or connection state. Its costs are wasted requests and a client-chosen interval, acceptable at this scale.
- **Evolution: notify the seller** by push or email when the status changes, so the seller never waits on the screen and polling remains only for integrations. That means publishing an event on each status change, one of the triggers for a broker in 1.1.
- **Discarded for now:** webhooks (need registered clients, signing and their own retries, and there are no clients or authentication, 4.1) and Server-Sent Events/WebSocket (long-lived connections across instances for a single status change).

**Why the database as the queue:** no work is lost on restart, multiple instances can run safely, and "ad created" and "work enqueued" are atomic because they are the same row.

**Discarded:**
- **Synchronous call:** exposes users to the dependency's latency, errors and rate limit.
- **Hybrid (sync with fallback to async):** two code paths for a small latency gain.
- **In-memory queue / `@Async`:** loses work on restart.
- **SQS / Kafka:** see 1.1.

### 1.1 Why not SQS or Kafka (yet)

A broker adds infrastructure without removing any hard part of this problem:
- **Dual write:** saving the ad and publishing the message can't share a transaction. The fix, a transactional outbox, is the table-based queue already used here.
- **Same rate limit:** throughput is capped by the provider's 5 req/s, not by the queue. The consumer still needs the rate limiter.
- **Retries fit worse:** backoff and `Retry-After` mean manipulating visibility timeouts (SQS) or retry topics (Kafka). A DLQ after `maxReceiveCount` is the same as the attempt cap in 2.1, but every receive counts, even when no real call was made.
- **Extra cost:** at-least-once delivery requires an idempotent consumer, plus a broker emulator in Docker Compose and in tests.

**When to adopt one:**

| Trigger | Fit |
| --- | --- |
| Other consumers react to ads (search, notifications, fraud) | Kafka (or SNS → SQS) |
| Moderation becomes a separate service/team | SQS (job) or Kafka (event) |
| Replay is needed (reprocess after a policy change) | Kafka |
| Per-ad ordering (edits re-trigger moderation) | Kafka keyed by `ad_id`, or SQS FIFO |
| Queue polling hurts the primary database | SQS |

SQS fits moderation as a **job** (per-message retry, native DLQ and redrive). Kafka fits ads as **events** consumed by several teams (retention, replay).

**Migration path:** keep the outbox, add a relay to the broker, and turn the worker into an idempotent consumer with the same client, rate limiter and status transitions. Size `maxReceiveCount` like the cap in 2.1, stop receiving while the circuit is open, alarm on DLQ depth and redrive once the cause is fixed. The API and status model don't change.

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

Unbounded retries don't overload our server (outbound load is bounded by the rate limiter), but stuck ads would eat the rate-limit budget and starve new ads, and a payload that always triggers a `500` would be retried forever.

The usual objection is that an outage exhausts every ad's attempts. Here, **while the circuit breaker is open the worker doesn't claim ads**, and `429`s don't count either, so attempts only count against a reachable dependency.

**Why 15:** with backoff capped at 5 min, 15 attempts take ~40 minutes (~8.5 min of fast retries, then six at 5 min). A full outage doesn't spend them (the circuit is open), and random failures almost never add up: a healthy ad failing 15 times in a row is ~1 in 10¹⁰ at the normal 20% error rate, and ~1 in a million even in a partial outage at 40% that stays under the circuit-breaker threshold. What the cap really bounds is how long the seller waits for an ad that can never be moderated: ~40 minutes before it shows `moderation_failed`.
- **Lowered from 50:** 50 was safe but left such an ad `pending` for ~4 hours, with no extra protection in practice.

**Discarded:** retrying forever (starvation, poison messages), and a low cap without circuit-breaker awareness (a short blip fails valid ads).

**Next steps:** an endpoint to requeue `moderation_failed` ads (equivalent to a DLQ redrive), and alerting on the failure rate.

---

## 3. Moderation client resilience

- **Timeout ~3s:** the mock's maximum latency is 2s.
- **Client-side rate limiter (~4 req/s, Resilience4j):** stays under the provider's limit instead of discovering it through `429`s. The mock counts rejected requests in its window, so aggressive retries keep us throttled.
  - **1 permit every 250ms, not 4 per second:** the provider counts a sliding 1s window, and Resilience4j refills in fixed periods, so "4 per 1s" let 8 calls through around a period boundary. Measured on the stack: 23 `429`s for 40 ads before, 0 after.
- **One worker at a time across instances (ShedLock):** the rate limiter lives in each instance's memory, so N instances running the worker would send up to N × 4 req/s and get `429`s. Each worker run first takes a lock row in PostgreSQL (`shedlock` table); the other instances skip that run. The API itself still scales to any number of instances.
  - **Why a lock and not a shared rate limiter:** one instance with 4 calls in flight already reaches the provider's limit, so more workers running at once would only split the same 5 req/s. The lock makes the in-memory limiter effectively global, with one lock per run instead of a database round trip per call. A shared limiter (Bucket4j on Postgres, or Redis) becomes right when several workers must run at once, e.g. one per category or region.
  - **Failover:** the lock is released at the end of each run, so any instance can take the next one. If an instance dies mid-run, the lock expires after `lock-at-most-for` (2 min, above the ~40s worst-case run) and another instance takes over. If a run ever outlived it, two workers could overlap; the claim (`SKIP LOCKED`) and the lease fencing token (section 1) still keep every write correct, it would only risk `429`s.
  - **Database clock:** lock times use PostgreSQL's clock (`usingDbTime`), so clock skew between instances can't let two of them hold the lock.
  - **Measured:** two instances on the stack took turns running the worker (24 and 16 calls) with 0 `429`s.
- **Parallel calls within a batch (4 at a time, virtual threads):** each call mostly waits on the provider's latency (~1s), so one call at a time used ~1 of the 4 req/s. With 4 in flight the rate limiter becomes the limit. The batch still finishes before the next run starts, so runs never overlap. Measured: 40 ads in ~20s, most of it the backoff of the ~20% that fail; sequential managed ~1 ad/s.
- **Circuit breaker (Resilience4j):** stops claiming ads when the dependency is unhealthy. The threshold (~50%) is set above the normal 20% error rate.
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

**Size limits and control characters:** without them, a 5 MB description was stored, and a NUL character reached PostgreSQL (which can't store it) and returned `500`. The limits bound storage per ad; the whole request body is still read before validation, so a body-size limit belongs at the edge (load balancer or gateway).

**Where each rule lives:** the request DTO (Bean Validation) only checks presence and simple bounds; the precise rules (title length in characters, price scale, image URLs, category) live in the domain, defined once and applied to every way an ad is created (API, seed). Both produce the same `400` with `errors: [{field, message}]`. Bean Validation's `@Size` would also be wrong for titles: it counts UTF-16 units, so an emoji counts as 2.
- **Trade-off:** the domain stops at the first broken rule, and only runs after the DTO checks pass, so a request with several mistakes may not report all of them at once.

**Left to moderation:** contact info, prohibited items and price range per category. That policy belongs to another service, and duplicating it creates two sources of truth.

**Conscious duplication:** the category list. An invalid category could never be moderated (the mock returns `400`), so failing fast is better.

**Category as an enum, not a table:** the valid list belongs to the moderation provider (it rejects unknown categories), so a table would be a second source of truth that suggests categories can be added with an `INSERT`, and ads in a new category would just fail moderation. Trade-off: adding one means changing the enum and a migration. A table (or a shared categories service) becomes right when categories gain metadata, a hierarchy, or must be managed without a deploy.

---

## 6. Data model

- **Money:** `BigDecimal` / `NUMERIC(12,2)`, never floating point. **Time:** `Instant` / `TIMESTAMPTZ`, ISO-8601 UTC.
- **Images:** a URL list on `ads` (`TEXT[]`). They are always read with the ad and never queried alone. An `ad_images` table becomes worth it once images gain metadata.
- **Moderation state as columns on `ads`:** `moderation_attempts`, `next_attempt_at` (null once terminal), `last_moderation_error`, `rejection_reason`. The worker's claim query needs them in the same transaction. A partial index `(next_attempt_at) WHERE status = 'pending'` keeps it cheap. The per-attempt history goes to structured logs keyed by `ad_id`.
- **Logging:** each moderation attempt logs the provider's HTTP status and latency, then the outcome and attempt number: `INFO` for results, `WARN` for transient failures, `ERROR` for `moderation_failed`. Ad creation logs `INFO` (created, idempotent replay) and `WARN` (key reused); validation `400`s are not logged, as they are client errors. `ad_id` (worker) and `request_id` (HTTP) are set in the MDC, so one ad's history or one request's logs can be found with a single filter. The `request_id` reuses the caller's `X-Request-Id` when it is safe to log (letters, digits, `._-`, up to 64 characters), otherwise a UUID; it is returned in the response.
  - **No logging port:** SLF4J already is the port (a facade, with Logback as the swappable adapter), and logs go to stdout for the platform to collect. A business record that must be kept, like the moderation history below, would be a table behind a port, not a log.
  - **Discarded, blob storage:** meant for large unstructured files, not small queryable state.
  - **Discarded, `moderation_attempts` history table:** no consumer in this scope. It becomes right when moderation decisions must be auditable (seller disputes, analytics), because logs are not a system of record.

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
│   ├── in/web/             # AdController, DTOs, ProblemDetail handler, request id, OpenAPI
│   ├── in/scheduler/       # ModerationWorker (@Scheduled + ShedLock → ModerationService)
│   ├── in/seed/            # SeedImporter (startup → AdService)
│   ├── out/persistence/    # JdbcAdRepository
│   └── out/moderation/     # HttpModerationGateway (RestClient), ResilientModerationGateway (rate limiter + circuit breaker)
└── config/
```

**Why:**
- **Business rules isolated from infrastructure.** `Ad` owns its validation and state transitions (`create`, `applyModeration` with the `RetryPolicy`) with no Spring or SQL, so the core logic is unit-tested without mocks.
- **The external dependency is a real boundary.** `ModerationGateway` is faked in application tests and implemented over HTTP with resilience in its adapter. Swapping the provider (or moving to a broker, see 1.1) touches only an adapter.
- **Familiar to new readers.** It's a well-known layout, so someone new to the repo knows where to look.
- **Enforced, not just conventional:** an ArchUnit test checks the dependency rule (`domain` depends only on the JDK; `application` doesn't depend on `adapter`; inbound and outbound adapters don't depend on each other).

**What was deliberately left out**, to avoid abstractions without a purpose:
- **Input-port interfaces per use case** (`CreateAdUseCase` + `Impl`): controllers call concrete application services.
- **A Gradle module per layer:** packages are enough at this size.
- **JPA entities mirroring the domain model:** with JPA, strict hexagonal needs both a domain `Ad` and an `AdJpaEntity` plus a mapper. The persistence adapter uses `JdbcClient` instead, so the row mapper *is* the mapping. This also fits better because the key queries (`SKIP LOCKED`, keyset pagination) are native SQL anyway.

**Discarded:** classic layered (`controller/service/repository`). Simpler, but the moderation client and persistence details tend to leak into services, and there's no tested boundary.

---

## 9. Stack

| Concern | Choice | Why |
| --- | --- | --- |
| Runtime | Java 25 (LTS), Spring Boot 4.1, Gradle 9 (Kotlin DSL) | Current LTS and Boot generation. The Gradle daemon is pinned to Java 25 and auto-provisioned, so the build doesn't depend on the local JDK. Records for DTOs; Lombok only where records don't fit, never in the domain (its `@Generated` annotation would break the dependency rule) |
| Database | PostgreSQL + Flyway | `SKIP LOCKED` powers the queue; plain SQL migrations |
| Persistence | Spring `JdbcClient` | No JPA entity duplicating the domain model (section 8); native SQL for keyset pagination and the worker claim |
| HTTP / resilience | `RestClient`, Resilience4j (`RateLimiter`, `CircuitBreaker`) | No `Retry` (section 3) |
| Worker lock | ShedLock (JDBC provider) | One worker run at a time across instances, on the existing PostgreSQL (section 3). Small, widely used for exactly this with `@Scheduled` |
| API / errors | Bean Validation, `ProblemDetail`, springdoc-openapi | Documented contract via Swagger UI |
| Logging | SLF4J + Logback (Boot default), Boot's built-in structured logging | No extra library. JSON (ECS) in Docker, plain text locally |
| Observability | Spring Boot Actuator + Micrometer, structured logs | `/actuator/health`; `/actuator/metrics` with HTTP and Resilience4j metrics (circuit-breaker state and failure rate, rate-limiter permits). Moderation outcomes and provider latency are in the logs, per attempt, with `ad_id` (section 6). Custom metrics are listed under "more time" |
| Tests | JUnit 5, AssertJ, Testcontainers, WireMock, Awaitility, ArchUnit | Real Postgres: the queries are Postgres-specific. ArchUnit enforces the architecture's dependency rule |
| Coverage | JaCoCo | Reported, not gated: what is tested matters more than the percentage |
| Formatting | Spotless (google-java-format) | Consistent formatting, so diffs only show real changes |
| Run | Docker Compose (app + Postgres + mock) | One command |

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

_To be filled in at the end._

## What I would do with more time

- **Authentication and ad ownership** (4.1): author taken from the user's credentials; non-published ads visible only to their author. Then idempotency keys per author, and a rate limit per user on `POST /ads`.
- **Notify the seller** (push or email) when moderation finishes, by publishing an event on each status change (1, 1.1), so only integrations need to poll.
- **Moderation metrics and alerts:** outcomes per status, provider latency, `pending` queue size and age of the oldest pending ad, with alerts on queue age and on the circuit staying open. Today these are in the logs only.
- **Redrive for `moderation_failed`:** an admin action to put ads back in the queue once the cause is fixed (e.g. after a provider bug).
- **Pause the worker after a `429`** for the `Retry-After`, in case another client shares the provider's quota (3). Not needed so far: 0 `429`s measured, even with two instances.
- **Expire idempotency keys** (e.g. after 24h) instead of keeping them forever (10).
- **Edge limits:** request body size and per-client rate limits at the gateway (5).
- **Moderation history table**, if decisions must be auditable (seller disputes), since logs are not a system of record (6).
