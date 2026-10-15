package com.olx.ads.adapter.in.seed;

import com.olx.ads.application.AdService;
import com.olx.ads.application.AdService.NewAd;
import com.olx.ads.domain.Category;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports the mock's 57 example ads at startup (DECISIONS.md, section 7). They go through {@link
 * AdService} like any ad: validated, stored as {@code pending} and moderated by the worker. The key
 * {@code seed:<id>} makes the import idempotent, so restarts don't duplicate them.
 *
 * <p>{@code seed/ads.json} is an unmodified copy of {@code api-mock/seed/ads.json}: the Docker
 * image can't read the mock's folder.
 */
@Component
@ConditionalOnProperty(name = "seed.enabled", havingValue = "true")
class SeedImporter implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedImporter.class);
  private static final String SEED_FILE = "seed/ads.json";

  private final AdService adService;
  private final JsonMapper jsonMapper;

  SeedImporter(AdService adService, JsonMapper jsonMapper) {
    this.adService = adService;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void run(ApplicationArguments args) throws IOException {
    SeedFile seed;
    try (InputStream in = new ClassPathResource(SEED_FILE).getInputStream()) {
      seed = jsonMapper.readValue(in, SeedFile.class);
    }
    int imported = 0;
    int skipped = 0;
    for (SeedAd ad : seed.ads()) {
      try {
        if (!adService.create(ad.toNewAd(), "seed:" + ad.id(), ad.createdAt()).replayed()) {
          imported++;
        }
      } catch (RuntimeException e) { // one bad entry must not stop the import or the app
        skipped++;
        log.warn("Seed ad {} skipped: {}", ad.id(), e.getMessage());
      }
    }
    log.info(
        "Seed import: {} imported, {} already present, {} skipped",
        imported,
        seed.ads().size() - imported - skipped,
        skipped);
  }

  record SeedFile(List<SeedAd> ads) {}

  /** The seed has no images, so each ad gets a placeholder rather than weakening the rule. */
  record SeedAd(
      long id,
      String title,
      String description,
      BigDecimal price,
      String category,
      Instant createdAt) {

    NewAd toNewAd() {
      return new NewAd(
          title,
          description,
          price,
          Category.fromValue(category),
          List.of("https://example.com/seed/ads/" + id + ".jpg"));
    }
  }
}
