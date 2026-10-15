package com.olx.ads.application;

import com.olx.ads.application.port.AdRepository;
import com.olx.ads.application.port.ModerationGateway;
import com.olx.ads.domain.RetryPolicy;
import java.time.Clock;
import java.time.Duration;

/** Moderates pending ads in batches (DECISIONS.md, section 1). */
public class ModerationService {

  public ModerationService(
      AdRepository repository,
      ModerationGateway gateway,
      Clock clock,
      RetryPolicy retryPolicy,
      int batchSize,
      Duration lease) {}

  /** Claims the ads that are due, moderates each one and saves the outcome. */
  public void moderateDueAds() {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
