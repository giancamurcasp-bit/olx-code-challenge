package com.olx.ads.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.olx.ads.TestcontainersConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdApiTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;

  // --- create ---

  @Test
  void createsAPendingAdAndPointsToIt() throws Exception {
    mockMvc
        .perform(create(json(validAd())))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", matchesPattern(".*/ads/\\d+")))
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.title").value("iPhone 13 128GB azul"))
        .andExpect(jsonPath("$.price").value(3500.0))
        .andExpect(jsonPath("$.category").value("eletronicos"))
        .andExpect(jsonPath("$.images[0]").value("https://example.com/iphone.jpg"))
        .andExpect(jsonPath("$.status").value("pending"))
        .andExpect(jsonPath("$.moderation_attempts").value(0))
        .andExpect(jsonPath("$.rejection_reason").value(nullValue()))
        .andExpect(jsonPath("$.created_at").isString());
  }

  @Test
  void locationOfACreatedAdReturnsThatAd() throws Exception {
    String location =
        mockMvc
            .perform(create(json(validAd())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    mockMvc
        .perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("iPhone 13 128GB azul"))
        .andExpect(jsonPath("$.status").value("pending"));
  }

  // --- idempotency key ---

  @Test
  void retryWithTheSameIdempotencyKeyReturnsTheSameAd() throws Exception {
    String key = UUID.randomUUID().toString();

    String first =
        mockMvc
            .perform(createWithKey(key, validAd()))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andReturn()
            .getResponse()
            .getHeader("Location");

    mockMvc
        .perform(createWithKey(key, validAd()))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", first))
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.title").value("iPhone 13 128GB azul"));
  }

  @Test
  void reusingAnIdempotencyKeyForADifferentAdIsRejected() throws Exception {
    String key = UUID.randomUUID().toString();
    mockMvc.perform(createWithKey(key, validAd())).andExpect(status().isCreated());
    Map<String, Object> different = validAd();
    different.put("price", 3400.00);

    mockMvc
        .perform(createWithKey(key, different))
        .andExpect(status().isUnprocessableContent())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void rejectsCreationWithoutAnIdempotencyKey() throws Exception {
    mockMvc
        .perform(post("/ads").contentType(MediaType.APPLICATION_JSON).content(json(validAd())))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value(containsString("Idempotency-Key")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "chave com espaço"})
  void rejectsMalformedIdempotencyKey(String key) throws Exception {
    mockMvc
        .perform(createWithKey(key, validAd()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void rejectsIdempotencyKeyLongerThan255Characters() throws Exception {
    mockMvc.perform(createWithKey("k".repeat(256), validAd())).andExpect(status().isBadRequest());
  }

  // --- validation ---

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidAds")
  void rejectsInvalidAdsPointingToTheField(
      String scenario, Consumer<Map<String, Object>> change, String field) throws Exception {
    Map<String, Object> ad = validAd();
    change.accept(ad);

    mockMvc
        .perform(create(json(ad)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[*].field").value(hasItem(field)));
  }

  static Stream<Arguments> invalidAds() {
    return Stream.of(
        invalid("title too short", ad -> ad.put("title", "Curto"), "title"),
        invalid("title missing", ad -> ad.remove("title"), "title"),
        invalid("price zero", ad -> ad.put("price", 0), "price"),
        invalid("no images", ad -> ad.put("images", List.of()), "images"),
        invalid("image not http", ad -> ad.put("images", List.of("ftp://x.com/a.jpg")), "images"),
        invalid("unknown category", ad -> ad.put("category", "carros"), "category"),
        invalid("blank description", ad -> ad.put("description", "   "), "description"),
        invalid("NUL in title", ad -> ad.put("title", "iPhone 13\u0000 azul"), "title"),
        invalid("NUL in description", ad -> ad.put("description", "Ótimo\u0000"), "description"),
        invalid(
            "description too long", ad -> ad.put("description", "a".repeat(5001)), "description"),
        invalid(
            "image URL too long",
            ad -> ad.put("images", List.of("https://example.com/" + "a".repeat(2048))),
            "images"));
  }

  @Test
  void rejectsMalformedJson() throws Exception {
    mockMvc
        .perform(create("{\"title\":"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  // --- detail ---

  @Test
  void returnsNotFoundForUnknownAd() throws Exception {
    mockMvc
        .perform(get("/ads/" + Long.MAX_VALUE))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("ad " + Long.MAX_VALUE + " not found"));
  }

  @Test
  void rejectsNonNumericId() throws Exception {
    mockMvc.perform(get("/ads/abc")).andExpect(status().isBadRequest());
  }

  // --- helpers ---

  private static Map<String, Object> validAd() {
    Map<String, Object> ad = new LinkedHashMap<>();
    ad.put("title", "iPhone 13 128GB azul");
    ad.put("description", "Em ótimo estado, com caixa e nota fiscal.");
    ad.put("price", 3500.00);
    ad.put("category", "eletronicos");
    ad.put("images", List.of("https://example.com/iphone.jpg"));
    return ad;
  }

  private MockHttpServletRequestBuilder create(String body) {
    return post("/ads")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private MockHttpServletRequestBuilder createWithKey(String key, Map<String, Object> ad) {
    return post("/ads")
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(ad));
  }

  private static Arguments invalid(
      String scenario, Consumer<Map<String, Object>> change, String field) {
    return Arguments.of(scenario, change, field);
  }

  private String json(Object body) {
    return jsonMapper.writeValueAsString(body);
  }
}
