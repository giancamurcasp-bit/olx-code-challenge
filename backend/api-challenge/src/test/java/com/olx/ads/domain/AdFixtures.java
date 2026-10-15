package com.olx.ads.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class AdFixtures {

  static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  static final String TITLE = "iPhone 13 128GB azul";
  static final String DESCRIPTION = "Em ótimo estado, com caixa e nota fiscal.";
  static final BigDecimal PRICE = new BigDecimal("3500.00");
  static final Category CATEGORY = Category.ELETRONICOS;
  static final List<String> IMAGES = List.of("https://example.com/iphone.jpg");

  /** Small cap and no jitter, so retry tests are short and deterministic. */
  static final RetryPolicy POLICY =
      new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(5), () -> 0.0);

  static Ad pendingAd() {
    return Ad.create(TITLE, DESCRIPTION, PRICE, CATEGORY, IMAGES, NOW);
  }

  private AdFixtures() {}
}
