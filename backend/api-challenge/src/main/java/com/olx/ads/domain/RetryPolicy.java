package com.olx.ads.domain;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter and a cap on attempts (DECISIONS.md, section 2.1).
 *
 * @param jitter supplies a value in [0, 1); injected so tests are deterministic
 */
public record RetryPolicy(
    int maxAttempts, Duration baseDelay, Duration maxDelay, DoubleSupplier jitter) {

  private static final double MAX_JITTER_RATIO = 0.2;
  private static final int MAX_EXPONENT = 30; // beyond this the cap always applies; avoids overflow

  public static RetryPolicy defaults() {
    return new RetryPolicy(
        50,
        Duration.ofSeconds(1),
        Duration.ofMinutes(5),
        () -> ThreadLocalRandom.current().nextDouble());
  }

  /** Delay before the next try, after the given number of failed attempts (1-based). */
  public Duration delayFor(int attempt) {
    long capMillis = maxDelay.toMillis();
    int exponent = Math.min(attempt - 1, MAX_EXPONENT);
    long exponential = Math.min(capMillis, baseDelay.toMillis() << exponent);
    long withJitter = exponential + (long) (exponential * MAX_JITTER_RATIO * jitter.getAsDouble());
    return Duration.ofMillis(Math.min(capMillis, withJitter));
  }
}
