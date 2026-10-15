package com.olx.ads.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.olx.ads.TestcontainersConfiguration;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import com.olx.ads.domain.ModerationResult;
import com.olx.ads.domain.RetryPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcAdRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final int ANY_LIMIT = 1_000; // other tests share the database

  @Autowired JdbcAdRepository repository;

  @Test
  void insertsANewAdAndReadsItBack() {
    Ad ad =
        Ad.create(
            "Bicicleta aro 29 Caloi",
            "Pouco usada, revisada.",
            new BigDecimal("1250.50"),
            Category.ESPORTES,
            List.of("https://example.com/1.jpg", "https://example.com/2.jpg"),
            NOW);

    Ad inserted = repository.insert(ad);
    Ad found = repository.findById(inserted.getId()).orElseThrow();

    assertThat(inserted.getId()).isNotNull();
    assertThat(found.getTitle()).isEqualTo("Bicicleta aro 29 Caloi");
    assertThat(found.getDescription()).isEqualTo("Pouco usada, revisada.");
    assertThat(found.getPrice()).isEqualByComparingTo("1250.50");
    assertThat(found.getCategory()).isEqualTo(Category.ESPORTES);
    assertThat(found.getImages())
        .containsExactly("https://example.com/1.jpg", "https://example.com/2.jpg");
    assertThat(found.getStatus()).isEqualTo(AdStatus.PENDING);
    assertThat(found.getModerationAttempts()).isZero();
    assertThat(found.getNextAttemptAt()).isEqualTo(NOW);
    assertThat(found.getCreatedAt()).isEqualTo(NOW);
    assertThat(found.getUpdatedAt()).isEqualTo(NOW);
  }

  @Test
  void returnsEmptyForUnknownId() {
    assertThat(repository.findById(Long.MAX_VALUE)).isEmpty();
  }

  // --- worker queue ---

  @Test
  void claimsDueAdsAndLeasesThem() {
    Ad ad = repository.insert(newAdCreatedAt(NOW));

    List<Ad> claimed = repository.claimDue(ANY_LIMIT, NOW, LEASE);

    assertThat(claimed).extracting(Ad::getId).contains(ad.getId());
    assertThat(repository.findById(ad.getId()).orElseThrow().getNextAttemptAt())
        .isEqualTo(NOW.plus(LEASE));
  }

  @Test
  void doesNotClaimAdsThatAreNotDueYet() {
    Ad ad = repository.insert(newAdCreatedAt(NOW.plusSeconds(3600)));

    assertThat(repository.claimDue(ANY_LIMIT, NOW, LEASE))
        .extracting(Ad::getId)
        .doesNotContain(ad.getId());
  }

  @Test
  void doesNotClaimAnAdAgainUntilItsLeaseExpires() {
    Ad ad = repository.insert(newAdCreatedAt(NOW));
    repository.claimDue(ANY_LIMIT, NOW, LEASE);

    assertThat(repository.claimDue(ANY_LIMIT, NOW.plusSeconds(10), LEASE))
        .extracting(Ad::getId)
        .doesNotContain(ad.getId());
    assertThat(repository.claimDue(ANY_LIMIT, NOW.plus(LEASE), LEASE))
        .extracting(Ad::getId)
        .contains(ad.getId());
  }

  @Test
  void updateStoresTheModerationOutcome() {
    Ad ad = repository.insert(newAdCreatedAt(NOW));
    ad.applyModeration(new ModerationResult.Rejected("prohibited_item"), POLICY, NOW);

    assertThat(repository.update(ad, NOW)).isTrue();

    Ad found = repository.findById(ad.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(AdStatus.REJECTED);
    assertThat(found.getRejectionReason()).isEqualTo("prohibited_item");
    assertThat(found.getModerationAttempts()).isEqualTo(1);
    assertThat(found.getNextAttemptAt()).isNull();
  }

  @Test
  void updateDoesNotOverwriteAnAdThatIsNoLongerPending() {
    Ad inserted = repository.insert(newAdCreatedAt(NOW));
    Ad firstCopy = repository.findById(inserted.getId()).orElseThrow();
    Ad lateCopy = repository.findById(inserted.getId()).orElseThrow();

    firstCopy.applyModeration(new ModerationResult.Approved(), POLICY, NOW);
    repository.update(firstCopy, NOW);
    lateCopy.applyModeration(new ModerationResult.Rejected("prohibited_item"), POLICY, NOW);

    assertThat(repository.update(lateCopy, NOW)).isFalse();
    assertThat(repository.findById(inserted.getId()).orElseThrow().getStatus())
        .isEqualTo(AdStatus.PUBLISHED);
  }

  @Test
  void updateIsDiscardedOnceAnotherWorkerTookOverTheExpiredLease() {
    Ad ad = repository.insert(newAdCreatedAt(NOW));
    Instant firstLease = NOW.plus(LEASE);
    Instant secondLease = firstLease.plus(LEASE);
    Ad slowWorkersCopy = claim(ad, NOW);
    Ad otherWorkersCopy = claim(ad, firstLease); // the first lease expired before its write

    slowWorkersCopy.applyModeration(new ModerationResult.Approved(), POLICY, firstLease);
    otherWorkersCopy.applyModeration(
        new ModerationResult.TransientFailure("http 500"), POLICY, firstLease);

    assertThat(repository.update(slowWorkersCopy, firstLease)).isFalse();
    assertThat(repository.update(otherWorkersCopy, secondLease)).isTrue();
    Ad found = repository.findById(ad.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(AdStatus.PENDING);
    assertThat(found.getLastModerationError()).isEqualTo("http 500");
  }

  private Ad claim(Ad ad, Instant now) {
    return repository.claimDue(ANY_LIMIT, now, LEASE).stream()
        .filter(claimed -> claimed.getId().equals(ad.getId()))
        .findFirst()
        .orElseThrow();
  }

  private static final RetryPolicy POLICY =
      new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(5), () -> 0.0);

  private static Ad newAdCreatedAt(Instant createdAt) {
    return Ad.create(
        "Bicicleta aro 29 Caloi",
        "Pouco usada, revisada.",
        new BigDecimal("1250.50"),
        Category.ESPORTES,
        List.of("https://example.com/1.jpg"),
        createdAt);
  }
}
