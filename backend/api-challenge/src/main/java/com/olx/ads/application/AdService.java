package com.olx.ads.application;

import com.olx.ads.application.port.AdRepository;
import com.olx.ads.application.port.PageCursor;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Creating and reading ads. Moderation happens asynchronously (DECISIONS.md, section 1). */
@Service
public class AdService {

  private static final Logger log = LoggerFactory.getLogger(AdService.class);

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
        .map(
            created -> {
              log.atInfo()
                  .addKeyValue("ad_id", String.valueOf(created.getId()))
                  .log("Ad created as pending");
              return new Creation(created, false);
            })
        .orElseGet(() -> replay(ad, idempotencyKey));
  }

  private Creation replay(Ad requested, String idempotencyKey) {
    Ad existing = repository.findByIdempotencyKey(idempotencyKey).orElseThrow();
    if (!existing.hasSameContentAs(requested)) {
      log.atWarn()
          .addKeyValue("ad_id", String.valueOf(existing.getId()))
          .log("Idempotency-Key reused for a different ad; rejected");
      throw new IdempotencyKeyReusedException();
    }
    log.atInfo()
        .addKeyValue("ad_id", String.valueOf(existing.getId()))
        .log("Idempotent replay of ad creation");
    return new Creation(existing, true);
  }

  /**
   * Returns one page of ads, newest first, and the cursor of the next page (null on the last one).
   *
   * @param category optional; null lists every category
   * @param after optional; null returns the first page
   */
  public AdPage list(AdStatus status, Category category, PageCursor after, int limit) {
    return new AdPage(List.of(), null); // not implemented yet
  }

  public Ad get(long id) {
    return repository.findById(id).orElseThrow(() -> new AdNotFoundException(id));
  }

  /** A page of ads; {@code next} is null on the last page. */
  public record AdPage(List<Ad> ads, PageCursor next) {}

  /** The created ad, and whether it already existed (a replayed request). */
  public record Creation(Ad ad, boolean replayed) {}

  public record NewAd(
      String title, String description, BigDecimal price, Category category, List<String> images) {}
}
