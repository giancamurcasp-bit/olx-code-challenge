package com.olx.ads.adapter.in.web;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.olx.ads.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The published OpenAPI contract must describe what the API really returns. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiContractTest {

  private static final String AD = "$.components.schemas.AdResponse.properties";
  private static final String POST_ADS = "$.paths['/ads'].post";

  @Autowired MockMvc mockMvc;

  @Test
  void fieldsAreSnakeCaseLikeTheRealResponses() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(AD).value(hasKey("rejection_reason")))
        .andExpect(jsonPath(AD).value(hasKey("created_at")))
        .andExpect(jsonPath(AD).value(not(hasKey("rejectionReason"))))
        .andExpect(
            jsonPath("$.components.schemas.AdPageResponse.properties")
                .value(hasKey("next_cursor")));
  }

  @Test
  void createDocumentsItsRealResponsesAndRequiredHeader() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(jsonPath(POST_ADS + ".responses").value(hasKey("201")))
        .andExpect(jsonPath(POST_ADS + ".responses").value(hasKey("400")))
        .andExpect(jsonPath(POST_ADS + ".responses").value(hasKey("422")))
        .andExpect(jsonPath(POST_ADS + ".responses").value(not(hasKey("200"))))
        .andExpect(jsonPath(POST_ADS + ".responses['201'].headers").value(hasKey("Location")))
        .andExpect(
            jsonPath(POST_ADS + ".parameters[?(@.name == 'Idempotency-Key')].required")
                .value(hasItem(true)));
  }

  @Test
  void readsDocumentTheirErrors() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(jsonPath("$.paths['/ads'].get.responses").value(hasKey("400")))
        .andExpect(jsonPath("$.paths['/ads/{id}'].get.responses").value(hasKey("404")))
        .andExpect(jsonPath("$.info.title").value("Ads API"));
  }
}
