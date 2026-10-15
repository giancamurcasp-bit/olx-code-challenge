package com.olx.ads.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.olx.ads.TestcontainersConfiguration;
import com.olx.ads.application.port.PageCursor;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import com.olx.ads.domain.ModerationResult;
import com.olx.ads.domain.RetryPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Keyset pagination against real PostgreSQL (DECISIONS.md, section 4). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcAdRepositoryListingTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  private static final RetryPolicy POLICY =
      new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(5), () -> 0.0);

  @Autowired JdbcAdRepository repository;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE ads").update(); // the listing must only see this test's ads
  }

  @Test
  void listsNewestFirstWithTheIdBreakingTies() {
    Ad old = published(Category.MOVEIS, NOW.minusSeconds(60));
    Ad tieFirst = published(Category.MOVEIS, NOW);
    Ad tieSecond = published(Category.MOVEIS, NOW); // same created_at, higher id

    assertThat(repository.findPage(AdStatus.PUBLISHED, null, null, 10))
        .extracting(Ad::getId)
        .containsExactly(tieSecond.getId(), tieFirst.getId(), old.getId());
  }

  @Test
  void continuesRightAfterTheCursorEvenWithinTies() {
    Ad a = published(Category.MOVEIS, NOW);
    Ad b = published(Category.MOVEIS, NOW);
    Ad c = published(Category.MOVEIS, NOW);
    Ad d = published(Category.MOVEIS, NOW.minusSeconds(1));

    List<Ad> next = repository.findPage(AdStatus.PUBLISHED, null, cursorOf(b), 10);

    assertThat(c.getId()).isGreaterThan(b.getId());
    assertThat(next).extracting(Ad::getId).containsExactly(a.getId(), d.getId());
  }

  @Test
  void respectsTheLimit() {
    published(Category.MOVEIS, NOW);
    published(Category.MOVEIS, NOW.minusSeconds(1));
    published(Category.MOVEIS, NOW.minusSeconds(2));

    assertThat(repository.findPage(AdStatus.PUBLISHED, null, null, 2)).hasSize(2);
  }

  @Test
  void filtersByStatus() {
    Ad publishedAd = published(Category.MOVEIS, NOW);
    Ad pendingAd = repository.insert(newAd(Category.MOVEIS, NOW));

    assertThat(repository.findPage(AdStatus.PUBLISHED, null, null, 10))
        .extracting(Ad::getId)
        .containsExactly(publishedAd.getId());
    assertThat(repository.findPage(AdStatus.PENDING, null, null, 10))
        .extracting(Ad::getId)
        .containsExactly(pendingAd.getId());
  }

  @Test
  void filtersByCategory() {
    Ad sofa = published(Category.MOVEIS, NOW);
    published(Category.ELETRONICOS, NOW);

    assertThat(repository.findPage(AdStatus.PUBLISHED, Category.MOVEIS, null, 10))
        .extracting(Ad::getId)
        .containsExactly(sofa.getId());
  }

  // --- helpers ---

  private Ad published(Category category, Instant createdAt) {
    Ad ad = repository.insert(newAd(category, createdAt));
    ad.applyModeration(new ModerationResult.Approved(), POLICY, createdAt);
    repository.update(ad, createdAt);
    return ad;
  }

  private static PageCursor cursorOf(Ad ad) {
    return new PageCursor(ad.getCreatedAt(), ad.getId());
  }

  private static Ad newAd(Category category, Instant createdAt) {
    return Ad.create(
        "Anúncio de teste aqui",
        "Descrição.",
        new BigDecimal("100.00"),
        category,
        List.of("https://example.com/a.jpg"),
        createdAt);
  }
}
