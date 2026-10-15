package com.olx.ads.application.port;

import com.olx.ads.domain.Ad;
import com.olx.ads.domain.ModerationResult;

/** Port to the external moderation provider. Never throws: every failure is a result. */
public interface ModerationGateway {

  ModerationResult check(Ad ad);

  /**
   * Whether calls would currently go through. False while the provider is considered unhealthy, so
   * the worker doesn't claim ads it can't moderate (DECISIONS.md, section 2.1).
   */
  default boolean isAcceptingCalls() {
    return true;
  }
}
