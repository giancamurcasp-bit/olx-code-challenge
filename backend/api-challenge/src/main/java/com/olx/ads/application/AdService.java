package com.olx.ads.application;

import com.olx.ads.application.port.AdRepository;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.Category;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** Creating and reading ads. Moderation happens asynchronously (DECISIONS.md, section 1). */
@Service
public class AdService {

  private final AdRepository repository;
  private final Clock clock;

  public AdService(AdRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /**
   * Creates an ad. A retry with the same idempotency key returns the ad created the first time
   * instead of a duplicate; reusing the key for different content is an error.
   */
  public Creation create(NewAd newAd, String idempotencyKey) {
    Ad ad =
        Ad.create(
            newAd.title(),
            newAd.description(),
            newAd.price(),
            newAd.category(),
            newAd.images(),
            clock.instant());
    return repository
        .insertWithIdempotencyKey(ad, idempotencyKey)
        .map(created -> new Creation(created, false))
        .orElseGet(() -> replay(ad, idempotencyKey));
  }

  private Creation replay(Ad requested, String idempotencyKey) {
    Ad existing = repository.findByIdempotencyKey(idempotencyKey).orElseThrow();
    if (!existing.hasSameContentAs(requested)) {
      throw new IdempotencyKeyReusedException();
    }
    return new Creation(existing, true);
  }

  public Ad get(long id) {
    return repository.findById(id).orElseThrow(() -> new AdNotFoundException(id));
  }

  /** The created ad, and whether it already existed (a replayed request). */
  public record Creation(Ad ad, boolean replayed) {}

  public record NewAd(
      String title, String description, BigDecimal price, Category category, List<String> images) {}
}
