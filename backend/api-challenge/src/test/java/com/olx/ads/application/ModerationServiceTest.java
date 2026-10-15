package com.olx.ads.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.olx.ads.TestcontainersConfiguration;
import com.olx.ads.application.port.AdRepository;
import com.olx.ads.application.port.ModerationGateway;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import com.olx.ads.domain.ModerationResult;
import com.olx.ads.domain.RetryPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Real PostgreSQL, fake moderation provider: covers the claim → moderate → save cycle. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ModerationServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  private static final RetryPolicy POLICY =
      new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(5), () -> 0.0);

  @Autowired AdRepository repository;
  @Autowired JdbcClient jdbc;

  private final FakeModerationGateway gateway = new FakeModerationGateway();
  private ModerationService service;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE ads").update(); // the batch must only see this test's ads
    service =
        new ModerationService(
            repository,
            gateway,
            Clock.fixed(NOW, ZoneOffset.UTC),
            POLICY,
            10,
            Duration.ofSeconds(30));
  }

  @Test
  void approvedAdIsPublished() {
    Ad ad = insertDueAd("Sofá 3 lugares retrátil");
    gateway.answer(ad, new ModerationResult.Approved());

    service.moderateDueAds();

    Ad found = find(ad);
    assertThat(found.getStatus()).isEqualTo(AdStatus.PUBLISHED);
    assertThat(found.getModerationAttempts()).isEqualTo(1);
    assertThat(found.getNextAttemptAt()).isNull();
  }

  @Test
  void rejectedAdKeepsTheReason() {
    Ad ad = insertDueAd("Celular chama no whatsapp");
    gateway.answer(ad, new ModerationResult.Rejected("contact_info_not_allowed"));

    service.moderateDueAds();

    assertThat(find(ad).getStatus()).isEqualTo(AdStatus.REJECTED);
    assertThat(find(ad).getRejectionReason()).isEqualTo("contact_info_not_allowed");
  }

  @Test
  void transientFailureKeepsTheAdPendingAndReschedulesIt() {
    Ad ad = insertDueAd("Sofá 3 lugares retrátil");
    gateway.answer(ad, new ModerationResult.TransientFailure("http 500"));

    service.moderateDueAds();

    Ad found = find(ad);
    assertThat(found.getStatus()).isEqualTo(AdStatus.PENDING);
    assertThat(found.getModerationAttempts()).isEqualTo(1);
    assertThat(found.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
    assertThat(found.getLastModerationError()).isEqualTo("http 500");
  }

  @Test
  void adsThatAreNotDueAreNotModerated() {
    Ad ad = repository.insert(newAd("Sofá 3 lugares retrátil", NOW.plusSeconds(3600)));

    service.moderateDueAds();

    assertThat(gateway.calls).isEmpty();
    assertThat(find(ad).getStatus()).isEqualTo(AdStatus.PENDING);
  }

  @Test
  void claimsNothingWhileTheProviderIsNotAcceptingCalls() {
    Ad ad = insertDueAd("Sofá 3 lugares retrátil");
    gateway.acceptingCalls = false;

    service.moderateDueAds();

    Ad found = find(ad);
    assertThat(gateway.calls).isEmpty();
    assertThat(found.getModerationAttempts()).isZero();
    assertThat(found.getNextAttemptAt()).isEqualTo(NOW); // not leased: still due
  }

  @Test
  void anUnexpectedErrorOnOneAdDoesNotStopTheBatch() {
    Ad broken = insertDueAd("Anúncio que quebra o gateway");
    Ad healthy = insertDueAd("Sofá 3 lugares retrátil");
    gateway.failOn(broken);
    gateway.answer(healthy, new ModerationResult.Approved());

    service.moderateDueAds();

    assertThat(find(healthy).getStatus()).isEqualTo(AdStatus.PUBLISHED);
    assertThat(find(broken).getStatus()).isEqualTo(AdStatus.PENDING); // retried after the lease
  }

  // --- helpers ---

  private Ad insertDueAd(String title) {
    return repository.insert(newAd(title, NOW));
  }

  private Ad find(Ad ad) {
    return repository.findById(ad.getId()).orElseThrow();
  }

  private static Ad newAd(String title, Instant createdAt) {
    return Ad.create(
        title,
        "Descrição do anúncio.",
        new BigDecimal("1200.00"),
        Category.MOVEIS,
        List.of("https://example.com/a.jpg"),
        createdAt);
  }

  /** Answers per ad id; records calls. Approves by default. */
  static class FakeModerationGateway implements ModerationGateway {

    final List<Long> calls = new ArrayList<>();
    private final Map<Long, ModerationResult> answers = new HashMap<>();
    private Long failingId;
    boolean acceptingCalls = true;

    @Override
    public boolean isAcceptingCalls() {
      return acceptingCalls;
    }

    void answer(Ad ad, ModerationResult result) {
      answers.put(ad.getId(), result);
    }

    void failOn(Ad ad) {
      failingId = ad.getId();
    }

    @Override
    public ModerationResult check(Ad ad) {
      calls.add(ad.getId());
      if (ad.getId().equals(failingId)) {
        throw new IllegalStateException("simulated bug");
      }
      return answers.getOrDefault(ad.getId(), new ModerationResult.Approved());
    }
  }
}
