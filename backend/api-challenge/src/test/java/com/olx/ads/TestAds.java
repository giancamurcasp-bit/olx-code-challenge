package com.olx.ads;

import com.olx.ads.application.port.AdRepository;
import com.olx.ads.domain.Ad;
import java.util.UUID;

/** Stores ads directly for tests that need them in the database before acting. */
public final class TestAds {

  private TestAds() {}

  /** Stores the ad under a fresh idempotency key, as every stored ad has one. */
  public static Ad store(AdRepository repository, Ad ad) {
    return repository.insertWithIdempotencyKey(ad, UUID.randomUUID().toString()).orElseThrow();
  }
}
