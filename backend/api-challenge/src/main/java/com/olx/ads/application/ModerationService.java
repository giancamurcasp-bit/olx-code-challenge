package com.olx.ads.application;

import com.olx.ads.application.port.AdRepository;
import com.olx.ads.application.port.ModerationGateway;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.ModerationResult;
import com.olx.ads.domain.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Moderates pending ads in batches (DECISIONS.md, section 1): claim with a lease (short statement),
 * call the provider outside any transaction, then save the outcome (short statement).
 */
public class ModerationService {

  private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

  private final AdRepository repository;
  private final ModerationGateway gateway;
  private final Clock clock;
  private final RetryPolicy retryPolicy;
  private final int batchSize;
  private final Duration lease;

  public ModerationService(
      AdRepository repository,
      ModerationGateway gateway,
      Clock clock,
      RetryPolicy retryPolicy,
      int batchSize,
      Duration lease) {
    this.repository = repository;
    this.gateway = gateway;
    this.clock = clock;
    this.retryPolicy = retryPolicy;
    this.batchSize = batchSize;
    this.lease = lease;
  }

  /** Claims the ads that are due, moderates each one and saves the outcome. */
  public void moderateDueAds() {
    if (!gateway.isAcceptingCalls()) {
      log.debug("Moderation provider unavailable; not claiming ads");
      return; // ads stay due, and no attempts are spent while the provider is down
    }
    List<Ad> ads = repository.claimDue(batchSize, clock.instant(), lease);
    ads.forEach(this::moderate);
  }

  private void moderate(Ad ad) {
    MDC.put("ad_id", String.valueOf(ad.getId()));
    try {
      Instant leasedUntil = ad.getNextAttemptAt();
      ModerationResult result = gateway.check(ad);
      ad.applyModeration(result, retryPolicy, clock.instant());
      if (repository.update(ad, leasedUntil)) {
        logOutcome(ad, result);
      } else {
        log.warn("Moderation result discarded: ad is no longer pending");
      }
    } catch (RuntimeException e) {
      // One broken ad must not stop the batch; its lease expires and it is retried.
      log.error("Unexpected error moderating ad; it will be retried after its lease", e);
    } finally {
      MDC.remove("ad_id");
    }
  }

  private static void logOutcome(Ad ad, ModerationResult result) {
    int attempt = ad.getModerationAttempts();
    switch (ad.getStatus()) {
      case PUBLISHED -> log.info("Ad published (attempt {})", attempt);
      case REJECTED -> log.info("Ad rejected: {} (attempt {})", ad.getRejectionReason(), attempt);
      case MODERATION_FAILED ->
          log.error("Moderation failed: {} (attempt {})", ad.getLastModerationError(), attempt);
      case PENDING ->
          log.warn("Moderation retry scheduled at {}: {}", ad.getNextAttemptAt(), describe(result));
    }
  }

  private static String describe(ModerationResult result) {
    return switch (result) {
      case ModerationResult.TransientFailure failure -> failure.error();
      case ModerationResult.RateLimited rateLimited ->
          "rate limited for " + rateLimited.retryAfter();
      default -> result.toString();
    };
  }
}
