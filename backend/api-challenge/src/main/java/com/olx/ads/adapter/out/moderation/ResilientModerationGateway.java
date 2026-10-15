package com.olx.ads.adapter.out.moderation;

import com.olx.ads.application.port.ModerationGateway;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.ModerationResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Protects the provider and ourselves (DECISIONS.md, section 3): a client-side rate limiter keeps
 * us under the provider's 5 req/s, and a circuit breaker stops calls while it is failing. A call
 * that doesn't go out is returned as {@link ModerationResult.RateLimited}: "not now", rescheduled
 * without counting an attempt.
 */
public class ResilientModerationGateway implements ModerationGateway {

  private static final Logger log = LoggerFactory.getLogger(ResilientModerationGateway.class);

  /**
   * How long a call that didn't go out is postponed. Short on purpose: while the circuit stays open
   * the worker doesn't claim ads at all (see {@link #isAcceptingCalls()}).
   */
  private static final Duration DEFER = Duration.ofSeconds(1);

  private final ModerationGateway provider;
  private final CircuitBreaker circuitBreaker;
  private final RateLimiter rateLimiter;

  public ResilientModerationGateway(
      ModerationGateway provider, CircuitBreaker circuitBreaker, RateLimiter rateLimiter) {
    this.provider = provider;
    this.circuitBreaker = circuitBreaker;
    this.rateLimiter = rateLimiter;
  }

  @Override
  public ModerationResult check(Ad ad) {
    if (!circuitBreaker.tryAcquirePermission()) {
      log.debug("Call not sent: circuit breaker {}", circuitBreaker.getState());
      return new ModerationResult.RateLimited(DEFER);
    }
    if (!rateLimiter.acquirePermission()) {
      circuitBreaker.releasePermission();
      log.warn("Call not sent: no rate-limit permit in time; too many calls waiting");
      return new ModerationResult.RateLimited(DEFER);
    }
    long start = System.nanoTime();
    ModerationResult result;
    try {
      result = provider.check(ad);
    } catch (RuntimeException e) { // the port says it never throws; don't leak the permission
      circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
      throw e;
    }
    long elapsed = System.nanoTime() - start;
    if (result instanceof ModerationResult.TransientFailure failure) {
      circuitBreaker.onError(elapsed, TimeUnit.NANOSECONDS, new ProviderFailure(failure.error()));
    } else {
      circuitBreaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
    }
    return result;
  }

  @Override
  public boolean isAcceptingCalls() {
    return circuitBreaker.getState() != CircuitBreaker.State.OPEN
        && circuitBreaker.getState() != CircuitBreaker.State.FORCED_OPEN;
  }

  /** Lets the circuit breaker record a failure that the provider reported as a result. */
  private static class ProviderFailure extends RuntimeException {
    ProviderFailure(String message) {
      super(message, null, false, false);
    }
  }
}
