package com.olx.ads.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.olx.ads.TestcontainersConfiguration;
import com.olx.ads.application.AdService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** A bug must surface as a generic 500 problem, without leaking internals to the client. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UnexpectedErrorApiTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean AdService adService;

  @Test
  void unexpectedErrorIsAProblemWithoutInternalDetails() throws Exception {
    when(adService.get(1)).thenThrow(new IllegalStateException("connection pool secret-host:5432"));

    mockMvc
        .perform(get("/ads/1"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(content().string(not(containsString("secret-host"))))
        .andExpect(jsonPath("$.detail").value("Unexpected error; it has been logged"));
  }
}
