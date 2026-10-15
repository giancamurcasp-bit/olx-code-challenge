package com.olx.ads.adapter.out.moderation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.Category;
import com.olx.ads.domain.ModerationResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

class HttpModerationGatewayTest {

  private static final String CHECK_PATH = "/v1/moderation/check";
  private static final Duration READ_TIMEOUT = Duration.ofMillis(500);

  @RegisterExtension
  static WireMockExtension moderation =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private final Ad ad =
      Ad.create(
          "Sofá 3 lugares retrátil",
          "Tecido suede, ótimo estado.",
          new BigDecimal("1200.50"),
          Category.MOVEIS,
          List.of("https://example.com/sofa.jpg"),
          Instant.parse("2026-10-15T12:00:00Z"));

  // --- request ---

  @Test
  void sendsTheAdToTheModerationEndpoint() {
    respond(200, "{\"status\":\"approved\"}");

    gateway().check(ad);

    moderation.verify(
        postRequestedFor(urlEqualTo(CHECK_PATH))
            .withRequestBody(
                equalToJson(
                    """
                    {"title": "Sofá 3 lugares retrátil",
                     "description": "Tecido suede, ótimo estado.",
                     "price": 1200.50,
                     "category": "moveis"}
                    """)));
  }

  // --- 200 ---

  @Test
  void approvedResponseIsApproved() {
    respond(200, "{\"status\":\"approved\"}");

    assertThat(gateway().check(ad)).isEqualTo(new ModerationResult.Approved());
  }

  @Test
  void rejectedResponseCarriesTheReasonTheServerSends() {
    respond(200, "{\"status\":\"rejected\",\"rejection_reason\":\"contact_info_not_allowed\"}");

    assertThat(gateway().check(ad))
        .isEqualTo(new ModerationResult.Rejected("contact_info_not_allowed"));
  }

  @Test
  void rejectedResponseAlsoAcceptsTheFieldNameFromTheDocs() {
    respond(200, "{\"status\":\"rejected\",\"reason\":\"prohibited_item\"}");

    assertThat(gateway().check(ad)).isEqualTo(new ModerationResult.Rejected("prohibited_item"));
  }

  @Test
  void rejectedResponseWithoutReasonIsAPermanentFailure() {
    respond(200, "{\"status\":\"rejected\"}");

    assertThat(gateway().check(ad)).isInstanceOf(ModerationResult.PermanentFailure.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"status\":\"maybe\"}", "not json", ""})
  void unexpectedBodyIsAPermanentFailure(String body) {
    respond(200, body);

    assertThat(gateway().check(ad)).isInstanceOf(ModerationResult.PermanentFailure.class);
  }

  // --- errors ---

  @ParameterizedTest
  @ValueSource(ints = {400, 404})
  void clientErrorIsAPermanentFailureWithTheStatus(int status) {
    respond(status, "{\"error\":\"validation_error\"}");

    assertThat(gateway().check(ad))
        .isInstanceOfSatisfying(
            ModerationResult.PermanentFailure.class,
            failure -> assertThat(failure.error()).contains(String.valueOf(status)));
  }

  @Test
  void rateLimitedResponseWaitsForRetryAfter() {
    moderation.stubFor(
        post(CHECK_PATH).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "3")));

    assertThat(gateway().check(ad))
        .isEqualTo(new ModerationResult.RateLimited(Duration.ofSeconds(3)));
  }

  @Test
  void rateLimitedResponseWithoutRetryAfterWaitsOneSecond() {
    respond(429, "{\"error\":\"too_many_requests\"}");

    assertThat(gateway().check(ad))
        .isEqualTo(new ModerationResult.RateLimited(Duration.ofSeconds(1)));
  }

  @Test
  void serverErrorIsATransientFailure() {
    respond(500, "{\"error\":\"internal_error\"}");

    assertThat(gateway().check(ad)).isInstanceOf(ModerationResult.TransientFailure.class);
  }

  @Test
  void timeoutIsATransientFailure() {
    moderation.stubFor(
        post(CHECK_PATH)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"status\":\"approved\"}")
                    .withFixedDelay((int) READ_TIMEOUT.multipliedBy(3).toMillis())));

    assertThat(gateway().check(ad)).isInstanceOf(ModerationResult.TransientFailure.class);
  }

  @Test
  void unreachableServerIsATransientFailure() {
    var properties =
        new ModerationProperties("http://localhost:1", Duration.ofMillis(200), READ_TIMEOUT);

    assertThat(new HttpModerationGateway(RestClient.builder(), properties).check(ad))
        .isInstanceOf(ModerationResult.TransientFailure.class);
  }

  // --- helpers ---

  private HttpModerationGateway gateway() {
    var properties =
        new ModerationProperties(moderation.baseUrl(), Duration.ofMillis(200), READ_TIMEOUT);
    return new HttpModerationGateway(RestClient.builder(), properties);
  }

  private void respond(int status, String body) {
    moderation.stubFor(
        post(CHECK_PATH)
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }
}
