package com.olx.ads.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ClockConfig {

  /** Injected wherever "now" is needed, so time can be controlled in tests. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
