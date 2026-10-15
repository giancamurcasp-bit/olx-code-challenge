package com.olx.ads.adapter.out.moderation;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The moderation port is the HTTP gateway wrapped with rate limiting and circuit breaking. */
@Configuration(proxyBeanMethods = false)
class ModerationGatewayConfig {

  private static final String INSTANCE = "moderation"; // resilience4j.*.instances.moderation

  @Bean
  ResilientModerationGateway moderationGateway(
      RestClient.Builder restClientBuilder,
      ModerationProperties properties,
      CircuitBreakerRegistry circuitBreakers,
      RateLimiterRegistry rateLimiters) {
    return new ResilientModerationGateway(
        new HttpModerationGateway(restClientBuilder, properties),
        circuitBreakers.circuitBreaker(INSTANCE),
        rateLimiters.rateLimiter(INSTANCE));
  }
}
