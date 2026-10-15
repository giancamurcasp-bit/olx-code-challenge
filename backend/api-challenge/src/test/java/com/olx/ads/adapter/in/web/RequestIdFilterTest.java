package com.olx.ads.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();
  private final AtomicReference<String> idSeenByTheRequest = new AtomicReference<>();
  private final MockFilterChain chain =
      new MockFilterChain(
          new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(
                jakarta.servlet.http.HttpServletRequest req,
                jakarta.servlet.http.HttpServletResponse res) {
              idSeenByTheRequest.set(MDC.get("request_id"));
            }
          });

  @Test
  void generatesARequestIdAvailableToLogsAndReturnsIt() throws Exception {
    var response = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest(), response, chain);

    assertThat(idSeenByTheRequest.get()).isNotBlank();
    assertThat(response.getHeader("X-Request-Id")).isEqualTo(idSeenByTheRequest.get());
  }

  @Test
  void keepsTheCallersRequestId() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "gateway-123");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(idSeenByTheRequest.get()).isEqualTo("gateway-123");
    assertThat(response.getHeader("X-Request-Id")).isEqualTo("gateway-123");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "with space", "forged\nlog line"})
  void replacesAnUnsafeRequestId(String unsafe) throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", unsafe);

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(idSeenByTheRequest.get()).isNotEqualTo(unsafe).isNotBlank();
  }

  @Test
  void clearsTheRequestIdAfterTheRequest() throws Exception {
    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

    assertThat(MDC.get("request_id")).isNull();
  }
}
