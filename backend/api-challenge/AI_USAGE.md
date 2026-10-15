# AI Usage

How AI was used in this project, what it got wrong, and how its output was validated.

> Living document: updated as the implementation progresses.

## Tools

| Tool | Used for |
| --- | --- |
| Claude Code (Claude Opus) in the terminal | Analyzing the challenge and the mock, discussing design decisions, drafting `DECISIONS.md` / `README.md`, and implementation (in progress) |
| ai-memory (MCP server + hooks) | Long-term memory across sessions: captures session context and hands off where the last session stopped, so I don't re-explain the project every time. Because it is an MCP server, any MCP-compatible agent can use it, so context carries over when I switch AI providers (together with the tool-agnostic `AGENTS.md`). `DECISIONS.md` and `AGENTS.md` stay the source of truth; memory is only context |

## How I worked with it

1. **Design before code.** No code was generated until the main decisions were discussed and written in `DECISIONS.md`. Generated code then has a reference to be checked against, instead of the code defining the design.
2. **AI as a sparring partner, not an oracle.** I asked for a recommendation with the trade-offs, then challenged it. Several decisions changed because of that (see below).
3. **Verify claims against the source.** Anything about the mock or the data was checked against `server.js` and the seed file, not taken from the AI's summary.
4. **I own every line.** The AI drafts; I decide what stays, and I review every change before committing.
5. **Tests first as the spec.** For domain logic, the moderation adapter and pagination, the test is written (or reviewed by me) before the implementation, and the AI may not change it to make it pass. Generated code is checked against expectations I defined, not ones the AI inferred from its own implementation.
6. **Project rules written down for the agent.** [`AGENTS.md`](./AGENTS.md) gives any AI agent the architecture, testing and documentation rules, and points it to `DECISIONS.md` as the source of truth. It is tool-agnostic so I can switch providers; `CLAUDE.md` only imports it.

## Examples

### What worked

- **Finding traps in the provided material.** Prompt: *"Take a look at this repo and analyze what's being proposed."* By reading `server.js` instead of only the docs, the AI found that the mock returns `rejection_reason` while `API.md` documents `reason`, and that the mock counts rate-limited requests in its window (so aggressive retries keep you throttled). I confirmed both in the code.
- **Checking the data with a script, not by eye.** The AI wrote a throwaway script over `seed/ads.json` that found 57 ads with only 28 distinct `created_at` values (so date-only pagination is unstable), no images, and no ads in `colecionaveis`. All three became explicit decisions.
- **Pushback on my own ideas.** I proposed adding SQS to show async skills. The AI argued against it: dual-write problem, same provider rate limit, worse retry fit. I agreed, but asked for the reasoning to be documented with concrete triggers for when a broker *would* be the right call (`DECISIONS.md` 1.1), so the decision reads as deliberate rather than a gap.
- **Scoping an architecture down to what earns its place.** I proposed hexagonal architecture for separation of concerns, maintainability and familiarity. The AI agreed but warned that the strict version produces exactly the "leftover abstractions" the rubric penalizes. We kept ports only at real boundaries (moderation, persistence) and skipped per-use-case interfaces and per-layer modules. It also pointed out that hexagonal + JPA means duplicate models (domain `Ad` + JPA entity + mapper), so I **switched persistence from JPA to `JdbcClient`**: our key queries were native SQL anyway. An ArchUnit test makes the boundaries enforced rather than conventional (`DECISIONS.md` 8).

### What I corrected or rejected

| AI output | Problem | What I did |
| --- | --- | --- |
| Moderation `400` errors left the ad `pending` with no further retries | `pending` would mean both "in progress" and "stuck forever", invisible from the outside | Asked whether a terminal status was needed, which led to `moderation_failed` |
| Retry transient errors **forever** (the AI's recommendation) | I challenged it: wouldn't infinite retries pile up? The AI then acknowledged real problems: stuck ads starve the rate-limit budget, and a payload that always triggers a `500` is retried forever | Retry cap that doesn't count attempts while the circuit breaker is open, so an outage doesn't fail valid ads (`DECISIONS.md` 2.1). Started at 50; later, asking "is it too much?", I lowered it to 15: the math showed 50 added no real protection and left an unmoderatable ad `pending` for ~4 hours instead of ~40 minutes |
| A `DECISIONS.md` of ~3,300 words | Too long for reviewers, with the same retry logic explained in three places | Asked for it to be shortened without losing information: about half the length, with duplicate sections merged |
| The "why async" argument only covered the dependency's failures | It was missing the user-facing benefits | Added low latency and availability decoupling, and asked for the UX trade-off (result not immediate, ad shows "under review") to be stated explicitly |
| Users and authentication were not mentioned | A real gap: anyone can see rejected ads | Decided it is out of scope for this version, but documented it explicitly as something to implement (`DECISIONS.md` 4.1) |
| The worker's lease comment said "a live worker never loses its claim" | False: a batch can wait on the rate limiter and time out on several ads, outliving the 30s lease, so two instances could write the same ad | Asked for a security and race-condition review; the fix makes the lease a fencing token, so the stale write is discarded (`DECISIONS.md` 1) |
| `Idempotency-Key` added as an optional header (the AI's recommendation) | I pointed out that duplicates were still possible for any client that doesn't send it | Made it required, and documented why content-based deduplication isn't an option without users (`DECISIONS.md` 10) |
| `AdService` had no unit tests; its behavior was only covered through the HTTP API and database tests | Idempotency logic (replay vs. key reuse) was tested only end to end, so a failure wouldn't point to the service | Asked for unit tests: an in-memory repository instead of mocks, so a retry really finds the stored ad (`AdServiceTest`) |

## How I validated

- **Design:** by challenging every recommendation with "what breaks if...?" and checking claims against the mock's code and the seed data.
- **Code:**
  - **Scaffold:** I had the agent run the full build (formatting, ArchUnit rules, Spring context against a real Postgres via Testcontainers) and `docker compose up`, checking that the app reports `UP`, the migration is applied and logs are JSON. Running it caught two problems a read-through wouldn't: a pinned formatter version that crashed in the build's JVM (switched to Spotless's default) and a port clash with another local Postgres (moved to 5433).
  - **The architecture test caught a real violation:** the generated domain code used Lombok's `@Getter`, which adds `@lombok.Generated` to each getter, so the domain depended on Lombok. ArchUnit failed the build; I chose explicit getters to keep the domain pure, instead of loosening the rule.
  - **Design review while writing the migration:** the single listing index from `DECISIONS.md` couldn't efficiently serve the listing without a category filter; split into two indexes and corrected the doc.
  - **Security and race-condition review on the running stack:** I asked the AI to attack the API, not just read the code. It confirmed findings with real requests instead of guessing: a NUL character returned `500` (PostgreSQL can't store it) and a 5 MB description was accepted. Each fix started with a failing test, then was re-checked against the stack (`400` for both; 5 concurrent requests with the same `Idempotency-Key` created one ad). Findings judged low-risk (public metrics, per-instance rate limiter) were documented instead of fixed.
  - **Measuring, not assuming, after parallelizing moderation:** the unit test proved 4 calls ran at once, but a run of 40 ads against the stack showed 23 `429`s, where the sequential version had none. The rate limiter's fixed 1s periods let 8 calls through around a period boundary, while the provider counts a sliding window. Spreading permits to 1 every 250ms brought it to 0. A reading of the code wouldn't have caught it.
  - _Implementation: to be filled in._
