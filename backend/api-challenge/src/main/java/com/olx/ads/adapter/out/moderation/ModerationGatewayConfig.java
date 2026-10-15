package com.olx.ads.adapter.out.moderation;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The moderation port is the HTTP gateway wrapped with rate limiting and circuit breaking. */
@Configuration(proxyBeanMethods = false)
class ModerationGatewayConfig {

  private static final Logger log = LoggerFactory.getLogger(ModerationGatewayConfig.class);

  private static final String INSTANCE = "moderation"; // resilience4j.*.instances.moderation

  @Bean
  ResilientModerationGateway moderationGateway(
      RestClient.Builder restClientBuilder,
      ModerationProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      RateLimiterRegistry rateLimiters) {
    CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(INSTANCE);
    // Opening and closing are the key operational events: the worker stops or resumes claiming.
    circuitBreaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                log.warn(
                    "Moderation circuit breaker {} -> {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));
    return new ResilientModerationGateway(
        new HttpModerationGateway(restClientBuilder, properties),
        circuitBreaker,
        rateLimiters.rateLimiter(INSTANCE));
  }
}
