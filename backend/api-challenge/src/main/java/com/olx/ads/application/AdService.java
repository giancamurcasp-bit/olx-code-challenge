package com.olx.ads.application;

import com.olx.ads.application.port.AdRepository;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.Category;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** Creating ads. Moderation happens asynchronously (DECISIONS.md, section 1). */
@Service
public class AdService {

  private final AdRepository repository;
  private final Clock clock;

  public AdService(AdRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public Ad create(NewAd newAd) {
    Ad ad =
        Ad.create(
            newAd.title(),
            newAd.description(),
            newAd.price(),
            newAd.category(),
            newAd.images(),
            clock.instant());
    return repository.insert(ad);
  }

  public record NewAd(
      String title, String description, BigDecimal price, Category category, List<String> images) {}
}
