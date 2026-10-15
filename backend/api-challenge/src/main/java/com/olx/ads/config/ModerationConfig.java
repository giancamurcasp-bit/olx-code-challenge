package com.olx.ads.config;

import com.olx.ads.application.ModerationService;
import com.olx.ads.application.port.AdRepository;
import com.olx.ads.application.port.ModerationGateway;
import com.olx.ads.domain.RetryPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ModerationConfig {

  @Bean
  RetryPolicy retryPolicy() {
    return RetryPolicy.defaults();
  }

  @Bean
  ModerationService moderationService(
      AdRepository repository,
      ModerationGateway gateway,
      Clock clock,
      RetryPolicy retryPolicy,
      ModerationWorkerProperties properties) {
    return new ModerationService(
        repository,
        gateway,
        clock,
        retryPolicy,
        properties.batchSize(),
        properties.lease(),
        properties.concurrency());
  }
}
