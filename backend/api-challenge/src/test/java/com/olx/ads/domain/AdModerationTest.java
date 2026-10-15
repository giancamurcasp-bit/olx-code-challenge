package com.olx.ads.domain;

import static com.olx.ads.domain.AdFixtures.NOW;
import static com.olx.ads.domain.AdFixtures.POLICY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AdModerationTest {

  private final Ad ad = AdFixtures.pendingAd();

  // --- outcomes ---

  @Test
  void approvedResultPublishesTheAd() {
    ad.applyModeration(new ModerationResult.Approved(), POLICY, NOW);

    assertThat(ad.getStatus()).isEqualTo(AdStatus.PUBLISHED);
    assertThat(ad.getModerationAttempts()).isEqualTo(1);
  }

  @Test
  void rejectedResultStoresTheReason() {
    ad.applyModeration(new ModerationResult.Rejected("contact_info_not_allowed"), POLICY, NOW);

    assertThat(ad.getStatus()).isEqualTo(AdStatus.REJECTED);
    assertThat(ad.getRejectionReason()).isEqualTo("contact_info_not_allowed");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void rejectedResultRequiresAReason(String reason) {
    assertThatThrownBy(() -> new ModerationResult.Rejected(reason))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void permanentFailureFailsModerationWithTheError() {
    ad.applyModeration(
        new ModerationResult.PermanentFailure("http 400: validation_error"), POLICY, NOW);

    assertThat(ad.getStatus()).isEqualTo(AdStatus.MODERATION_FAILED);
    assertThat(ad.getLastModerationError()).isEqualTo("http 400: validation_error");
  }

  @ParameterizedTest
  @MethodSource("terminalResults")
  void finishedAdsAreNoLongerScheduled(ModerationResult result) {
    ad.applyModeration(result, POLICY, NOW);

    assertThat(ad.getNextAttemptAt()).isNull();
  }

  @ParameterizedTest
  @MethodSource("terminalResults")
  void finishedAdsCannotChangeStatusAgain(ModerationResult result) {
    ad.applyModeration(result, POLICY, NOW);
    AdStatus finalStatus = ad.getStatus();

    assertThatThrownBy(
            () -> ad.applyModeration(new ModerationResult.Approved(), POLICY, NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(ad.getStatus()).isEqualTo(finalStatus);
  }

  // --- retries ---

  @Test
  void transientFailureCountsAnAttemptAndSchedulesARetry() {
    ad.applyModeration(new ModerationResult.TransientFailure("http 500"), POLICY, NOW);

    assertThat(ad.getStatus()).isEqualTo(AdStatus.PENDING);
    assertThat(ad.getModerationAttempts()).isEqualTo(1);
    assertThat(ad.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
  }

  @Test
  void transientFailureRecordsTheErrorForDiagnosis() {
    ad.applyModeration(new ModerationResult.TransientFailure("timeout after 3s"), POLICY, NOW);

    assertThat(ad.getLastModerationError()).isEqualTo("timeout after 3s");
  }

  @Test
  void consecutiveTransientFailuresBackOffExponentially() {
    ad.applyModeration(new ModerationResult.TransientFailure("http 500"), POLICY, NOW);
    ad.applyModeration(new ModerationResult.TransientFailure("http 500"), POLICY, NOW);

    assertThat(ad.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
  }

  @Test
  void rateLimitedResultWaitsForRetryAfterWithoutCountingAnAttempt() {
    ad.applyModeration(new ModerationResult.RateLimited(Duration.ofSeconds(3)), POLICY, NOW);

    assertThat(ad.getStatus()).isEqualTo(AdStatus.PENDING);
    assertThat(ad.getModerationAttempts()).isZero();
    assertThat(ad.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(3));
  }

  @Test
  void staysPendingUntilTheLastAllowedAttempt() {
    failTransiently(POLICY.maxAttempts() - 1);

    assertThat(ad.getStatus()).isEqualTo(AdStatus.PENDING);
  }

  @Test
  void failsModerationWhenRetriesAreExhausted() {
    failTransiently(POLICY.maxAttempts());

    assertThat(ad.getStatus()).isEqualTo(AdStatus.MODERATION_FAILED);
    assertThat(ad.getLastModerationError()).isEqualTo("retries_exhausted");
    assertThat(ad.getNextAttemptAt()).isNull();
  }

  // --- helpers ---

  private void failTransiently(int times) {
    for (int i = 0; i < times; i++) {
      ad.applyModeration(new ModerationResult.TransientFailure("http 500"), POLICY, NOW);
    }
  }

  static Stream<ModerationResult> terminalResults() {
    return Stream.of(
        new ModerationResult.Approved(),
        new ModerationResult.Rejected("prohibited_item"),
        new ModerationResult.PermanentFailure("http 400"));
  }
}
