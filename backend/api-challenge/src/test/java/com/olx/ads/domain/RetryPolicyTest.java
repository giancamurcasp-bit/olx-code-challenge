package com.olx.ads.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

  private static final Duration BASE = Duration.ofSeconds(1);
  private static final Duration CAP = Duration.ofMinutes(5);

  @ParameterizedTest
  @CsvSource({"1, 1", "2, 2", "3, 4", "4, 8"})
  void delayDoublesWithEachAttempt(int attempt, long expectedSeconds) {
    RetryPolicy policy = new RetryPolicy(50, BASE, CAP, () -> 0.0);

    assertThat(policy.delayFor(attempt)).isEqualTo(Duration.ofSeconds(expectedSeconds));
  }

  @ParameterizedTest
  @CsvSource({"10", "50", "1000"})
  void delayNeverExceedsTheCap(int attempt) {
    RetryPolicy policy = new RetryPolicy(50, BASE, CAP, () -> 0.0);

    assertThat(policy.delayFor(attempt)).isEqualTo(CAP);
  }

  @Test
  void jitterAddsUpTo20PercentOnTopOfTheDelay() {
    RetryPolicy policy = new RetryPolicy(50, BASE, CAP, () -> 0.5);

    assertThat(policy.delayFor(3)).isEqualTo(Duration.ofMillis(4_400));
  }

  @Test
  void jitterNeverPushesTheDelayAboveTheCap() {
    RetryPolicy policy = new RetryPolicy(50, BASE, CAP, () -> 0.99);

    assertThat(policy.delayFor(9)).isEqualTo(CAP); // 256s + 20% would be ~307s
  }

  @Test
  void defaultsMatchTheDocumentedPolicy() {
    RetryPolicy policy = RetryPolicy.defaults();

    assertThat(policy.maxAttempts()).isEqualTo(15);
    assertThat(policy.baseDelay()).isEqualTo(BASE);
    assertThat(policy.maxDelay()).isEqualTo(CAP);
  }
}
