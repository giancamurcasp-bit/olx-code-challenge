package com.olx.ads.adapter.out.moderation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.olx.ads.application.port.ModerationGateway;
import com.olx.ads.domain.Ad;
import com.olx.ads.domain.ModerationResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the external moderation API and translates every outcome, including failures, into a {@link
 * ModerationResult} (DECISIONS.md, sections 2 and 3). Note: the provider documents the rejection
 * field as {@code reason} but actually sends {@code rejection_reason}; both are read.
 */
public class HttpModerationGateway implements ModerationGateway {

  private static final Logger log = LoggerFactory.getLogger(HttpModerationGateway.class);

  private static final String CHECK_PATH = "/v1/moderation/check";
  private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(1);
  private static final int MAX_ERROR_BODY_LENGTH = 200;

  private final RestClient restClient;

  public HttpModerationGateway(RestClient.Builder builder, ModerationProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            // The provider speaks HTTP/1.1; skip the HTTP/2 upgrade attempt on plain http.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
  }

  @Override
  public ModerationResult check(Ad ad) {
    CheckRequest request =
        new CheckRequest(
            ad.getTitle(), ad.getDescription(), ad.getPrice(), ad.getCategory().value());
    long start = System.nanoTime();
    try {
      return restClient
          .post()
          .uri(CHECK_PATH)
          .body(request)
          .exchange(
              (req, response) -> {
                log.info(
                    "Moderation API responded {} in {} ms",
                    response.getStatusCode().value(),
                    elapsedMillis(start));
                return toResult(response.getStatusCode(), response);
              });
    } catch (RestClientException e) {
      String error = "request failed: " + rootMessage(e);
      log.warn("Moderation API call failed after {} ms: {}", elapsedMillis(start), error);
      return new ModerationResult.TransientFailure(error);
    }
  }

  private static ModerationResult toResult(
      HttpStatusCode status, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
      throws IOException {
    if (status.value() == 429) {
      return new ModerationResult.RateLimited(
          retryAfter(response.getHeaders().getFirst("Retry-After")));
    }
    if (status.is5xxServerError()) {
      return new ModerationResult.TransientFailure("http " + status.value());
    }
    if (!status.is2xxSuccessful()) {
      return new ModerationResult.PermanentFailure(
          "http " + status.value() + ": " + errorBody(response));
    }
    CheckResponse body;
    try {
      body = response.bodyTo(CheckResponse.class);
    } catch (RestClientException e) {
      return new ModerationResult.PermanentFailure("unreadable response: " + rootMessage(e));
    }
    return body == null ? new ModerationResult.PermanentFailure("empty response") : toResult(body);
  }

  private static ModerationResult toResult(CheckResponse body) {
    if ("approved".equals(body.status())) {
      return new ModerationResult.Approved();
    }
    if ("rejected".equals(body.status())) {
      String reason = body.rejectionReason() != null ? body.rejectionReason() : body.reason();
      return reason == null || reason.isBlank()
          ? new ModerationResult.PermanentFailure("rejected without a reason")
          : new ModerationResult.Rejected(reason);
    }
    return new ModerationResult.PermanentFailure("unexpected status: " + body.status());
  }

  private static Duration retryAfter(String header) {
    if (header == null) {
      return DEFAULT_RETRY_AFTER;
    }
    try {
      long seconds = Long.parseLong(header.strip());
      return seconds >= 0 ? Duration.ofSeconds(seconds) : DEFAULT_RETRY_AFTER;
    } catch (NumberFormatException e) {
      return DEFAULT_RETRY_AFTER; // an HTTP date: the provider sends seconds, so not supported
    }
  }

  private static String errorBody(
      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException {
    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    return body.length() > MAX_ERROR_BODY_LENGTH ? body.substring(0, MAX_ERROR_BODY_LENGTH) : body;
  }

  private static long elapsedMillis(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
  }

  private static String rootMessage(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName()
        + (root.getMessage() != null ? ": " + root.getMessage() : "");
  }

  /** The provider's request contract; names are explicit so they don't follow our API's style. */
  record CheckRequest(
      @JsonProperty("title") String title,
      @JsonProperty("description") String description,
      @JsonProperty("price") BigDecimal price,
      @JsonProperty("category") String category) {}

  record CheckResponse(
      @JsonProperty("status") String status,
      @JsonProperty("rejection_reason") String rejectionReason,
      @JsonProperty("reason") String reason) {}
}
