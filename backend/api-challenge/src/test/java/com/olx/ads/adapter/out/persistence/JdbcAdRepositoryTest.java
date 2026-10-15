package com.olx.ads.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.olx.ads.TestcontainersConfiguration;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.AdStatus;
import com.olx.ads.domain.Category;
import java.math.BigDecimal;
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
}
