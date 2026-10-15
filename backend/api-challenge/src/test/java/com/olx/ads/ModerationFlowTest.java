package com.olx.ads;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** End to end: an ad created through the API is moderated by the real worker. */
@SpringBootTest(
    properties = {"moderation.worker.enabled=true", "moderation.worker.poll-interval=100ms"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ModerationFlowTest {

  @RegisterExtension
  static WireMockExtension moderation =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void moderationUrl(DynamicPropertyRegistry registry) {
    registry.add("moderation.base-url", moderation::baseUrl);
  }

  @Autowired MockMvc mockMvc;

  @Test
  void createdAdIsPublishedOrRejectedByTheWorker() throws Exception {
    moderation.stubFor(
        post("/v1/moderation/check").willReturn(okJson("{\"status\":\"approved\"}")));
    moderation.stubFor(
        post("/v1/moderation/check")
            .withRequestBody(matchingJsonPath("$.title", containing("whatsapp")))
            .willReturn(
                okJson(
                    "{\"status\":\"rejected\",\"rejection_reason\":\"contact_info_not_allowed\"}")));

    String approved = create("Sofá 3 lugares retrátil");
    String rejected = create("Celular, chama no whatsapp");

    awaitStatus(approved, "published");
    awaitStatus(rejected, "rejected");
    mockMvc
        .perform(get(rejected))
        .andExpect(jsonPath("$.rejection_reason").value("contact_info_not_allowed"));
  }

  @Test
  void aFailedCallIsRetriedUntilTheAdIsModerated() throws Exception {
    moderation.stubFor(
        post("/v1/moderation/check")
            .withRequestBody(matchingJsonPath("$.title", containing("Bicicleta")))
            .inScenario("flaky provider")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("recovered"));
    moderation.stubFor(
        post("/v1/moderation/check")
            .withRequestBody(matchingJsonPath("$.title", containing("Bicicleta")))
            .inScenario("flaky provider")
            .whenScenarioStateIs("recovered")
            .willReturn(okJson("{\"status\":\"approved\"}")));

    String ad = create("Bicicleta aro 29 Caloi");

    awaitStatus(ad, "published");
    mockMvc
        .perform(get(ad))
        .andExpect(jsonPath("$.moderation_attempts").value(2))
        .andExpect(jsonPath("$.last_moderation_error").value("http 500"));
  }

  private String create(String title) throws Exception {
    String body =
        """
        {"title": "%s", "description": "Em bom estado.", "price": 1200.00,
         "category": "moveis", "images": ["https://example.com/a.jpg"]}
        """
            .formatted(title);
    return mockMvc
        .perform(
            MockMvcRequestBuilders.post("/ads")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  private void awaitStatus(String location, String expected) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> mockMvc.perform(get(location)).andExpect(jsonPath("$.status").value(expected)));
  }
}
