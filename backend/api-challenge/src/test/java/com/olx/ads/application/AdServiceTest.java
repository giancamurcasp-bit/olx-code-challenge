package com.olx.ads.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.olx.ads.application.AdService.AdPage;
import com.olx.ads.application.AdService.Creation;
import com.olx.ads.application.AdService.NewAd;
import com.olx.ads.application.port.AdRepository;
import com.olx.ads.application.port.PageCursor;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import com.olx.ads.domain.InvalidAdException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests with an in-memory repository; the real one is covered by JdbcAdRepositoryTest. */
class AdServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  private static final String KEY = "6f1c2b3a-key";

  private final InMemoryAdRepository repository = new InMemoryAdRepository();
  private final AdService service = new AdService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

  // --- create ---

  @Test
  void createsAPendingAdStampedWithTheCurrentTime() {
    Creation creation = service.create(newAd(), KEY);

    Ad ad = creation.ad();
    assertThat(creation.replayed()).isFalse();
    assertThat(ad.getId()).isNotNull();
    assertThat(ad.getStatus()).isEqualTo(AdStatus.PENDING);
    assertThat(ad.getCreatedAt()).isEqualTo(NOW);
    assertThat(ad.getNextAttemptAt()).isEqualTo(NOW); // due for moderation immediately
    assertThat(repository.ads).hasSize(1);
  }

  @Test
  void invalidAdIsNotStored() {
    NewAd invalid =
        new NewAd("Curto", "Descrição.", new BigDecimal("10.00"), Category.MOVEIS, List.of(IMAGE));

    assertThatThrownBy(() -> service.create(invalid, KEY)).isInstanceOf(InvalidAdException.class);
    assertThat(repository.ads).isEmpty();
  }

  // --- idempotency key ---

  @Test
  void retryWithTheSameKeyReturnsTheOriginalAdWithoutADuplicate() {
    Ad original = service.create(newAd(), KEY).ad();

    Creation retry = service.create(newAd(), KEY);

    assertThat(retry.replayed()).isTrue();
    assertThat(retry.ad().getId()).isEqualTo(original.getId());
    assertThat(repository.ads).hasSize(1);
  }

  @Test
  void retryWithTheSameContentWrittenDifferentlyIsStillAReplay() {
    service.create(newAd(), KEY);
    NewAd sameAd =
        new NewAd(
            "  iPhone 13 128GB azul ",
            "Em ótimo estado.",
            new BigDecimal("3500"),
            Category.ELETRONICOS,
            List.of(IMAGE));

    assertThat(service.create(sameAd, KEY).replayed()).isTrue();
  }

  @Test
  void reusingAKeyForADifferentAdIsRejected() {
    service.create(newAd(), KEY);
    NewAd otherAd =
        new NewAd(
            "iPhone 13 128GB azul",
            "Em ótimo estado.",
            new BigDecimal("3400.00"),
            Category.ELETRONICOS,
            List.of(IMAGE));

    assertThatThrownBy(() -> service.create(otherAd, KEY))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    assertThat(repository.ads).hasSize(1);
  }

  @Test
  void differentKeysCreateDifferentAds() {
    Ad first = service.create(newAd(), "key-1").ad();
    Ad second = service.create(newAd(), "key-2").ad();

    assertThat(first.getId()).isNotEqualTo(second.getId());
    assertThat(repository.ads).hasSize(2);
  }

  @Test
  void invalidAdIsRejectedEvenWithAKeyThatWasAlreadyUsed() {
    service.create(newAd(), KEY);
    NewAd invalid =
        new NewAd("Curto", "Descrição.", new BigDecimal("10.00"), Category.MOVEIS, List.of(IMAGE));

    assertThatThrownBy(() -> service.create(invalid, KEY)).isInstanceOf(InvalidAdException.class);
  }

  // --- list ---

  @Test
  void pageWithMoreAdsAfterItPointsToTheNextPage() {
    Ad newest = createPendingAd("key-3", NOW);
    Ad middle = createPendingAd("key-2", NOW.minusSeconds(1));
    Ad oldest = createPendingAd("key-1", NOW.minusSeconds(2));

    AdPage first = service.list(AdStatus.PENDING, null, null, 2);
    AdPage second = service.list(AdStatus.PENDING, null, first.next(), 2);

    assertThat(first.ads()).extracting(Ad::getId).containsExactly(newest.getId(), middle.getId());
    assertThat(first.next()).isEqualTo(new PageCursor(middle.getCreatedAt(), middle.getId()));
    assertThat(second.ads()).extracting(Ad::getId).containsExactly(oldest.getId());
    assertThat(second.next()).isNull();
  }

  @Test
  void pageThatExactlyFillsTheLimitHasNoNextPage() {
    createPendingAd("key-1", NOW);
    createPendingAd("key-2", NOW.minusSeconds(1));

    AdPage page = service.list(AdStatus.PENDING, null, null, 2);

    assertThat(page.ads()).hasSize(2);
    assertThat(page.next()).isNull();
  }

  // --- get ---

  @Test
  void getsAnAdById() {
    Ad created = service.create(newAd(), KEY).ad();

    assertThat(service.get(created.getId()).getTitle()).isEqualTo("iPhone 13 128GB azul");
  }

  @Test
  void unknownAdIsNotFound() {
    assertThatThrownBy(() -> service.get(42))
        .isInstanceOf(AdNotFoundException.class)
        .hasMessage("ad 42 not found");
  }

  // --- helpers ---

  private static final String IMAGE = "https://example.com/iphone.jpg";

  private Ad createPendingAd(String key, Instant createdAt) {
    var atTime = new AdService(repository, Clock.fixed(createdAt, ZoneOffset.UTC));
    return atTime.create(newAd(), key).ad();
  }

  private static NewAd newAd() {
    return new NewAd(
        "iPhone 13 128GB azul",
        "Em ótimo estado.",
        new BigDecimal("3500.00"),
        Category.ELETRONICOS,
        List.of(IMAGE));
  }

  /** Stores ads in memory, assigning ids like the database does. Only what AdService uses. */
  static class InMemoryAdRepository implements AdRepository {

    final List<Ad> ads = new ArrayList<>();
    private final Map<String, Ad> byIdempotencyKey = new HashMap<>();

    @Override
    public Ad insert(Ad ad) {
      Ad stored =
          Ad.restore(
              ads.size() + 1L,
              ad.getTitle(),
              ad.getDescription(),
              ad.getPrice(),
              ad.getCategory(),
              ad.getImages(),
              ad.getStatus(),
              ad.getRejectionReason(),
              ad.getModerationAttempts(),
              ad.getNextAttemptAt(),
              ad.getLastModerationError(),
              ad.getCreatedAt(),
              ad.getUpdatedAt());
      ads.add(stored);
      return stored;
    }

    @Override
    public Optional<Ad> insertWithIdempotencyKey(Ad ad, String idempotencyKey) {
      if (byIdempotencyKey.containsKey(idempotencyKey)) {
        return Optional.empty();
      }
      Ad stored = insert(ad);
      byIdempotencyKey.put(idempotencyKey, stored);
      return Optional.of(stored);
    }

    @Override
    public Optional<Ad> findById(long id) {
      return ads.stream().filter(ad -> ad.getId() == id).findFirst();
    }

    @Override
    public Optional<Ad> findByIdempotencyKey(String idempotencyKey) {
      return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
    }

    @Override
    public List<Ad> findPage(AdStatus status, Category category, PageCursor after, int limit) {
      Comparator<Ad> newestFirst =
          Comparator.comparing(Ad::getCreatedAt).thenComparing(Ad::getId).reversed();
      return ads.stream()
          .filter(ad -> ad.getStatus() == status)
          .filter(ad -> category == null || ad.getCategory() == category)
          .filter(
              ad ->
                  after == null
                      || ad.getCreatedAt().isBefore(after.createdAt())
                      || (ad.getCreatedAt().equals(after.createdAt()) && ad.getId() < after.id()))
          .sorted(newestFirst)
          .limit(limit)
          .toList();
    }

    @Override
    public List<Ad> claimDue(int limit, Instant now, Duration lease) {
      throw new UnsupportedOperationException("not used by AdService");
    }

    @Override
    public boolean update(Ad ad, Instant leasedUntil) {
      throw new UnsupportedOperationException("not used by AdService");
    }
  }
}
