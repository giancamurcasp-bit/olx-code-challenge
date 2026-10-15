package com.olx.ads.adapter.in.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stub: implemented in the next commit. */
@Component
@ConditionalOnProperty(name = "seed.enabled", havingValue = "true")
class SeedImporter implements ApplicationRunner {

  @Override
  public void run(ApplicationArguments args) {}
}
