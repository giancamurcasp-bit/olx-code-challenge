package com.olx.ads.adapter.out.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.olx.ads.application.port.ModerationGateway;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.Category;
import com.olx.ads.domain.ModerationResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ResilientModerationGatewayTest {

  private static final int WINDOW = 4;

  private final Ad ad =
      Ad.create(
          "Sofá 3 lugares retrátil",
          "Tecido suede, ótimo estado.",
          new BigDecimal("1200.50"),
          Category.MOVEIS,
          List.of("https://example.com/sofa.jpg"),
          Instant.parse("2026-10-15T12:00:00Z"));

  private final CircuitBreaker circuitBreaker =
      CircuitBreaker.of(
          "moderation",
          CircuitBreakerConfig.custom()
              .slidingWindowSize(WINDOW)
              .minimumNumberOfCalls(WINDOW)
              .failureRateThreshold(50)
              .waitDurationInOpenState(Duration.ofMinutes(1))
              .build());

  private final RateLimiter unlimited =
      RateLimiter.of(
          "moderation",
          RateLimiterConfig.custom()
              .limitForPeriod(1_000)
              .limitRefreshPeriod(Duration.ofSeconds(1))
              .timeoutDuration(Duration.ZERO)
              .build());

  @Test
  void passesTheProviderResultThroughWhenHealthy() {
    var provider = new StubProvider(new ModerationResult.Rejected("prohibited_item"));

    assertThat(gateway(provider, unlimited).check(ad))
        .isEqualTo(new ModerationResult.Rejected("prohibited_item"));
  }

  @Test
  void transientFailuresOpenTheCircuit() {
    var gateway =
        gateway(new StubProvider(new ModerationResult.TransientFailure("http 500")), unlimited);

    callTimes(gateway, WINDOW);

    assertThat(gateway.isAcceptingCalls()).isFalse();
  }

  @Test
  void otherOutcomesDoNotOpenTheCircuit() {
    var provider =
        new StubProvider(
            new ModerationResult.Rejected("prohibited_item"),
            new ModerationResult.PermanentFailure("http 400"),
            new ModerationResult.RateLimited(Duration.ofSeconds(1)),
            new ModerationResult.Approved());
    var gateway = gateway(provider, unlimited);

    callTimes(gateway, WINDOW);

    assertThat(gateway.isAcceptingCalls()).isTrue();
  }

  @Test
  void openCircuitDefersTheAdWithoutCallingTheProvider() {
    var provider = new StubProvider(new ModerationResult.Approved());
    circuitBreaker.transitionToOpenState();

    ModerationResult result = gateway(provider, unlimited).check(ad);

    assertThat(result).isInstanceOf(ModerationResult.RateLimited.class);
    assertThat(provider.calls).isZero();
  }

  @Test
  void withoutARateLimitPermitTheAdIsDeferredWithoutCallingTheProvider() {
    var provider = new StubProvider(new ModerationResult.Approved());
    RateLimiter onePerMinute =
        RateLimiter.of(
            "moderation",
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build());
    var gateway = gateway(provider, onePerMinute);

    gateway.check(ad);
    ModerationResult second = gateway.check(ad);

    assertThat(second).isInstanceOf(ModerationResult.RateLimited.class);
    assertThat(provider.calls).isEqualTo(1);
  }

  @Test
  void concurrentCallsNeverExceedTheProvidersLimitInAnySecond() throws Exception {
    // The production setting: 1 permit every 250ms. The provider rejects a 6th call within any
    // sliding second; a "4 per 1s period" limiter let 8 through around a period boundary.
    RateLimiter production =
        RateLimiter.of(
            "moderation",
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMillis(250))
                .timeoutDuration(Duration.ofSeconds(5))
                .build());
    var provider = new TimestampingProvider();
    var gateway = gateway(provider, production);

    try (var threads = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory())) {
      for (int i = 0; i < 12; i++) {
        threads.submit(() -> gateway.check(ad));
      }
    }

    assertThat(provider.callTimes).hasSize(12);
    assertThat(maxCallsInAnySecond(provider.callTimes)).isLessThanOrEqualTo(5);
  }

  // --- helpers ---

  private static int maxCallsInAnySecond(List<Long> nanoTimes) {
    List<Long> sorted = nanoTimes.stream().sorted().toList();
    int max = 0;
    for (int start = 0; start < sorted.size(); start++) {
      int end = start;
      while (end < sorted.size()
          && sorted.get(end) - sorted.get(start) < Duration.ofSeconds(1).toNanos()) {
        end++;
      }
      max = Math.max(max, end - start);
    }
    return max;
  }

  /** Records when each call reached the provider; approves everything. */
  static class TimestampingProvider implements ModerationGateway {

    final List<Long> callTimes = new CopyOnWriteArrayList<>();

    @Override
    public ModerationResult check(Ad ad) {
      callTimes.add(System.nanoTime());
      return new ModerationResult.Approved();
    }
  }

  private ResilientModerationGateway gateway(ModerationGateway provider, RateLimiter rateLimiter) {
    return new ResilientModerationGateway(provider, circuitBreaker, rateLimiter);
  }

  private void callTimes(ModerationGateway gateway, int times) {
    for (int i = 0; i < times; i++) {
      gateway.check(ad);
    }
  }

  /** Returns the given results in order, repeating the last one. */
  static class StubProvider implements ModerationGateway {

    private final List<ModerationResult> results;
    int calls;

    StubProvider(ModerationResult... results) {
      this.results = List.of(results);
    }

    @Override
    public ModerationResult check(Ad ad) {
      return results.get(Math.min(calls++, results.size() - 1));
    }
  }
}
