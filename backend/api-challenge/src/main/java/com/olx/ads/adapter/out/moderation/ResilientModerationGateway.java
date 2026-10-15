package com.olx.ads.adapter.out.moderation;

import com.olx.ads.application.port.ModerationGateway;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.ModerationResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;

public class ResilientModerationGateway implements ModerationGateway {

  public ResilientModerationGateway(
      ModerationGateway provider, CircuitBreaker circuitBreaker, RateLimiter rateLimiter) {}

  @Override
  public ModerationResult check(Ad ad) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  @Override
  public boolean isAcceptingCalls() {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
