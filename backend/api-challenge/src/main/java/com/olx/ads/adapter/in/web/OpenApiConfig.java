package com.olx.ads.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI contract served at {@code /v3/api-docs} and {@code /swagger-ui.html}. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Ads API")
                .version("1.0")
                .description(
                    """
                    Marketplace ads with automatic moderation. Ads are created as `pending` and \
                    moderated in the background, usually within seconds: poll `GET /ads/{id}` \
                    until `status` is no longer `pending`. Errors use RFC 9457 problem details."""));
  }

  /**
   * swagger-core builds schemas with its own Jackson 2 mapper, which doesn't see the app's
   * snake_case setting (Jackson 3); without this the contract would show {@code rejectionReason}
   * while the API returns {@code rejection_reason}.
   */
  @Bean
  ModelResolver snakeCaseModelResolver() {
    return new ModelResolver(
        Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
  }
}
