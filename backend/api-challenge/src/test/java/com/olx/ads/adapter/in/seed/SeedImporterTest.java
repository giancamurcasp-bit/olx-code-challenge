package com.olx.ads.adapter.in.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.olx.ads.TestcontainersConfiguration;
import com.olx.ads.application.port.AdRepository;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Runs in its own context (and database), where the import ran at startup. */
@SpringBootTest(properties = "seed.enabled=true")
@Import(TestcontainersConfiguration.class)
class SeedImporterTest {

  private static final int SEED_ADS = 57;

  @Autowired SeedImporter importer;
  @Autowired AdRepository repository;
  @Autowired JdbcClient jdbc;

  @Test
  void importsEverySeedAdAsPendingSoItGoesThroughModeration() {
    assertThat(countAds()).isEqualTo(SEED_ADS);
    assertThat(
            jdbc.sql("SELECT count(*) FROM ads WHERE status <> 'pending'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void keepsTheSeedDataAndCreationDate() {
    Ad iphone = seedAd(1);

    assertThat(iphone.getTitle()).isEqualTo("iPhone 13 128GB");
    assertThat(iphone.getPrice()).isEqualByComparingTo("80");
    assertThat(iphone.getCategory()).isEqualTo(Category.ELETRONICOS);
    assertThat(iphone.getCreatedAt()).isEqualTo(Instant.parse("2026-06-03T12:00:00Z"));
    assertThat(iphone.getStatus()).isEqualTo(AdStatus.PENDING);
  }

  @Test
  void givesEachAdADeterministicPlaceholderImage() {
    assertThat(seedAd(1).getImages()).containsExactly("https://example.com/seed/ads/1.jpg");
  }

  @Test
  void runningAgainImportsNothingNew() throws Exception {
    importer.run(new DefaultApplicationArguments());

    assertThat(countAds()).isEqualTo(SEED_ADS);
  }

  private Ad seedAd(int seedId) {
    return repository.findByIdempotencyKey("seed:" + seedId).orElseThrow();
  }

  private long countAds() {
    return jdbc.sql("SELECT count(*) FROM ads").query(Long.class).single();
  }
}
