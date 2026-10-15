package com.olx.ads.domain;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** Exponential backoff with jitter and a cap on attempts (DECISIONS.md, section 2.1). */
public record RetryPolicy(
    int maxAttempts, Duration baseDelay, Duration maxDelay, DoubleSupplier jitter) {

  public static RetryPolicy defaults() {
    throw new UnsupportedOperationException("not implemented yet");
  }

  public Duration delayFor(int attempt) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
