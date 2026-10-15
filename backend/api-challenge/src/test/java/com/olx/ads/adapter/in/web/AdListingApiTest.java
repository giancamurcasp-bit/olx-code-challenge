package com.olx.ads.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.olx.ads.TestcontainersConfiguration;
import com.olx.ads.application.port.AdRepository;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.Category;
import com.olx.ads.domain.ModerationResult;
import com.olx.ads.domain.RetryPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdListingApiTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
  private static final RetryPolicy POLICY =
      new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(5), () -> 0.0);

  @Autowired MockMvc mockMvc;
  @Autowired AdRepository repository;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void setUp() {
    jdbc.sql("TRUNCATE ads").update(); // the listing must only see this test's ads
  }

  @Test
  void listsOnlyPublishedAdsByDefault() throws Exception {
    Ad published = moderated(Category.MOVEIS, NOW, new ModerationResult.Approved());
    moderated(Category.MOVEIS, NOW, new ModerationResult.Rejected("prohibited_item"));
    repository.insert(newAd(Category.MOVEIS, NOW)); // pending

    mockMvc
        .perform(get("/ads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value(published.getId()))
        .andExpect(jsonPath("$.data[0].status").value("published"))
        .andExpect(jsonPath("$.next_cursor").value(nullValue()));
  }

  @Test
  void filtersByCategory() throws Exception {
    Ad sofa = moderated(Category.MOVEIS, NOW, new ModerationResult.Approved());
    moderated(Category.ELETRONICOS, NOW, new ModerationResult.Approved());

    mockMvc
        .perform(get("/ads").param("category", "moveis"))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value(sofa.getId()));
  }

  @Test
  void statusFilterShowsRejectedAdsWithTheirReason() throws Exception {
    moderated(Category.MOVEIS, NOW, new ModerationResult.Rejected("prohibited_item"));

    mockMvc
        .perform(get("/ads").param("status", "rejected"))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].rejection_reason").value("prohibited_item"));
  }

  @Test
  void pagesThroughEveryAdOnceNewestFirstUsingTheCursor() throws Exception {
    Ad newest = moderated(Category.MOVEIS, NOW, new ModerationResult.Approved());
    Ad tieLow = moderated(Category.MOVEIS, NOW.minusSeconds(1), new ModerationResult.Approved());
    Ad tieHigh = moderated(Category.MOVEIS, NOW.minusSeconds(1), new ModerationResult.Approved());
    Ad older = moderated(Category.MOVEIS, NOW.minusSeconds(2), new ModerationResult.Approved());
    Ad oldest = moderated(Category.MOVEIS, NOW.minusSeconds(3), new ModerationResult.Approved());

    List<Long> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      var request = get("/ads").param("limit", "2");
      if (cursor != null) {
        request.param("cursor", cursor);
      }
      String body =
          mockMvc
              .perform(request)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      List<Integer> ids = JsonPath.read(body, "$.data[*].id");
      ids.forEach(id -> seen.add(id.longValue()));
      cursor = JsonPath.read(body, "$.next_cursor");
      pages++;
    } while (cursor != null);

    // The first page ends inside the tie, so the cursor must break it by id.
    assertThat(seen)
        .containsExactly(
            newest.getId(), tieHigh.getId(), tieLow.getId(), older.getId(), oldest.getId());
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void emptyListingHasNoNextPage() throws Exception {
    mockMvc
        .perform(get("/ads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0))
        .andExpect(jsonPath("$.next_cursor").value(nullValue()));
  }

  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "limit, 0",
    "limit, 51",
    "status, archived",
    "category, carros",
    "cursor, not-a-cursor",
    "cursor, bm90LWEtY3Vyc29y"
  })
  void rejectsInvalidParametersPointingToThem(String param, String value) throws Exception {
    mockMvc
        .perform(get("/ads").param(param, value))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[*].field").value(hasItem(param)));
  }

  // --- helpers ---

  private Ad moderated(Category category, Instant createdAt, ModerationResult result) {
    Ad ad = repository.insert(newAd(category, createdAt));
    ad.applyModeration(result, POLICY, createdAt);
    repository.update(ad, createdAt);
    return ad;
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
