package com.olx.ads.domain;

import java.time.Duration;

/** Outcome of one call to the moderation provider, as the domain understands it. */
public sealed interface ModerationResult {

  record Approved() implements ModerationResult {}

  record Rejected(String reason) implements ModerationResult {
    public Rejected {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("a rejection must have a reason");
      }
    }
  }

  /** Worth retrying: 5xx, timeout, connection error. Counts as an attempt. */
  record TransientFailure(String error) implements ModerationResult {}

  /** The provider asked us to slow down. Does not count as an attempt. */
  record RateLimited(Duration retryAfter) implements ModerationResult {}

  /** Retrying can't fix it: 4xx or an unparseable response. */
  record PermanentFailure(String error) implements ModerationResult {}
}
