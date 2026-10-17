# Load test and metrics

[k6](https://k6.io) scenarios against the running stack, with metrics from the API, the worker, PostgreSQL and k6 itself in Prometheus and Grafana. Everything runs in Docker; nothing to install.

## Running

```bash
docker compose --profile observability up -d --build     # app + Prometheus + Grafana + postgres-exporter
docker compose run --rm k6 run /scripts/backlog.js       # or mixed.js, create.js
```

| Service | URL |
| --- | --- |
| Grafana (dashboard "Ads API — load test", no login) | `http://localhost:3000` |
| Prometheus | `http://localhost:9090` |

k6 prints its summary with pass/fail thresholds, and pushes its metrics to Prometheus (remote write), so the load and its effects are on the same dashboard. Start from an empty database for comparable runs: `docker compose --profile observability down -v`. To restart only the query statistics: `docker compose exec postgres psql -U ads -d ads -c 'SELECT pg_stat_statements_reset()'`.

## Scenarios

| Script | Load | What it shows |
| --- | --- | --- |
| `production.js` | 100 users at once for 5 min (`-e HOLD=10m`), with think time: 70 browse the feed (filters, pages, ads), 15 open ads from shared links, 15 sell (create, then poll until moderated; some retry with the same key or send an invalid ad) | Production-like traffic, mostly reads, every endpoint at once; the time a seller waits for moderation (`moderation_time`) |
| `capacity.js` | The production readers (85 users) while creation climbs in steps: 0.5, 1, 2, 3 and 4 ads/s, 2 min each (`-e RATES=30,60,120`, ads per minute); one ad in five is followed until moderated | Moderation capacity: the first step that misses the moderation criterion (expected to fail from 3 ads/s) |
| `backlog.js` | 500 creates from 50 clients at once (`-e ADS=1000`, `-e MIN_RATE=2` ads/s to pass), then polls until no ad is `pending` | The API absorbs the burst in a fraction of a second; moderation drains it at the provider's pace |
| `mixed.js` | 100 iterations/s for 2 min: 80% listing (following `next_cursor`), 15% detail, 5% create | Read latency per endpoint and the listing indexes under load |
| `create.js` | `POST /ads` ramping to 200/s, held for 1 min (`-e PEAK=50 -e RAMP=20s -e HOLD=20s` for a smaller run) | How far ad creation scales. Every ad joins the moderation backlog: the default run keeps the worker busy for hours |

## Pass/fail criteria

A run fails (k6 exits non-zero) when any threshold is crossed. Thresholds shared by every script live in `lib.js`.

| Criterion | Threshold | Scripts |
| --- | --- | --- |
| No server errors: any 5xx, or no response at all | `server_errors` count == 0 | all |
| Other unexpected responses (4xx where 2xx was expected) | `http_req_failed` < 1%, `checks` > 99% | all |
| Latency per endpoint | `http_req_duration` p95 (200 ms listing, 100 ms detail, 200–300 ms create) | production, mixed, create, backlog |
| Moderation within the criterion | `moderation_time` p95 < 60s | production; capacity, per step |
| No ad left behind: a followed ad still pending when the script stops waiting (2 min in production, 10 min in capacity) is a timeout, not a missing sample | `moderation_timeouts` count == 0 | production, capacity (per step), backlog |
| Moderation actually happened (an empty Trend passes its p95 threshold) | `ads_moderated` count > 0 | production, capacity |
| Backlog drained fast enough | `backlog_drain_seconds` < `ADS / MIN_RATE` (default 2 ads/s); the ads still pending at twice that time are timeouts | backlog |

- A failed poll (5xx, timeout) is an error, never read as "still pending" or "no pending ads": before this, a `500` while polling the backlog could end it as drained.
- `capacity.js` holds every step to the moderation criterion, so with the default steps it **is expected to fail** from 3 ads/s on: the first ✗ step is the capacity. `-e RATES=30,60,120` runs only the steps that must pass.
- Sellers get a graceful stop longer than their timeout, so a run that ends doesn't cut off ads mid-follow without counting them.

## What is collected

| Source | Metrics |
| --- | --- |
| API and worker (`/actuator/prometheus`, Micrometer) | Requests/s and p95/p99 per endpoint, HikariCP pool (active, waiting, acquire time), CPU; provider calls by HTTP status and latency, rate limiter waits, circuit-breaker state and failure rate |
| PostgreSQL (`postgres-exporter`) | Transactions/s, rows inserted/updated/fetched, connections by state, locks, cache hit ratio, the slowest statements with their SQL, mean/max time and calls (`pg_stat_statements`), scans per index (an index at 0 is unused), ads per status and the queue age: how long the oldest `pending` ad has waited since it was created, retries included (custom queries, `observability/postgres-exporter-queries.yml`) |
| k6 | Virtual users, requests/s, client-side p95 per request |

## Results (Colima VM on a laptop, everything in one VM: relative, not absolute)

| Scenario | Result |
| --- | --- |
| Burst of 500 creates, 50 clients | Accepted in ~0.1s (~5,500 req/s), p95 19 ms, 0 errors. On a cold JVM (first requests after start) p95 was 163 ms: JIT warm-up, not the pool (0 connections waited) |
| Backlog drain, 500 ads | 209s, **2.4 ads/s**: 614 provider calls (480 `200`, 107 `500` retried, 2 `429`), circuit never opened |
| Production-like, 100 users, 5 min | 6,494 requests, 0 errors; p95 3.5 ms (listing), 2.8 ms (detail), 7.6 ms (create); moderation p95 6s |
| Capacity, 0.5 → 4 ads/s | Moderation keeps up to 2 ads/s; from the 3 ads/s step the backlog grows and throughput plateaus at **~2.1–2.3 ads/s**. The circuit opened briefly in the top steps: its 20-call window is noisy at a 20% error rate |
| Mixed, 100 iterations/s | ~139 req/s, p95 2.2 ms (listing) and 1.9 ms (detail), 0 errors |
| Creates ramping to 50/s | p95 5.4 ms, 0 errors |

**Findings**
- **The API is not the bottleneck:** creates and reads stay in single-digit milliseconds; the pool of 10 connections never queued, because every statement is short and the worker holds no connection during the provider call.
- **Moderation runs at ~2.2–2.4 ads/s, using ~3 of its 4 req/s:** each ad needs ~1.3 calls (~20% are `500`s, retried), and each run claims 20 ads and waits for its slowest call before the next run (plus the 1s poll interval), so the tail of every batch (up to 2s) is idle quota. A worker that claims more as calls finish would get close to 4 req/s, ~3 ads/s (`DECISIONS.md`, "more time").
- **The test caught its own bug:** detail requests on random ids from 1 to the newest got 15% `404`s, because an identity sequence skips the values of conflicting inserts. The script now takes ids from the listing.
